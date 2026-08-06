/*
 * Copyright (C) 2026 The AlphaDroid Project
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

package com.android.systemui.qs.ax.data.repository

import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.ax.ui.model.AxLockscreenMediaStyle
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Media settings the Ax QS media control reacts to. */
@SysUISingleton
class AxMediaSettingsRepository
@Inject
constructor(
    private val secureSettings: SecureSettings,
    private val systemSettings: SystemSettings,
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    /**
     * "Pin media player" - keeps the last played app in the media control after its session goes
     * away, as a resumable card rather than the empty placeholder.
     *
     * The pipeline half of this setting is handled by [MediaDataProcessor]; this is the UI half,
     * which decides whether the inactive entries it produces are rendered at all.
     */
    val isMediaResumptionEnabled: StateFlow<Boolean> =
        secureSettings
            .observerFlow(UserHandle.USER_ALL, Settings.Secure.MEDIA_CONTROLS_RESUME)
            .onStart { emit(Unit) }
            .map { readResumptionSetting() }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, readResumptionSetting())

    /** Which lockscreen media card layout the user picked. */
    val lockscreenMediaStyle: StateFlow<AxLockscreenMediaStyle> =
        systemSettings
            .observerFlow(UserHandle.USER_ALL, Settings.System.LOCKSCREEN_MEDIA_STYLE)
            .onStart { emit(Unit) }
            .map { readStyleSetting() }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)
            .stateIn(applicationScope, SharingStarted.Eagerly, readStyleSetting())

    private fun readStyleSetting(): AxLockscreenMediaStyle =
        AxLockscreenMediaStyle.fromSetting(
            systemSettings.getIntForUser(
                Settings.System.LOCKSCREEN_MEDIA_STYLE,
                AxLockscreenMediaStyle.DEFAULT.settingValue,
                UserHandle.USER_CURRENT,
            )
        )

    private fun readResumptionSetting(): Boolean =
        secureSettings.getBoolForUser(
            Settings.Secure.MEDIA_CONTROLS_RESUME,
            true,
            UserHandle.USER_CURRENT,
        )
}
