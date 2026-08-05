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

package com.android.systemui.media.ax.ui.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Shared chrome for Alpha media surfaces that speak the Phase 1 glass language:
 * Dynamic Bar keyguard chip, lockscreen media card, and (optionally) related players.
 *
 * Accent is reserved for play + progress; body stays dark glass, not full-fill Monet.
 */
object MediaChrome {
    /** ~80% dark glass body (not full-fill accent). */
    val GlassBody = Color(0xCC1C1C1E)
    val GlassBorder = Color.White.copy(alpha = 0.10f)
    val OnGlass = Color.White
    /**
     * Secondary / hint on glass. Alphas match Dynamic Bar island tokens
     * (`IslandContentTokens.AlphaSecondary` = 0.7f, `AlphaHint` = 0.4f) so chip and
     * media surfaces stay on one scale.
     */
    val OnGlassSecondary = Color.White.copy(alpha = 0.7f)
    val OnGlassHint = Color.White.copy(alpha = 0.4f)
    /** Neutral skip fill before accent wash. */
    val SkipNeutral = Color.White.copy(alpha = 0.12f)
    val ProgressTrack = Color.White.copy(alpha = 0.18f)
    val ProgressHeight = 2.dp

    /** Lockscreen card corner radius — softer than a QS tile. */
    val LockscreenCornerRadius = 28.dp

    /**
     * Skip / secondary control fill: opaque blend of glass toward art accent so icons stay
     * legible (a low-alpha overlay vanishes on dark glass).
     */
    fun skipBackground(accent: Color, amount: Float = 0.55f): Color =
        lerp(GlassBody, accent, amount)
}
