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

import androidx.annotation.ColorRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.internal.R

private val isDarkTheme: Boolean
    @Composable @ReadOnlyComposable get() = isSystemInDarkTheme()

private val onSurface: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

private val surfaceContainerHigh: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * A Monet role taken from the **dark** palette whatever the system theme is.
 *
 * `MaterialTheme` only ever exposes the scheme currently in force, so a surface that wants to stay
 * in its night form has to read the `*_dark` platform resources directly. These are the same
 * resources `dynamicDarkColorScheme` is built from, so the user's palette still applies.
 */
@Composable
@ReadOnlyComposable
private fun nightRole(@ColorRes id: Int): Color = Color(LocalContext.current.getColor(id))

/**
 * Every Alpha surface, and every element it paints, as one map.
 *
 * ```
 * AlphaColors.DbLockscreenPill.textSecondary   // the object, then the element
 * ```
 *
 * **The problem this exists to solve.** The first cut was organised by *kind of colour* — one
 * `OnGlass`, one `LockscreenProgress`, one `SkipNeutral` — and every surface drank from the same
 * handful. `LockscreenProgress` alone was read by five objects, so "make the QS timeline quieter"
 * silently moved the lockscreen pill, both keyguard cards and the Dynamic Bar stack with it.
 * Tuning meant grepping for consumers, checking none of them minded, and inventing an exception
 * when one did.
 *
 * Here an element belongs to exactly one object. Changing [QsMediaCard.progressTrack] changes the
 * QS timeline and nothing else, by construction — no consumer search, no special cases. Want to
 * tune a colour: open this file, find the object, find the element.
 *
 * **The six objects** are the surfaces a user can point at:
 *
 * | Object | Where it is |
 * |---|---|
 * | [DbStatusBarChip] | the chip in the status bar, and the same chip around the cutout |
 * | [DbLockscreenPill] | the pill above the lockscreen shortcut row |
 * | [DbKeyguardCard] | what the pill expands into on the lockscreen |
 * | [LockscreenMediaCard] | the standalone lockscreen media card (Glass / Minimal / Waveform) |
 * | [QsMediaCard] | the media card in Quick Settings, every span |
 * | [DbStackCard] | the cards in the expanded Dynamic Bar stack |
 *
 * `Db` marks the four that belong to the Dynamic Bar, so it is obvious at the call site which
 * surfaces move together when the Dynamic Bar's look changes and which two are their own thing.
 *
 * **Two kinds of value**, and the split is deliberately visible:
 *
 * - **Role-backed** (`MaterialTheme.colorScheme.…`) — follows the user's Monet palette and flips
 *   with day / night on its own.
 * - **Fixed** (`Color(0x…)`) — the same in both themes, on purpose. Say why in a comment.
 *
 * Where an element differs by theme it says so inline (`if (isDarkTheme) … else …`) rather than
 * pointing at an alpha table elsewhere in the file: one lookup has to be enough.
 *
 * Shared crayons — the event palette, [onAccentColor], the contrast floors — sit above the objects
 * because they genuinely are one thing (the charging green *is* the battery green). An object that
 * wants to break away declares its own element instead.
 *
 * Nothing else is flat. If you find yourself wanting a value that several objects would share,
 * that is the old mistake asking to be repeated — give each object its own and let them drift.
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

    // ── Legibility floor ─────────────────────────────────────────────────────────────────
    // Used only when no palette role clears contrast on an arbitrary accent.

    val contrastFloorLightColor = Color.White
    val contrastFloorDarkColor = Color.Black

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Status bar chip (and the cutout chip — same object, two mounts)
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The chip that floats in the status bar, and the identical one drawn around the cutout.
     *
     * ⚠️ **Night-locked: day renders exactly as night.** This is the one surface with no
     * neighbours to match and a backdrop that does not follow the theme — it only shows while
     * Quick Settings is closed, so what sits behind it is wallpaper. Following the theme bought
     * nothing and cost the chip its stability: text flipped dark on a theme change while the
     * wallpaper under it did not move.
     *
     * So [body], [text] and [textInverse] read the `*_dark` Monet roles in both modes, and
     * [tintAmount] is the night value. The user's palette still applies — these are the same
     * resources the dark scheme is built from — but day and night resolve identically.
     *
     * Every event carries its hue: [body] is the base the event accent tints, not the final colour.
     */
    object DbStatusBarChip {

        /** Base the event accent is mixed into. The result, not this, is what you see. */
        val body: Color
            @Composable @ReadOnlyComposable
            get() = nightRole(R.color.system_surface_container_high_dark)

        /** How far [body] travels toward the event accent. 0 = untinted, 1 = solid accent. */
        val tintAmount = 0.45f

        val rim: Color @Composable @ReadOnlyComposable get() = text.copy(alpha = 0.16f)

        val rimWidth = 1.dp

        /**
         * Text and glyphs. Picked by measuring against the tinted [body] — see `contentColorOn` — so
         * these two are candidates, not the answer: whichever contrasts better wins, and if neither
         * clears [AlphaMetrics.minContentContrast] the chip falls back to the shared floors.
         */
        val text: Color
            @Composable @ReadOnlyComposable get() = nightRole(R.color.system_on_surface_dark)
        val textInverse: Color
            @Composable @ReadOnlyComposable get() = nightRole(R.color.system_inverse_on_surface_dark)

        val textSecondaryAlpha = 0.7f
        val textHintAlpha = 0.5f

        /** Progress hairline along the bottom edge: both ends blend accent toward [text]. */
        val progressTrackBlend = 0.2f
        val progressFillBlend = 0.6f
        val progressHeight = 2.dp

        /** Stack-count badge: body is accent blended toward [text], numeral is [text]. */
        val badgeBodyBlend = 0.3f

        /**
         * The badge the chip carries when several events are queued, and the cutout ring's
         * highlight. Fixed black-on-white: both sit over the wallpaper, where a themed pair would
         * disappear against half of them.
         */
        val badgeBody = Color.Black
        val badgeRim = Color.White
        val ringHighlight = Color.White

        /**
         * Lightness the album colour is normalised to before it tints [body]. Media used to opt out of
         * the colour code here; it does not any more.
         *
         * Not the same number as a *fill* takes ([AlphaMetrics.mediaAccentFillLightnessDark]) — a fill has a hard
         * ceiling because [AlphaColors.onAccentColor] must read on it, a tint has none because the text is
         * measured against the mixed result afterwards. Set to where the event palette sits.
         */
        const val accentTintLightness = 0.55f
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Lockscreen pill
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The pill above the lockscreen shortcut row.
     *
     * It sits *between* the two keyguard shortcut buttons, so [body] deliberately matches what
     * `KeyguardQuickAffordanceViewBinder` gives them — the bottom row has to read as one band. That is
     * also why media alone skips the event tint here ([mediaBody]): a coloured media pill between two
     * neutral circles broke the row.
     */
    object DbLockscreenPill {

        /** Base the event accent tints, for every event except media. */
        val body: Color @Composable @ReadOnlyComposable get() = surfaceContainerHigh

        /** Media takes the body untinted — see the class note. */
        val mediaBody: Color @Composable @ReadOnlyComposable get() = surfaceContainerHigh

        val tintAmount: Float @Composable @ReadOnlyComposable get() = if (isDarkTheme) 0.45f else 0.62f

        /** Hairline. Media uses the neutral rim, tinted events the brighter one. */
        val mediaRim: Color
            @Composable @ReadOnlyComposable
            get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        val rim: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.16f else 0.12f)

        val rimWidth = 1.dp

        val text: Color @Composable @ReadOnlyComposable get() = onSurface

        /** Second candidate when the tinted body is too light for [text] — see `contentColorOn`. */
        val textInverse: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.inverseOnSurface

        val textSecondary: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.7f else 0.62f)
        val textHint: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.4f else 0.42f)

        /** Album thumbnail: plate behind it while it loads, and the hairline around it. */
        val artPlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)
        val artRim: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.38f else 0.18f)

        /** Transport. Play is the only filled control; skips are bare. */
        val playGlyph: Color @Composable @ReadOnlyComposable get() = AlphaColors.onAccentColor
        val skipGlyph: Color @Composable @ReadOnlyComposable get() = onSurface
        val buttonPlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)

        /** Timeline along the bottom edge. */
        val progressTrack: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.22f)
        val progressFill: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.92f)
        val progressHeight = 2.dp

        /** Waveform is the one style that lets the album colour into the timeline. */
        val waveformProgressTrack: Color
            @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.18f)

        val badgeBodyBlend = 0.3f
        val badgeText: Color @Composable @ReadOnlyComposable get() = onSurface

        /** Battery pill — the variant shown when no event is on the pill. */
        val batteryCharging: Color @Composable @ReadOnlyComposable get() = AlphaColors.green
        val batteryPowerSave: Color @Composable @ReadOnlyComposable get() = AlphaColors.orange
        val batteryIdle: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Keyguard expand card — what the pill opens into
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The card the lockscreen pill expands into. Not media-only — it hosts whatever event the pill
     * was showing (media, timer, stopwatch, audio recording), which is why the transport elements
     * below sit beside plain text and action buttons.
     *
     * Opaque: it lands on top of notification cards.
     */
    object DbKeyguardCard {

        val body: Color @Composable @ReadOnlyComposable get() = surfaceContainerHigh

        /** A box nested inside the card steps a role rather than going translucent. */
        val nestedBody: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest

        val rim: Color
            @Composable @ReadOnlyComposable
            get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        val rimWidth = 1.dp

        val text: Color @Composable @ReadOnlyComposable get() = onSurface
        val textSecondary: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.7f else 0.62f)
        val textHint: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.4f else 0.42f)

        val artPlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)
        val artRim: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.38f else 0.18f)
        val artRimWidth = 1.dp

        val playGlyph: Color @Composable @ReadOnlyComposable get() = AlphaColors.onAccentColor
        val skipGlyph: Color @Composable @ReadOnlyComposable get() = onSurface
        val buttonPlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)

        val progressTrack: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.22f)
        val progressTrail: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.92f)
        val progressThumb: Color @Composable @ReadOnlyComposable get() = onSurface

        /** Buttons that end up on the card from a notification action. */
        val actionButton: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
        val actionButtonText: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Lockscreen media card — the standalone one, with the three styles
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The standalone lockscreen media card (Glass / Minimal / Waveform).
     *
     * The only object here whose body is **open**: blur or album art behind the pane is the whole
     * point, so [frostBody] carries alpha and lets it through. Day needs a denser pane than night —
     * what sits behind is wallpaper and cover art, and neither follows the theme, so a light seed at
     * night's density loses to a dark wallpaper.
     */
    object LockscreenMediaCard {

        val frostBody: Color
            @Composable @ReadOnlyComposable
            get() = surfaceContainerHigh.copy(alpha = if (isDarkTheme) 0x4D / 255f else 0.72f)

        /** When the compositor refuses blur, an open pane leaves content on bare wallpaper. */
        val frostBodyNoBlur: Color
            @Composable @ReadOnlyComposable get() = surfaceContainerHigh.copy(alpha = 0xD9 / 255f)

        val rim: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.38f else 0.18f)
        val rimWidth = 1.dp

        val text: Color @Composable @ReadOnlyComposable get() = onSurface
        val textSecondary: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.7f else 0.62f)
        val textHint: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.4f else 0.42f)

        val artPlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)
        val artRim: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.38f else 0.18f)

        val playGlyph: Color @Composable @ReadOnlyComposable get() = AlphaColors.onAccentColor
        val skipGlyph: Color @Composable @ReadOnlyComposable get() = onSurface
        val badgePlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)

        val progressTrack: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.22f)
        val progressTrail: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.92f)
        val progressThumb: Color @Composable @ReadOnlyComposable get() = onSurface

        /** Lift. The card is the only thing here that casts a shadow. */
        val shadowAmbient = Color.Black.copy(alpha = 0.30f)
        val shadowSpot = Color.Black.copy(alpha = 0.42f)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // QS media card
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The Quick Settings media card, at every span.
     *
     * A **tile**, not glass: [body] is what the tiles beside it use, so the card never looks like a
     * visitor. Album art is a thumbnail inside it rather than a wash behind it, which is what keeps
     * these colours ours instead of the cover's.
     */
    object QsMediaCard {

        /** Matches the inactive tiles around it. `surfaceEffect1` when blur is on. */
        val body: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceBright

        val text: Color @Composable @ReadOnlyComposable get() = onSurface
        val textSecondary: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.7f else 0.62f)
        val textHint: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.4f else 0.42f)

        val artPlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)
        val artRim: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.38f else 0.18f)

        /** Play is the card's one accent; everything else is content-coloured. */
        val playGlyph: Color @Composable @ReadOnlyComposable get() = AlphaColors.onAccentColor
        val skipGlyph: Color @Composable @ReadOnlyComposable get() = onSurface
        val outputGlyph: Color @Composable @ReadOnlyComposable get() = onSurface

        val progressTrack: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.18f)
        val progressFill: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.92f)
        val progressThumb: Color @Composable @ReadOnlyComposable get() = onSurface

        /** Carousel dots. Fixed: they sit over album art on the lockscreen mount. */
        val pageDot = Color.White
        val pageDotInactive = Color.White.copy(alpha = 0.42f)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Dynamic Bar stack cards
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The cards in the expanded Dynamic Bar stack — media, charging, timer, notification, and the
     * rest. One object, because they are one stack and a card that theme-drifted from its neighbours
     * would be the bug.
     */
    object DbStackCard {

        val body: Color @Composable @ReadOnlyComposable get() = surfaceContainerHigh
        val nestedBody: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainerHighest

        /** An expand card can land on a notification card, which resolves to a neighbouring role. */
        val rim: Color
            @Composable @ReadOnlyComposable
            get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        val rimWidth = 1.dp
        val cornerRadius = 28.dp

        val text: Color @Composable @ReadOnlyComposable get() = onSurface
        val textSecondary: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.7f else 0.62f)
        val textHint: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.4f else 0.42f)
        val textVariant: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

        val artPlate: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.12f else 0.08f)
        val artGlyph: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.4f else 0.42f)

        val playGlyph: Color @Composable @ReadOnlyComposable get() = AlphaColors.onAccentColor
        val skipGlyph: Color @Composable @ReadOnlyComposable get() = onSurface
        val extraGlyph: Color
            @Composable @ReadOnlyComposable
            get() = onSurface.copy(alpha = if (isDarkTheme) 0.7f else 0.62f)
        val disabledGlyphAlpha = 0.3f

        val progressTrack: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.22f)
        val progressTrail: Color @Composable @ReadOnlyComposable get() = onSurface.copy(alpha = 0.92f)
        val progressThumb: Color @Composable @ReadOnlyComposable get() = onSurface

        val actionButton: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
        val actionButtonText: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onPrimary
        val destructiveButton: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.errorContainer
        val destructiveButtonText: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onErrorContainer

        val batteryCharging: Color @Composable @ReadOnlyComposable get() = AlphaColors.green
        val batteryPowerSave: Color @Composable @ReadOnlyComposable get() = AlphaColors.orange
        val batteryIdle: Color
            @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant

        /**
         * Charging ring. Its own hues and its own baked-in alpha (0xCC) — deliberately *not*
         * [red] / [orange] / [green], and not split per theme. A
         * wart inherited from the extraction; now at least it is a wart in one named place.
         */
        val chargeRingLow = Color(0xCCF44336)
        val chargeRingMid = Color(0xCCFF9800)
        val chargeRingHigh = Color(0xCC4CAF50)

        /** Torch card bulb. Fixed: a bulb that followed the theme would stop reading as a bulb. */
        val torchWindow = Color.Black
        val torchFilament = Color.White
    }
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

    /** Timeline geometry, shared by every card that draws our own bar rather than a platform one. */
    val mediaTimelineHeight = 20.dp
    val mediaTimelineTrackWidth = 3.dp
    val mediaTimelineThumbRadius = 5.dp

    /** Hue rotation for the Waveform accent sweep. */
    const val accentSweepDegrees = 62f

    /** Media accent is darkened by this factor for chip and expand-card fills. */
    const val mediaAccentDarkenKeep = 0.35f

    /**
     * Lightness a filled media control is painted at, whatever the artwork handed us.
     *
     * The media scheme's accent is `primaryFixed` — a tone-90 pastel *by construction*, the same
     * in both themes. A pastel fill has nowhere to sit: [AlphaColors.onAccentColor] disappears on
     * it, and on a light card the fill itself disappears into the body. So hue and chroma stay the
     * artwork's and lightness becomes ours.
     *
     * Split per theme, identical today — same rule as the event palette: the light value may only
     * ever move toward *more* vivid.
     */
    const val mediaAccentFillLightnessDark = 0.46f
    const val mediaAccentFillLightnessLight = 0.46f


    /** Chroma floor, so a grey cover still yields a coloured accent rather than a slab of concrete. */
    const val mediaAccentFillSaturationFloor = 0.55f

    /** WCAG AA for icons and short labels at chip sizes. */
    const val minContentContrast = 4.5
}
