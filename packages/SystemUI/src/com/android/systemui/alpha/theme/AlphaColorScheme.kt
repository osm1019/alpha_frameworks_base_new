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

package com.android.systemui.alpha.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Every colour Alpha's Compose surfaces paint with, in one place.
 *
 * Consumers: the media stack, the Dynamic Bar stack, the lockscreen pill, and the
 * statusbar / cutout chip. They reference these names and never author a colour of their own —
 * need a new one, add it here; need an exception, add the exception here and use it where it
 * applies.
 *
 * Members are named for the **element** they paint (`mediaButtonColor`, `chipBodyColor`), with a
 * `Light` / `Dark` suffix only where an element genuinely carries two values. The one exception
 * is the event palette at the top: those are shared crayons, assigned to events by
 * `eventStyleFor`, so naming them after any single element would be a lie.
 *
 * **This file is currently an extraction, not a design.** Every value is exactly what the call
 * site resolved to before it was lifted here, so adopting it changes nothing on screen. Tuning
 * happens from here afterwards, in one place instead of twenty.
 *
 * Two kinds of value live here, and the split is deliberately visible:
 *
 * - **Fixed** (`val x = Color(0x…)`) — the same colour in both themes.
 * - **Role-backed** (`@Composable val x get() = MaterialTheme.colorScheme.…`) — follows the
 *   user's Monet palette and flips with day / night on its own.
 *
 * Knowing which is which is half the reason to have this file.
 */
object AlphaColors {

    private val isDark: Boolean
        @Composable @ReadOnlyComposable get() = isSystemInDarkTheme()

    // ── Event palette ────────────────────────────────────────────────────────────────────
    // Shared hues, mapped to events by `eventStyleFor`. Named by colour because each one
    // serves several elements.
    //
    // Split per theme so the two sides tune independently. The pairs are identical today —
    // change one without touching the other whenever a hue needs it.
    //
    // ⚠️ RULE: a light-theme accent must never be **less vivid** than its dark counterpart.
    // Light surfaces wash colour out, so if a hue looks weak in day mode the fix is to push
    // the `…Light` value harder, never to soften it toward the background.

    val redDark = Color(0xFFEF5350)
    val redLight = Color(0xFFEF5350)

    val pinkDark = Color(0xFFEC407A)
    val pinkLight = Color(0xFFEC407A)

    val orangeDark = Color(0xFFFFA726)
    val orangeLight = Color(0xFFFFA726)

    val yellowDark = Color(0xFFFFCA28)
    val yellowLight = Color(0xFFFFCA28)

    val greenDark = Color(0xFF66BB6A)
    val greenLight = Color(0xFF66BB6A)

    val mintDark = Color(0xFF26A69A)
    val mintLight = Color(0xFF26A69A)

    val tealDark = Color(0xFF29B6F6)
    val tealLight = Color(0xFF29B6F6)

    val blueDark = Color(0xFF42A5F5)
    val blueLight = Color(0xFF42A5F5)

    val indigoDark = Color(0xFF7E57C2)
    val indigoLight = Color(0xFF7E57C2)

    val purpleDark = Color(0xFFAB47BC)
    val purpleLight = Color(0xFFAB47BC)

    val pausedGrayDark = Color(0xFF8E8E93)
    val pausedGrayLight = Color(0xFF8E8E93)

    // Resolved for the active theme — this is what call sites use.

