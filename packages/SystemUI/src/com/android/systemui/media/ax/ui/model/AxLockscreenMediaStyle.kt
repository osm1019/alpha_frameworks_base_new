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

package com.android.systemui.media.ax.ui.model

/**
 * Look of the lockscreen media card, picked by the user in AlphaSettings
 * (`Settings.System.LOCKSCREEN_MEDIA_STYLE`).
 *
 * Values are persisted, so they may only be appended to.
 */
enum class AxLockscreenMediaStyle(val settingValue: Int) {
    /** Glass card: art thumbnail, waveform badge, wide transport. */
    GLASS(0),
    /** Single-row pill: circular art, bare controls, segmented progress. */
    MINIMAL(1),
    /** Waveform band across the card, five-slot transport. */
    WAVEFORM(2);

    companion object {
        val DEFAULT = GLASS

        fun fromSetting(value: Int): AxLockscreenMediaStyle =
            entries.firstOrNull { it.settingValue == value } ?: DEFAULT
    }
}
