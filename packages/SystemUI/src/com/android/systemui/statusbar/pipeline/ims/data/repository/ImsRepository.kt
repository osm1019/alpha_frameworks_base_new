/*
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
import android.telephony.AccessNetworkConstants
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ImsStateCallback
import android.telephony.ims.RegistrationManager.RegistrationCallback
import android.telephony.ims.feature.MmTelFeature
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE
import android.util.Log
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

interface ImsRepository {
    val subId: Int
    val imsState: StateFlow<ImsStateModel>
}

@OptIn(ExperimentalCoroutinesApi::class)
class ImsRepositoryImpl(
    override val subId: Int,
    imsManager: ImsManager,
    subscriptionManager: SubscriptionManager,
    bgDispatcher: CoroutineDispatcher,
    scope: CoroutineScope,
) : ImsRepository {

    private val imsCallback: StateFlow<ImsCallbackState> = run {
        val initial = ImsCallbackState()

        if (imsManager == null) {
            return@run kotlinx.coroutines.flow.MutableStateFlow(initial)
        }

        if (!SubscriptionManager.isValidSubscriptionId(subId) || subscriptionManager == null) {
            return@run kotlinx.coroutines.flow.MutableStateFlow(initial)
        }

        val imsMmTelManager = runCatching { imsManager.getImsMmTelManager(subId) }.getOrNull()
            ?: return@run kotlinx.coroutines.flow.MutableStateFlow(initial)

        val imsEvents: Flow<CallbackEvent> = callbackFlow<CallbackEvent> {
            val registrationCallback = object : RegistrationCallback() {
                override fun onRegistered(attributes: ImsRegistrationAttributes) {
                    trySend(CallbackEvent.OnImsRegistrationChanged(true, attributes))
                }
                override fun onUnregistered(info: ImsReasonInfo) {
                    trySend(CallbackEvent.OnImsRegistrationChanged(false, null))
                }
            }

            val capabilityCallback = object : ImsMmTelManager.CapabilityCallback() {
                override fun onCapabilitiesStatusChanged(caps: MmTelFeature.MmTelCapabilities) {
                    trySend(CallbackEvent.OnImsCapabilitiesStatusChanged(caps))
                }
            }

            val stateCallback = object : ImsStateCallback() {
                var registered = false

                override fun onAvailable() {
                    if (registered) return
                    runCatching<Unit> {
                        imsMmTelManager.registerImsRegistrationCallback(
                            bgDispatcher.asExecutor(), registrationCallback
                        )
                        imsMmTelManager.registerMmTelCapabilityCallback(
                            bgDispatcher.asExecutor(), capabilityCallback
                        )
                        registered = true
                    }.onFailure {
                        // Don't close the flow here. This ImsStateCallback is still
                        // registered and healthy — only the MmTel feature went away
                        // mid-registration, which is routine during boot. Closing would
                        // make retryWhen register a *second* ImsStateCallback, and
                        // telephony keeps the stale wrapper for the life of the process.
                        // Drop whatever half registered and wait for the next
                        // onAvailable().
                        unregisterFeatureCallbacks()
                        registered = false
                    }
                }

                override fun onUnavailable(reason: Int) {
                    if (!registered) return
                    unregisterFeatureCallbacks()
                    registered = false
                }

                override fun onError() {
                    // Telephony has thrown this ImsStateCallback away; it will never fire
                    // again. Unwind and let retryWhen register a fresh one.
                    if (registered) {
                        unregisterFeatureCallbacks()
                        registered = false
                    }
                    close(ImsStateCallbackInvalidException())
                }

                private fun unregisterFeatureCallbacks() {
                    // One runCatching each: a throw on the first must not skip the second.
                    runCatching<Unit> {
                        imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                    }
                    runCatching<Unit> {
                        imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                    }
                }
            }

            val regResult: Result<Unit> = runCatching<Unit> {
                imsMmTelManager.registerImsStateCallback(bgDispatcher.asExecutor(), stateCallback)
            }
            if (regResult.isFailure) {
                close(regResult.exceptionOrNull())
                return@callbackFlow
            }

            awaitClose {
                // One runCatching each: the state callback is the one that must always
                // come off, so a throw unwinding the feature callbacks can't skip it.
                runCatching<Unit> { imsMmTelManager.unregisterImsStateCallback(stateCallback) }
                runCatching<Unit> {
                    imsMmTelManager.unregisterImsRegistrationCallback(registrationCallback)
                }
                runCatching<Unit> {
                    imsMmTelManager.unregisterMmTelCapabilityCallback(capabilityCallback)
                }
            }
        }
        imsEvents
            .retryWhen { cause: Throwable, attempt: Long ->
                // Registration throws until telephony is up, which on boot happens after
                // SystemUI starts. Every ImsException is retried: giving up on one leaves the
                // sub with no IMS state until SystemUI restarts. Anything else would duplicate
                // a registration we already hold.
                val retry = cause is ImsStateCallbackInvalidException || cause is ImsException
                if (retry) {
                    delay(retryDelayMs(attempt))
                }
                retry
            }
            .catch { cause: Throwable ->
                Log.w(TAG, "No IMS state for sub $subId", cause)
            }
            .scan(initial = initial) { state: ImsCallbackState, event: CallbackEvent ->
                state.applyEvent(event)
            }
            // stopTimeout survives brief unsubscribes without leaking IMS callbacks for
            // dead subs after SIM/eSIM changes (Eagerly would pin them forever).
            .stateIn(scope = scope, started = WhileSubscribedWithTimeout, initial)
    }

    override val imsState: StateFlow<ImsStateModel> =
        imsCallback
            .map { callbackState ->
                val registrationChanged = callbackState.onImsRegistrationChanged
                val capabilitiesChanged = callbackState.onImsCapabilitiesStatusChanged
                val registered = registrationChanged?.registered ?: false
                val capabilities = capabilitiesChanged?.capabilities
                val slotIndex = if (SubscriptionManager.isValidSubscriptionId(subId)) {
                    SubscriptionManager.getSlotIndex(subId)
                } else {
                    SubscriptionManager.INVALID_SIM_SLOT_INDEX
                }
                val attrs = registrationChanged?.attributes
                ImsStateModel(
                    subId = subId,
                    slotIndex = slotIndex,
                    activeSubCount = subscriptionManager.activeSubscriptionInfoCount,
                    registered = registered,
                    capabilities = capabilities,
                    registrationTech = attrs?.registrationTechnology
                        ?: REGISTRATION_TECH_NONE,
                    transportType = attrs?.transportType
                        ?: AccessNetworkConstants.TRANSPORT_TYPE_INVALID,
                )
            }
            .catch { emit(ImsStateModel()) /* on exception, just return default value */ }
            .stateIn(scope, WhileSubscribedWithTimeout, ImsStateModel())

    private companion object {
        const val TAG = "ImsRepository"

        const val FLOW_STOP_TIMEOUT_MS = 5_000L
        val WhileSubscribedWithTimeout: SharingStarted =
            SharingStarted.WhileSubscribed(stopTimeoutMillis = FLOW_STOP_TIMEOUT_MS)

        const val RETRY_BASE_MS = 1_000L
        const val RETRY_MAX_MS = 30_000L

        /** Backs off so a sub whose IMS service never comes up does not retry at 1 Hz forever. */
        fun retryDelayMs(attempt: Long): Long =
            (RETRY_BASE_MS shl attempt.coerceAtMost(5L).toInt()).coerceAtMost(RETRY_MAX_MS)
    }

    private class NoOpImsRepository(override val subId: Int) : ImsRepository {
        override val imsState: StateFlow<ImsStateModel> =
            kotlinx.coroutines.flow.MutableStateFlow(ImsStateModel())
    }

    class Factory
    @Inject
    constructor(
        private val subscriptionManager: SubscriptionManager,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
        @Application private val context: Context,
    ) {
        fun build(subId: Int): ImsRepository {
            val pm = context.packageManager
            // Only durable conditions belong here: the repository is cached per sub, so a
            // transient one would keep IMS state dead for the rest of the process.
            val hasIms = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_IMS)
            val hasValidSub = SubscriptionManager.isValidSubscriptionId(subId)

            val imsManager: ImsManager? =
                context.getSystemService(ImsManager::class.java)

            if (!hasIms || imsManager == null || !hasValidSub) {
                return NoOpImsRepository(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            }

            return ImsRepositoryImpl(
                subId = subId,
                imsManager = imsManager,
                subscriptionManager = subscriptionManager,
                bgDispatcher = bgDispatcher,
                scope = scope,
            )
        }
    }
}

/**
 * Telephony dropped our [ImsStateCallback] (see [ImsStateCallback.onError]); it will never fire
 * again, so the flow has to be re-collected to register a new one.
 */
private class ImsStateCallbackInvalidException : Exception("ImsStateCallback invalidated")

sealed interface CallbackEvent {
    data class OnImsRegistrationChanged(
        val registered: Boolean,
        val attributes: ImsRegistrationAttributes?
    ) : CallbackEvent

    data class OnImsCapabilitiesStatusChanged(
        val capabilities: MmTelFeature.MmTelCapabilities
    ) : CallbackEvent
}

data class ImsCallbackState(
    val onImsRegistrationChanged: CallbackEvent.OnImsRegistrationChanged? = null,
    val onImsCapabilitiesStatusChanged: CallbackEvent.OnImsCapabilitiesStatusChanged? = null
) {
    fun applyEvent(event: CallbackEvent): ImsCallbackState {
        return when (event) {
            is CallbackEvent.OnImsRegistrationChanged -> copy(onImsRegistrationChanged = event)
            is CallbackEvent.OnImsCapabilitiesStatusChanged -> copy(onImsCapabilitiesStatusChanged = event)
        }
    }
}