    val red: Color @Composable @ReadOnlyComposable get() = if (isDark) redDark else redLight
    val pink: Color @Composable @ReadOnlyComposable get() = if (isDark) pinkDark else pinkLight
    val orange: Color
        @Composable @ReadOnlyComposable get() = if (isDark) orangeDark else orangeLight
    val yellow: Color
        @Composable @ReadOnlyComposable get() = if (isDark) yellowDark else yellowLight
    val green: Color @Composable @ReadOnlyComposable get() = if (isDark) greenDark else greenLight
    val mint: Color @Composable @ReadOnlyComposable get() = if (isDark) mintDark else mintLight
    val teal: Color @Composable @ReadOnlyComposable get() = if (isDark) tealDark else tealLight
    val blue: Color @Composable @ReadOnlyComposable get() = if (isDark) blueDark else blueLight
    val indigo: Color
        @Composable @ReadOnlyComposable get() = if (isDark) indigoDark else indigoLight
    val purple: Color
        @Composable @ReadOnlyComposable get() = if (isDark) purpleDark else purpleLight
    val pausedGray: Color
        @Composable @ReadOnlyComposable get() = if (isDark) pausedGrayDark else pausedGrayLight

    // ── Media ────────────────────────────────────────────────────────────────────────────

    /** Media chip accent when the session reports no colour of its own. */
    val mediaFallbackColor: Color @Composable @ReadOnlyComposable get() = purple

    /** Transport glyphs — play, skips, output — on a glass body. */
    val mediaButtonColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

    /**
     * Glyph on any **filled accent** — play buttons, action circles, selected plates.
     *
     * Near-white in both themes, by rule. Accent fills are authored (or darkened) to a range
     * where this always reads, so no contrast measurement is involved: measuring is what let a
     * glyph be picked against one colour and painted on another.
     *
     * Slightly off pure white so it does not glare on a saturated fill. One place to soften it
     * further if it ever does.
     */
    val onAccentColor = Color(0xFFFAFAFA)

    /** Transport glyphs on the QS card, where the wash is artwork rather than glass. */
    val mediaArtButtonColor = Color.White

    /** Carousel page dots on the QS media card. */
    val mediaPageDotColor = Color.White
    val mediaPageDotInactiveColor = Color.White.copy(alpha = 0.42f)

    // ── Chip and card bodies ─────────────────────────────────────────────────────────────
    // Opaque: these sit over status icons, notifications and wallpaper.

    /** Dynamic Bar chip / pill and expand-card body. Matches the lockscreen shortcut buttons. */
    val chipBodyColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** Nested box inside an expand card — steps a role rather than going translucent. */
    val chipBodyNestedColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest

    /** Expand-card shell on the island stack. */
    val cardBodyColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceBright

    /** Hairline between panes — an expand card can land on a notification card. */
    val chipRimColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaOpacity.rimAlpha)

    // ── Text ─────────────────────────────────────────────────────────────────────────────

    val chipTextColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

    val chipTextVariantColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    // ── Action buttons on expand cards ───────────────────────────────────────────────────

    val actionButtonColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

    val actionButtonTextColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary

    val destructiveButtonColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.errorContainer

    val destructiveButtonTextColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onErrorContainer

    // ── Battery ──────────────────────────────────────────────────────────────────────────

    val batteryChargingColor: Color @Composable @ReadOnlyComposable get() = green
    val batteryPowerSaveColor: Color @Composable @ReadOnlyComposable get() = orange

    val batteryIdleColor: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant

    /**
     * Charging ring on the expand card. Carries its own alpha in the literal (0xCC) and its own
     * hues — deliberately *not* [red] / [orange] / [green], and not split per theme yet. A wart
     * worth resolving when this file stops being a pure extraction.
     */
    val chargeRingLowColor = Color(0xCCF44336)
    val chargeRingMidColor = Color(0xCCFF9800)
    val chargeRingHighColor = Color(0xCC4CAF50)

    // ── Cutout, badge, torch ─────────────────────────────────────────────────────────────

    /** Cutout ring highlight — the event accent is lerped toward this. */
    val cutoutRingHighlightColor = Color.White

    /** Stack-count badge: body, rim and numeral. */
    val badgeBodyColor = Color.Black
    val badgeRimColor = Color.White

    /** Torch pill bulb. */
    val torchWindowColor = Color.Black
    val torchFilamentColor = Color.White

    // ── Lockscreen media card lift ───────────────────────────────────────────────────────

    val cardShadowAmbientColor = Color.Black.copy(alpha = 0.30f)
    val cardShadowSpotColor = Color.Black.copy(alpha = 0.42f)

    // ── Legibility floor ─────────────────────────────────────────────────────────────────
    // Used only when no palette role clears contrast on an arbitrary accent.

    val contrastFloorLightColor = Color.White
    val contrastFloorDarkColor = Color.Black

    /** Declared by the old island tokens and never referenced. Kept for the audit trail. */
    val unusedDarkChipTextColor = Color(0xFF1B1B1B)
}

