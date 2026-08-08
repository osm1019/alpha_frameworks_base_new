/*
 * Copyright (C) 2024 The LibreMobileOS Foundation
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

package com.android.systemui.statusbar.pipeline.wifi.ui.model

import com.android.systemui.res.R
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.pipeline.wifi.shared.model.VoWifiState

sealed interface VoWifiIcon {
    data class Visible(val icon: Icon) : VoWifiIcon
    object Hidden : VoWifiIcon
}

/**
 * Maps [VoWifiState] to a status-bar icon.
 *
 * [VoWifiState.Enabled] always yields [VoWifiIcon.Visible] with a real drawable. Invalid or
 * unknown SIM slot indexes must not collapse to Hidden — that used to blank VoWiFi while the
 * mobile pipeline also suppressed HD for the same IMS state (dual-gone).
 */
val VoWifiState.icon: VoWifiIcon
    get() =
        when (this) {
            is VoWifiState.Enabled ->
                VoWifiIcon.Visible(
                    Icon.Resource(
                        resolveVoWifiDrawable(slots, activeSubCount),
                        /* contentDescription= */ null,
                    )
                )
            else -> VoWifiIcon.Hidden
        }

/**
 * Picks a VoWiFi glyph for the enabled state.
 *
 * Dual-SIM badges (sim1 / sim2 / dual) only when slot indexes are known (>= 0). Anything else
 * falls back to the generic [R.drawable.ic_vowifi] so Enabled is never undrawable.
 */
internal fun resolveVoWifiDrawable(slots: List<Int>, activeSubCount: Int): Int {
    val validSlots = slots.filter { it >= 0 }
    if (activeSubCount >= 2) {
        if (validSlots.size >= 2) {
            return R.drawable.ic_vowifi_dual
        }
        when (validSlots.firstOrNull()) {
            0 -> return R.drawable.ic_vowifi_one
            1 -> return R.drawable.ic_vowifi_two
        }
    }
    return R.drawable.ic_vowifi
}
