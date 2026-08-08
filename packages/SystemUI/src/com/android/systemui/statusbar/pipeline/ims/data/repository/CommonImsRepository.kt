/*
 * Copyright (C) 2022 The Android Open Source Project
 * Copyright (C) 2024 The LibreMobileOS Foundation
 * Copyright (C) 2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.pipeline.ims.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsIconModel
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

interface CommonImsRepository {
    val imsStates: StateFlow<List<ImsStateModel>>
    val imsIconState: StateFlow<ImsIconModel>
    fun getRepoForSubId(subId: Int): ImsRepository
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommonImsRepositoryImpl
@Inject
constructor(
    private val subscriptionManager: SubscriptionManager,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val imsRepoFactory: ImsRepositoryImpl.Factory,
    @Application private val context: Context,
    private val userTracker: UserTracker,
    @Main private val mainExecutor: Executor,
) : CommonImsRepository {

    // Reached from both the wifi and the mobile pipelines: computeIfAbsent is what keeps a sub
    // to one repository, and so to one ImsStateCallback.
    private val subIdRepositoryCache = ConcurrentHashMap<Int, ImsRepository>()

    private val mobileSubscriptionsChangeEvent: Flow<Unit> =
        if (!hasTelephony()) {
            flowOf(Unit)
        } else conflatedCallbackFlow {
            val callback = object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() { trySend(Unit) }
            }
            try {
                subscriptionManager.addOnSubscriptionsChangedListener(bgDispatcher.asExecutor(), callback)
            } catch (_: SecurityException) {
                trySend(Unit)
                close()
                return@conflatedCallbackFlow
            }
            awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(callback) }
        }

    private val subscriptions: StateFlow<List<Int>> =
        mobileSubscriptionsChangeEvent
            .mapLatest { fetchSubscriptionsList().map { it.subscriptionId } }
            .onEach { ids -> dropUnusedReposFromCache(ids) }
            .distinctUntilChanged()
            // stopTimeout bridges brief unsubscribes without pinning telephony forever.
            .stateIn(scope, started = WhileSubscribedWithTimeout, listOf())

    private fun dropUnusedReposFromCache(newIds: List<Int>) {
        // Drop repos for subs that no longer exist. With WhileSubscribed(+timeout) their
        // stateIn collectors stop and IMS callbacks are unregistered after the timeout.
        subIdRepositoryCache.keys.retainAll(newIds.toSet())
    }

    private fun hasTelephony(): Boolean =
        try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        } catch (_: Throwable) { false }

    /**
     * True if the checked subId is in the list of current subs
     *
     * @param checkedSubIds the list to validate [subId] against. To invalidate the cache, pass in the
     *   new subscription list. Otherwise use [subscriptions.value] to validate a subId against the
     *   current known subscriptions
     */
    private fun checkSub(subId: Int, checkedSubIds: List<Int>): Boolean {
        checkedSubIds.forEach {
            if (it == subId) {
                return true
            }
        }
        return false
    }

    private suspend fun fetchSubscriptionsList(): List<SubscriptionInfo> =
        withContext(bgDispatcher) {
            try {
                subscriptionManager.completeActiveSubscriptionInfoList ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        }

    override val imsStates: StateFlow<List<ImsStateModel>> =
        subscriptions
            .map { ids -> ids.map { getRepoForSubId(it) } }
            .flatMapLatest { repos ->
                if (repos.isEmpty()) flowOf(emptyList())
                else combine(repos.map { it.imsState }) { it.toList() }
            }
            .stateIn(scope, started = WhileSubscribedWithTimeout, listOf())

    /**
     * User toggles for HD / VoWiFi status-bar icons.
     *
     * Observed **directly** from Settings.Secure (not via TunerService) so live writes from
     * the :tuner process apply without a SystemUI restart. Uses [UserTracker.userId] (not
     * process user 0) and re-reads on user switch.
     *
     * Started eagerly: a cheap ContentObserver is fine to keep for the SysUI lifetime and
     * guarantees the force-hide flags stay current even when no icon binder is collecting.
     */
    override val imsIconState: StateFlow<ImsIconModel> = conflatedCallbackFlow {
        val cr = context.contentResolver

        fun readIconState(): ImsIconModel {
            val userId = userTracker.userId
            val showHd =
                Settings.Secure.getIntForUser(cr, KEY_HD_ICON, /* def= */ 0, userId) != 0
            val showVowifi =
                Settings.Secure.getIntForUser(cr, KEY_VOWIFI_ICON, /* def= */ 0, userId) != 0
            return ImsIconModel(showHdIcon = showHd, showVowifiIcon = showVowifi)
        }

        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(readIconState())
                }

                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    trySend(readIconState())
                }

                override fun onChange(
                    selfChange: Boolean,
                    uris: Collection<Uri>,
                    flags: Int,
                    userId: Int,
                ) {
                    if (userId != UserHandle.USER_ALL && userId != userTracker.userId) {
                        return
                    }
                    trySend(readIconState())
                }
            }

        // USER_ALL so notify from any settings write path reaches us; we re-read for the
        // current UserTracker user in readIconState().
        cr.registerContentObserver(
            Settings.Secure.getUriFor(KEY_HD_ICON),
            /* notifyForDescendants= */ false,
            observer,
            UserHandle.USER_ALL,
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor(KEY_VOWIFI_ICON),
            /* notifyForDescendants= */ false,
            observer,
            UserHandle.USER_ALL,
        )

        val userCallback =
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) {
                    trySend(readIconState())
                }
            }
        userTracker.addCallback(userCallback, mainExecutor)

        trySend(readIconState())

        awaitClose {
            cr.unregisterContentObserver(observer)
            userTracker.removeCallback(userCallback)
        }
    }.stateIn(
        scope,
        started = SharingStarted.Eagerly,
        initialValue = ImsIconModel(),
    )

    override fun getRepoForSubId(subId: Int): ImsRepository =
        getOrCreateRepoForSubId(subId)

    private fun getOrCreateRepoForSubId(subId: Int): ImsRepository =
        subIdRepositoryCache.computeIfAbsent(subId) { createRepositoryForSubId(it) }

    private fun createRepositoryForSubId(subId: Int): ImsRepository {
        return imsRepoFactory.build(subId)
    }

    private companion object {
        const val KEY_HD_ICON = "status_bar_show_hd_calling"
        const val KEY_VOWIFI_ICON = "status_bar_show_vowifi"

        /** Survive collector flaps without pinning IMS callbacks for the process lifetime. */
        const val FLOW_STOP_TIMEOUT_MS = 5_000L
        val WhileSubscribedWithTimeout: SharingStarted =
            SharingStarted.WhileSubscribed(stopTimeoutMillis = FLOW_STOP_TIMEOUT_MS)
    }
}