/**
 * Opacity and blend amounts, named for what they act on.
 *
 * ⚠️ The `…PlateAlpha` group is the mechanism behind "vivid in dark, pale in light": call sites
 * paint `accent.copy(alpha = …)` over the body, so the *body* decides how the accent reads. Over
 * near-black an accent goes deep and saturated; over a light body the same alpha washes to
 * pastel. Values are unchanged here — the fix is an authored plate colour per accent per theme,
 * which is a design pass, not an extraction.
 */
object AlphaOpacity {

    // Text and glyph weights on a glass body.
    const val secondaryTextAlpha = 0.7f
    const val tertiaryTextAlpha = 0.5f
    const val hintTextAlpha = 0.4f
    const val disabledAlpha = 0.3f

    // Accent-over-body plates. See the warning above.
    const val subtlePlateAlpha = 0.15f
    const val faintPlateAlpha = 0.1f
    const val iconPlateAlpha = 0.16f
    const val statusChipPlateAlpha = 0.14f
    const val borderAlpha = 0.08f
    const val trackAlpha = 0.25f

    /** Hairline rim on a glass pane. */
    const val rimAlpha = 0.5f

    // Media button and text weights that differ by theme.
    const val mediaTextSecondaryAlphaDark = 0.7f
    const val mediaTextSecondaryAlphaLight = 0.62f
    const val mediaTextHintAlphaDark = 0.4f
    const val mediaTextHintAlphaLight = 0.42f
    const val mediaButtonPlateAlphaDark = 0.12f
    const val mediaButtonPlateAlphaLight = 0.08f
    const val chipRimAlphaDark = 0.16f
    const val chipRimAlphaLight = 0.12f

    /** Luminous rim on the lockscreen media card and its art thumbnail. */
    const val mediaCardRimAlphaDark = 0.38f
    const val mediaCardRimAlphaLight = 0.18f

    // Timeline.
    const val progressTipAlpha = 0.92f
    const val progressTrackAlpha = 0.22f
    const val progressTrackAlphaQs = 0.18f
    const val progressTrailMidAlpha = 0.35f

    /** Lockscreen media card, where blur / album art behind the pane is the point. */
    const val mediaCardFrostAlphaDark = 0x4D / 255f
    const val mediaCardFrostAlphaLight = 0.72f

    /** Lockscreen media card when the compositor refuses blur. */
    const val mediaCardFrostAlphaNoBlur = 0xD9 / 255f

    /** How hard a non-media event accent tints the chip body. */
    const val eventTintAmountDark = 0.45f
    const val eventTintAmountLight = 0.62f

    /** Blend of body toward accent for a secondary control plate. */
    const val buttonPlateBlendAmount = 0.55f
}

/** Non-colour chrome, kept beside the palette so tuning stays one file. */
object AlphaMetrics {
    val chipRimWidth = 1.dp
    val cardCornerRadius = 28.dp
    val progressHeight = 2.dp

    val mediaCardBlurRadius = 56.dp
    val mediaCardElevation = 14.dp
    val mediaArtSize = 88.dp
    val mediaArtCornerRadius = 20.dp

    /** Hue rotation for the Waveform accent sweep. */
    const val accentSweepDegrees = 62f

    /** Media accent is darkened by this factor for chip and expand-card fills. */
    const val mediaAccentDarkenKeep = 0.35f

    /** WCAG AA for icons and short labels at chip sizes. */
    const val minContentContrast = 4.5
}
