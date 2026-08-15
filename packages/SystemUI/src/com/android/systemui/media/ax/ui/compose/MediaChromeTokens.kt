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
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.alpha.theme.AlphaMetrics
import com.android.systemui.alpha.theme.AlphaOpacity

/**
 * Colour **maths** for media surfaces — the operations, not the values.
 *
 * This used to hold both, and that was the problem: one `OnGlass`, one `LockscreenProgress`, one
 * `SkipNeutral`, each read by four or five different surfaces, so tuning any of them moved things
 * nobody asked to move. Every value now lives on its surface's object in [AlphaColors]; what is
 * left here are the four transforms that take a colour and return another one.
 *
 * Nothing in this file decides what any surface looks like. Add a value to [AlphaColors], add a
 * transform here.
 */
object MediaChrome {

    /**
     * An art-derived colour, normalised to the one band a *filled control* can live in.
     *
     * Keeps the artwork's hue and chroma, replaces its weight. The incoming colour cannot be
     * trusted to be a fill: remedia hands us `primaryFixed`, a tone-90 pastel by construction and
     * identical in both themes, on which [AlphaColors.onAccentColor] disappears and which vanishes
     * into a light card. Everything that paints a solid accent goes through here, so the near-white
     * glyph reads on all of them by construction rather than by measurement.
     *
     * One number for both themes: the ceiling is set by the glyph, and the glyph does not change.
     */
    fun accentFill(accent: Color): Color =
        normalisedAccent(accent, AlphaMetrics.mediaAccentFillLuminance)

    /**
     * The same album colour normalised for *tinting a body* rather than filling a control, to the
     * [luminance] the calling surface asks for.
     *
     * A different number from [accentFill] on purpose: a fill has a hard ceiling because the
     * near-white glyph must read on it, a tint has none because the content colour is measured
     * against the mixed result afterwards.
     */
    fun accentTint(accent: Color, luminance: Float): Color = normalisedAccent(accent, luminance)

    /**
     * Solve for the HSL lightness whose result has the requested **relative luminance**.
     *
     * Setting lightness directly, which this used to do, is not the same thing: HSL lightness is a
     * position between black and white on a per-hue ramp, not a measure of how bright the result
     * is. At a fixed 0.46 the palette spanned 0.086 to 0.489 in real luminance — a factor of six —
     * so a single constant meant the near-white glyph cleared 7.4:1 on indigo and 1.86:1 on yellow.
     * Six of nine hues failed the contrast floor the constant existed to guarantee.
     *
     * Luminance rises monotonically with lightness at a fixed hue and saturation, so halving the
     * interval converges; ten steps land within a thousandth, well under a quantisation step.
     */
    private fun normalisedAccent(accent: Color, luminance: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent.toArgb(), hsl)
        hsl[1] = hsl[1].coerceAtLeast(AlphaMetrics.mediaAccentFillSaturationFloor)
        var low = 0f
        var high = 1f
        repeat(LuminanceSolveSteps) {
            val mid = (low + high) / 2f
            hsl[2] = mid
            if (ColorUtils.calculateLuminance(ColorUtils.HSLToColor(hsl)) < luminance) {
                low = mid
            } else {
                high = mid
            }
        }
        hsl[2] = (low + high) / 2f
        return Color(ColorUtils.HSLToColor(hsl))
    }

    private const val LuminanceSolveSteps = 10

    /** Sweep from the art accent to a hue-rotated sibling — the Waveform band and rim. */
    fun accentSweep(accent: Color, degrees: Float = AlphaMetrics.accentSweepDegrees): Brush =
        Brush.horizontalGradient(listOf(accent, accent.rotateHue(degrees)))

    private fun Color.rotateHue(degrees: Float): Color {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(toArgb(), hsv)
        hsv[0] = (hsv[0] + degrees).mod(360f)
        return Color(AndroidColor.HSVToColor(hsv))
    }

    /**
     * Played portion of a timeline: a trail that fades in behind the thumb.
     *
     * Not composable, so it can run inside a [androidx.compose.foundation.Canvas] draw scope — pass
     * the surface's own progress colour, captured during composition.
     */
    fun lockscreenProgressTrail(endX: Float, tip: Color): Brush =
        Brush.horizontalGradient(
            0f to tip.copy(alpha = 0f),
            AlphaOpacity.progressTrailMidAlpha to
                tip.copy(alpha = AlphaOpacity.progressTrailMidAlpha),
            1f to tip,
            startX = 0f,
            endX = endX,
        )
}
