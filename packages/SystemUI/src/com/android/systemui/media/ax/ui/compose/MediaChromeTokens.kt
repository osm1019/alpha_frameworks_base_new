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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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

    private val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = isSystemInDarkTheme()

    /**
     * An art-derived colour, normalised to the one band a *filled control* can live in.
     *
     * Keeps the artwork's hue and chroma, replaces its lightness. The incoming colour cannot be
     * trusted to be a fill: remedia hands us `primaryFixed`, a tone-90 pastel by construction and
     * identical in both themes, on which [AlphaColors.onAccentColor] disappears and which vanishes
     * into a light card. Everything that paints a solid accent goes through here, so the near-white
     * glyph reads on all of them by construction rather than by measurement.
     */
    @Composable
    @ReadOnlyComposable
    fun accentFill(accent: Color): Color =
        normalisedAccent(
            accent,
            if (isDark) AlphaMetrics.mediaAccentFillLightnessDark
            else AlphaMetrics.mediaAccentFillLightnessLight,
        )

    /**
     * The same album colour normalised for *tinting a body* rather than filling a control.
     *
     * A different lightness on purpose: a fill has a hard ceiling because the near-white glyph must
     * read on it, a tint has none because the content colour is measured against the mixed result
     * afterwards. See [AlphaMetrics.mediaAccentTintLightnessDark].
     */
    @Composable
    @ReadOnlyComposable
    fun accentTint(accent: Color): Color =
        normalisedAccent(
            accent,
            if (isDark) AlphaMetrics.mediaAccentTintLightnessDark
            else AlphaMetrics.mediaAccentTintLightnessLight,
        )

    private fun normalisedAccent(accent: Color, lightness: Float): Color {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent.toArgb(), hsl)
        hsl[1] = hsl[1].coerceAtLeast(AlphaMetrics.mediaAccentFillSaturationFloor)
        hsl[2] = lightness
        return Color(ColorUtils.HSLToColor(hsl))
    }

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
