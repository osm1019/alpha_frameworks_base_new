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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.android.systemui.alpha.theme.AlphaOpacity
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.alpha.theme.AlphaMetrics

/**
 * Shared chrome for Alpha media + Dynamic Bar glass surfaces.
 *
 * **The seed is a Material role, not a hex.** Body is `surfaceContainerHigh` and content is
 * `onSurface` — the same pair the lockscreen shortcut buttons use
 * (`KeyguardQuickAffordanceViewBinder`). Day/night and the user's Monet palette come for free,
 * and the keyguard pill matches the shortcuts it sits between by construction rather than by
 * a hand-picked colour that has to be re-tuned every time the theme moves.
 *
 * Density is the only thing this file decides:
 *
 * | Surface | Body | Why |
 * |---------|------|-----|
 * | DB chip / expand card | opaque | Sits over status icons, notifications, wallpaper — a controlled surface, like the shortcut buttons |
 * | Lockscreen card with frost | open (denser in day) | Blur / art behind it is the whole point |
 * | Lockscreen card, blur off | `AlphaNoBlur` | Without frost an open pane leaves content on bare wallpaper |
 *
 * Play stays per-style (art `primary` / bare / accent ring). Dynamic Bar non-media chips tint
 * the body with the event accent via `islandGlassChrome` so colour codes survive.
 *
 * QS media is a separate track — same tokens later, not wired through axdynamicbar.
 */
object MediaChrome {

    private const val AlphaOpenDark = AlphaOpacity.mediaCardFrostAlphaDark
    private const val AlphaNoBlur = AlphaOpacity.mediaCardFrostAlphaNoBlur

    /**
     * Day mode needs a denser open body than night. What sits behind the frost is wallpaper and
     * album art, and neither follows the theme — at 0.30 a light seed loses to a dark wallpaper
     * and `onSurface` text lands on a pane that is still visually dark. Night has no such
     * problem because the seed already agrees with a typical wallpaper.
     */
    private const val AlphaOpenLight = AlphaOpacity.mediaCardFrostAlphaLight

    private val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = isSystemInDarkTheme()

    /**
     * Body seed. Opaque: the shortcut buttons beside the keyguard pill are opaque too, and a
     * translucent chip over status icons or notification text reads as a smear, not a pane.
     */
    private val seed: Color
        @Composable
        @ReadOnlyComposable
        get() = AlphaColors.chipBodyColor

    private fun Color.withAlpha(a: Float): Color = copy(alpha = a)

    /** Opaque glass body — DB chip / expand panel (not full-fill accent). */
    val GlassBody: Color
        @Composable
        @ReadOnlyComposable
        get() = seed

    /**
     * Hairline that separates one glass pane from another. Our expand card can land on top of a
     * notification card, which resolves to a neighbouring surface role — without this they merge.
     */
    val GlassBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = AlphaColors.chipRimColor

    /**
     * Lockscreen media card body. Open so wallpaper / art frost tints the pane.
     */
    val LockscreenGlassBody: Color
        @Composable
        @ReadOnlyComposable
        get() = seed.withAlpha(if (isDark) AlphaOpenDark else AlphaOpenLight)

    /**
     * Luminous rim on the lockscreen card and art thumbnail — one material, two panes.
     */
    val LockscreenGlassBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) AlphaOpacity.mediaCardRimAlphaDark else AlphaOpacity.mediaCardRimAlphaLight)

    val LockscreenGlassBorderWidth = AlphaMetrics.chipRimWidth

    /**
     * Denser body when the compositor refuses cross-window blur (dev option, power save).
     * Without frost, an open pane leaves content sitting on bare wallpaper.
     */
    val LockscreenGlassBodyNoBlur: Color
        @Composable
        @ReadOnlyComposable
        get() = seed.withAlpha(AlphaNoBlur)

    /**
     * Sweep from the art accent to a hue-rotated sibling — Waveform band + rim.
     */
    fun accentSweep(accent: Color, degrees: Float = AlphaMetrics.accentSweepDegrees): Brush =
        Brush.horizontalGradient(listOf(accent, accent.rotateHue(degrees)))

    private fun Color.rotateHue(degrees: Float): Color {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(toArgb(), hsv)
        hsv[0] = (hsv[0] + degrees).mod(360f)
        return Color(AndroidColor.HSVToColor(hsv))
    }

    /**
     * Played portion of the Glass timeline: fade-in trail behind the thumb.
     * Non-composable so it can run inside [Canvas] draw scopes — pass [LockscreenProgress]
     * (or any tip colour) captured during composition.
     */
    fun lockscreenProgressTrail(endX: Float, tip: Color): Brush =
        Brush.horizontalGradient(
            0f to tip.copy(alpha = 0f),
            AlphaOpacity.progressTrailMidAlpha to tip.copy(alpha = AlphaOpacity.progressTrailMidAlpha),
            1f to tip,
            startX = 0f,
            endX = endX,
        )

    val LockscreenGlassElevation = AlphaMetrics.mediaCardElevation
    val LockscreenGlassBlurRadius = AlphaMetrics.mediaCardBlurRadius

    /**
     * Primary content on glass — the partner of [seed]. Paired by the palette, so this needs no
     * per-mode branch and no contrast guard of our own.
     */
    val OnGlass: Color
        @Composable
        @ReadOnlyComposable
        get() = AlphaColors.chipTextColor

    /**
     * Secondary / hint on glass. Alphas match Dynamic Bar island tokens
     * (`AlphaSecondary` = 0.7f, `AlphaHint` = 0.4f).
     */
    val OnGlassSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) AlphaOpacity.mediaTextSecondaryAlphaDark else AlphaOpacity.mediaTextSecondaryAlphaLight)

    val OnGlassHint: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) AlphaOpacity.mediaTextHintAlphaDark else AlphaOpacity.mediaTextHintAlphaLight)

    /** Neutral skip / badge fill before accent wash. */
    val SkipNeutral: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) AlphaOpacity.mediaButtonPlateAlphaDark else AlphaOpacity.mediaButtonPlateAlphaLight)

    /**
     * Bare control tint on glass. Lockscreen controls are neutral except Glass play
     * (art primary). Progress tokens stay quiet, not accent squiggle.
     */
    val ControlBare: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass

    val LockscreenProgress: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = AlphaOpacity.progressTipAlpha)

    val LockscreenProgressTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = AlphaOpacity.progressTrackAlpha)

    val LockscreenProgressThumb: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass

    val LockscreenArtSize = AlphaMetrics.mediaArtSize
    val LockscreenArtCorner = AlphaMetrics.mediaArtCornerRadius

    val ProgressTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = AlphaOpacity.progressTrackAlphaQs)

    val ProgressHeight = AlphaMetrics.progressHeight

    val LockscreenCornerRadius = AlphaMetrics.cardCornerRadius

    /**
     * Skip / secondary control fill: blend of glass body toward art accent so icons stay
     * legible (a low-alpha overlay vanishes on dark glass).
     */
    @Composable
    @ReadOnlyComposable
    fun skipBackground(accent: Color, amount: Float = AlphaOpacity.buttonPlateBlendAmount): Color =
        lerp(GlassBody, accent, amount)

    /** Event-tint border on non-media DB chips (hairline over tinted glass). */
    val EventTintBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) AlphaOpacity.chipRimAlphaDark else AlphaOpacity.chipRimAlphaLight)
}
