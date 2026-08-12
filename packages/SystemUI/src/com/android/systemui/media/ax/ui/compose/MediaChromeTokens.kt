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

    private const val AlphaOpenDark = 0x4D / 255f // ~0.30 — lockscreen with blur / art frost
    private const val AlphaNoBlur = 0xD9 / 255f // ~0.85 — lockscreen when blur is off

    /**
     * Day mode needs a denser open body than night. What sits behind the frost is wallpaper and
     * album art, and neither follows the theme — at 0.30 a light seed loses to a dark wallpaper
     * and `onSurface` text lands on a pane that is still visually dark. Night has no such
     * problem because the seed already agrees with a typical wallpaper.
     */
    private const val AlphaOpenLight = 0.72f

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
        get() = MaterialTheme.colorScheme.surfaceContainerHigh

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
        get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

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
        get() = OnGlass.copy(alpha = if (isDark) 0.38f else 0.18f)

    val LockscreenGlassBorderWidth = 1.dp

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
    fun accentSweep(accent: Color, degrees: Float = 62f): Brush =
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
            0.35f to tip.copy(alpha = 0.35f),
            1f to tip,
            startX = 0f,
            endX = endX,
        )

    val LockscreenGlassElevation = 14.dp
    val LockscreenGlassBlurRadius = 56.dp

    /**
     * Primary content on glass — the partner of [seed]. Paired by the palette, so this needs no
     * per-mode branch and no contrast guard of our own.
     */
    val OnGlass: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface

    /**
     * Secondary / hint on glass. Alphas match Dynamic Bar island tokens
     * (`AlphaSecondary` = 0.7f, `AlphaHint` = 0.4f).
     */
    val OnGlassSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) 0.7f else 0.62f)

    val OnGlassHint: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) 0.4f else 0.42f)

    /** Neutral skip / badge fill before accent wash. */
    val SkipNeutral: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) 0.12f else 0.08f)

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
        get() = OnGlass.copy(alpha = 0.92f)

    val LockscreenProgressTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = 0.22f)

    val LockscreenProgressThumb: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass

    val LockscreenArtSize = 88.dp
    val LockscreenArtCorner = 20.dp

    val ProgressTrack: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = 0.18f)

    val ProgressHeight = 2.dp

    val LockscreenCornerRadius = 28.dp

    /**
     * Skip / secondary control fill: blend of glass body toward art accent so icons stay
     * legible (a low-alpha overlay vanishes on dark glass).
     */
    @Composable
    @ReadOnlyComposable
    fun skipBackground(accent: Color, amount: Float = 0.55f): Color =
        lerp(GlassBody, accent, amount)

    /** Event-tint border on non-media DB chips (hairline over tinted glass). */
    val EventTintBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = OnGlass.copy(alpha = if (isDark) 0.16f else 0.12f)
}
