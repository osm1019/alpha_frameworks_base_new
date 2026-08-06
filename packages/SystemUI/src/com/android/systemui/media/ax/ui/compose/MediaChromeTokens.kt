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

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * Shared chrome for Alpha media surfaces that speak the Phase 1 glass language:
 * Dynamic Bar keyguard chip, lockscreen media card, and (optionally) related players.
 *
 * Accent is reserved for play + progress; body stays dark glass, not full-fill Monet.
 *
 * The lockscreen card uses a more open glass ([LockscreenGlassBody]) so the wallpaper can tint
 * the pane the way the Style 1 mockup does. The denser [GlassBody] stays on the DB chip and
 * expand panel, where the surface sits over UI chrome rather than the wallpaper.
 */
object MediaChrome {
    /** ~80% dark glass body — DB chip / expand panel (not full-fill accent). */
    val GlassBody = Color(0xCC1C1C1E)
    val GlassBorder = Color.White.copy(alpha = 0.10f)

    /**
     * Lockscreen media card body. ~40% so the wallpaper shows through and tints the pane;
     * paired with [LockscreenGlassBlurRadius] for a frosted read. Denser than air, open enough
     * that a bright sky no longer paints the card as a black slab.
     */
    val LockscreenGlassBody = Color(0x4D1C1C1E)
    /**
     * Luminous rim, uniform on every edge — and on the art thumbnail, so the two panes read as the
     * same material. A gradient rim looked like a lighting trick; a single bright hairline is what
     * the reference actually has.
     */
    val LockscreenGlassBorder = Color.White.copy(alpha = 0.38f)
    val LockscreenGlassBorderWidth = 1.dp

    /**
     * Denser body for when the compositor refuses cross-window blur (developer option, power save,
     * unsupported hardware). Without the frost behind it, an open pane leaves white text sitting on
     * a bright wallpaper — the volume dialog swaps colours the same way.
     */
    val LockscreenGlassBodyNoBlur = Color(0xD91C1C1E)

    /**
     * Sweep from the art accent to a hue-rotated sibling — the Waveform style's signature, used for
     * its band and its rim. Derived from the art so it stays the track's own palette.
     */
    fun accentSweep(accent: Color, degrees: Float = 62f): Brush =
        Brush.horizontalGradient(listOf(accent, accent.rotateHue(degrees)))

    private fun Color.rotateHue(degrees: Float): Color {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(toArgb(), hsv)
        hsv[0] = (hsv[0] + degrees).mod(360f)
        return Color(AndroidColor.HSVToColor(hsv))
    }

    /**
     * Played portion of the Glass timeline: a trail that fades in behind the thumb rather than a
     * flat filled bar.
     */
    fun lockscreenProgressTrail(endX: Float): Brush =
        Brush.horizontalGradient(
            0f to LockscreenProgress.copy(alpha = 0f),
            0.35f to LockscreenProgress.copy(alpha = 0.35f),
            1f to LockscreenProgress,
            startX = 0f,
            endX = endX,
        )
    /** Soft drop shadow so the card lifts off the wallpaper. */
    val LockscreenGlassElevation = 14.dp
    /**
     * Cross-window blur radius for [BackgroundBlurDrawable] on the lockscreen card backdrop
     * (same path as the volume dialog). Devices that refuse blur still get the open tinted body.
     */
    val LockscreenGlassBlurRadius = 56.dp

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

    /**
     * Lockscreen card controls are neutral, not accent: white play fill with a dark glyph, and
     * skips drawn bare on the glass. Progress on the Glass style is also neutral (see
     * [LockscreenProgress] / [LockscreenProgressTrack]) — the mockup is a quiet white timeline,
     * not an accent squiggle.
     */
    val PlayNeutralFill = Color.White.copy(alpha = 0.95f)
    val OnPlayNeutral = Color(0xFF1C1C1E)
    val ControlBare = OnGlass
    val LockscreenProgress = Color.White.copy(alpha = 0.92f)
    val LockscreenProgressTrack = Color.White.copy(alpha = 0.22f)
    val LockscreenProgressThumb = Color.White

    /** Art thumbnail on the lockscreen card. */
    val LockscreenArtSize = 88.dp
    val LockscreenArtCorner = 20.dp
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
