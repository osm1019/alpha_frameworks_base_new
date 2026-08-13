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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.qs.ax.ui.compose

import android.text.format.DateUtils
import androidx.annotation.DimenRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.media.ax.ui.compose.AxWaveform
import com.android.systemui.media.ax.ui.compose.MediaChrome
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.ax.ui.model.AxLockscreenMediaStyle
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Styles whose layout has landed. The rest fall back to [AxLockscreenMediaStyle.GLASS] — layout and
 * card height alike — so the tree stays shippable while the remaining styles are built.
 */
private val ImplementedStyles =
    setOf(
        AxLockscreenMediaStyle.GLASS,
        AxLockscreenMediaStyle.MINIMAL,
        AxLockscreenMediaStyle.WAVEFORM,
    )

/** The style actually rendered for [this] selection. */
internal val AxLockscreenMediaStyle.effective: AxLockscreenMediaStyle
    get() = if (this in ImplementedStyles) this else AxLockscreenMediaStyle.DEFAULT

/** Card height the keyguard host reserves for [this] style. */
@DimenRes
internal fun AxLockscreenMediaStyle.heightRes(): Int =
    when (this) {
        AxLockscreenMediaStyle.GLASS -> R.dimen.ax_lockscreen_media_height_glass
        AxLockscreenMediaStyle.MINIMAL -> R.dimen.ax_lockscreen_media_height_minimal
        AxLockscreenMediaStyle.WAVEFORM -> R.dimen.ax_lockscreen_media_height_waveform
    }

/**
 * Corner radius of the card body for [this] style. Also drives the blur drawable's corner, so the
 * frost stops exactly where the border does.
 */
internal fun AxLockscreenMediaStyle.cornerRadius(): Dp =
    when (this) {
        AxLockscreenMediaStyle.GLASS -> MediaChrome.LockscreenCornerRadius
        // Half of ax_lockscreen_media_height_minimal — a true pill.
        AxLockscreenMediaStyle.MINIMAL -> MinimalPillCorner
        AxLockscreenMediaStyle.WAVEFORM -> MediaChrome.LockscreenCornerRadius
    }

/** Picks the lockscreen card layout the user asked for. */
@Composable
internal fun LockscreenMediaContent(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    when (viewModel.lockscreenMediaStyle.effective) {
        AxLockscreenMediaStyle.GLASS ->
            GlassLockscreenMedia(
                session = session,
                title = title,
                subtitle = subtitle,
                viewModel = viewModel,
                colors = colors,
                interactive = interactive,
            )
        AxLockscreenMediaStyle.WAVEFORM ->
            WaveformLockscreenMedia(
                session = session,
                title = title,
                subtitle = subtitle,
                viewModel = viewModel,
                colors = colors,
                interactive = interactive,
            )
        AxLockscreenMediaStyle.MINIMAL ->
            MinimalLockscreenMedia(
                session = session,
                title = title,
                subtitle = subtitle,
                viewModel = viewModel,
                colors = colors,
                interactive = interactive,
            )
    }
}

/**
 * Glass card: art thumbnail beside the track text, a waveform badge and the output chip stacked at
 * the trailing edge, then a single-row neutral timeline and a centred transport.
 *
 * Controls and the seek bar are deliberately neutral — white play fill, bare skips
 * ([MediaChrome.ControlBare]), quiet white progress (no accent squiggle). The frosted body is
 * painted by [LockscreenGlassBackdrop] on the host card, not here.
 */
@Composable
private fun GlassLockscreenMedia(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    val playing = session?.state == MediaSessionState.Playing
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly
    val progress = session?.let(viewModel::progress) ?: 0f
    val durationMs = session?.durationMs ?: 0L
    val hasDuration = durationMs > 0L
    val elapsedLabel =
        if (hasDuration) DateUtils.formatElapsedTime((progress * durationMs).toLong() / 1000L)
        else ""
    val totalLabel = if (hasDuration) DateUtils.formatElapsedTime(durationMs / 1000L) else ""
    // Slots are filled from the session's own custom actions, which carry no semantic type — so the
    // action draws its own icon and the slot disappears when there is none.
    val trailingAction = session?.additionalActions?.firstOrNull()

    // Vertical budget targets the mockup ratio (shorter card): art row + single timeline +
    // transport. Elapsed/total share the seek row so we do not spend a second text row.
    Column(
        modifier =
            Modifier.fillMaxSize()
                // Bottom padding is deeper than the top: the transport is the visual base of the
                // card and needs room under the play button, as in the reference.
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(MediaChrome.LockscreenArtSize),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArtPane(
                session = session,
                size = MediaChrome.LockscreenArtSize,
                shape = RoundedCornerShape(MediaChrome.LockscreenArtCorner),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                AnimatedMediaText(
                    text = title,
                    color = colors.foreground,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                if (subtitle.isNotEmpty()) {
                    AnimatedMediaText(
                        text = subtitle,
                        color = MediaChrome.OnGlassSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // The badge owns the trailing edge, as in the reference; the output switcher moved to
            // the transport row so nothing competes with it up here.
            WaveformBadge(
                playing = playing,
                seed = session?.key?.hashCode() ?: 0,
                size = WaveformBadgeSize,
            )
        }

        Spacer(Modifier.height(4.dp))

        // Reference timeline: elapsed · bar · total on one row, neutral chrome (no accent squiggle).
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasDuration) {
                    Text(
                        text = elapsedLabel,
                        color = MediaChrome.OnGlassHint,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                LockscreenSeekBar(
                    session = session,
                    viewModel = viewModel,
                    interactive = interactive,
                    modifier = Modifier.weight(1f),
                )
                if (hasDuration) {
                    Text(
                        text = totalLabel,
                        color = MediaChrome.OnGlassHint,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(modifier = Modifier.fillMaxWidth().height(TransportRowHeight)) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showCoreActions) {
                        CoreMediaAction(
                            action = session?.leftAction,
                            imageVector = Icons.Filled.SkipPrevious,
                            descriptionRes = R.string.controls_media_button_prev,
                            viewModel = viewModel,
                            width = 44.dp,
                            iconSize = 28.dp,
                            tint = MediaChrome.ControlBare,
                            interactive = interactive,
                        )
                        CoreMediaAction(
                            action = session?.playPauseAction,
                            imageVector = playPauseIcon(session),
                            descriptionRes = playPauseDescription(session),
                            animatedIconRes = R.drawable.ic_media_play_button,
                            animatedIconAtEnd = playing,
                            viewModel = viewModel,
                            width = PlayButtonSize,
                            iconSize = 26.dp,
                            // The one accented control on an otherwise neutral card: fill and glyph
                            // both come from the artwork scheme, and it carries the same hairline as
                            // the card and the art thumbnail.
                            tint = colors.onPrimary,
                            background = colors.primary,
                            shape = CircleShape,
                            border =
                                BorderStroke(
                                    MediaChrome.LockscreenGlassBorderWidth,
                                    MediaChrome.LockscreenGlassBorder,
                                ),
                            interactive = interactive,
                        )
                        CoreMediaAction(
                            action = session?.rightAction,
                            imageVector = Icons.Filled.SkipNext,
                            descriptionRes = R.string.controls_media_button_next,
                            viewModel = viewModel,
                            width = 44.dp,
                            iconSize = 28.dp,
                            tint = MediaChrome.ControlBare,
                            interactive = interactive,
                        )
                    }
                }
                // Output switcher balances the custom action across the transport row. Drawn bare
                // and pure white like every other glyph here — a tinted pill made it the faintest
                // thing on the card.
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors =
                        colors.copy(
                            primary = Color.Transparent,
                            onPrimary = MediaChrome.ControlBare,
                        ),
                    interactive = interactive,
                    compact = false,
                    iconSize = 22.dp,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                if (trailingAction != null) {
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        MediaAction(
                            action = trailingAction,
                            viewModel = viewModel,
                            width = 40.dp,
                            iconSize = 22.dp,
                            tint = MediaChrome.OnGlassSecondary,
                            interactive = interactive,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimal pill: circular art filling the pill's height, track text with a segmented progress read-out
 * under it, and bare transport icons at the trailing edge.
 *
 * Deliberately spare — no output chip, no custom action, no timestamps, no scrubbing. The segments
 * are an indicator only: a 4dp row of dashes is not a touch target worth pretending about.
 */
@Composable
private fun MinimalLockscreenMedia(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    val playing = session?.state == MediaSessionState.Playing
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly
    val progress = session?.let(viewModel::progress) ?: 0f

    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtPane(
            session = session,
            size = MediaChrome.LockscreenArtSize,
            shape = CircleShape,
            borderWidth = MinimalArtRingWidth,
            borderColor = MediaChrome.LockscreenGlassBorder,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            AnimatedMediaText(
                text = title,
                color = colors.foreground,
                style = MaterialTheme.typography.titleMedium,
            )
            if (subtitle.isNotEmpty()) {
                AnimatedMediaText(
                    text = subtitle,
                    color = MediaChrome.OnGlassSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // No spacer: the touch box is taller than the dots and centres them, so the row sits
            // where the old 8dp spacer + 4dp canvas put it while being big enough to hit.
            SegmentedSeekBar(
                session = session,
                viewModel = viewModel,
                interactive = interactive,
                modifier = Modifier.fillMaxWidth().height(MinimalSeekHeight),
            )
        }
        if (showCoreActions) {
            Spacer(Modifier.width(8.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoreMediaAction(
                        action = session?.leftAction,
                        imageVector = Icons.Filled.SkipPrevious,
                        descriptionRes = R.string.controls_media_button_prev,
                        viewModel = viewModel,
                        width = 40.dp,
                        iconSize = 24.dp,
                        tint = MediaChrome.ControlBare,
                        interactive = interactive,
                    )
                    CoreMediaAction(
                        action = session?.playPauseAction,
                        imageVector = playPauseIcon(session),
                        descriptionRes = playPauseDescription(session),
                        animatedIconRes = R.drawable.ic_media_play_button,
                        animatedIconAtEnd = playing,
                        viewModel = viewModel,
                        width = 48.dp,
                        iconSize = 32.dp,
                        tint = MediaChrome.ControlBare,
                        interactive = interactive,
                    )
                    CoreMediaAction(
                        action = session?.rightAction,
                        imageVector = Icons.Filled.SkipNext,
                        descriptionRes = R.string.controls_media_button_next,
                        viewModel = viewModel,
                        width = 40.dp,
                        iconSize = 24.dp,
                        tint = MediaChrome.ControlBare,
                        interactive = interactive,
                    )
                }
            }
        }
    }
}

/**
 * Waveform card: art and track text on top, an accent band across the middle, a five-slot transport
 * and a full-width timeline under it.
 *
 * This is the one style where accent is loud — band, rim and played progress all come from the art.
 * The mockup is a landscape frame, so proportions are re-derived for a four-column card rather than
 * copied: art is 72dp, not the third of the width the reference shows.
 */
@Composable
private fun WaveformLockscreenMedia(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    val playing = session?.state == MediaSessionState.Playing
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly
    val progress = session?.let(viewModel::progress) ?: 0f
    val durationMs = session?.durationMs ?: 0L
    val hasDuration = durationMs > 0L
    val elapsedLabel =
        if (hasDuration) DateUtils.formatElapsedTime((progress * durationMs).toLong() / 1000L)
        else ""
    val totalLabel = if (hasDuration) DateUtils.formatElapsedTime(durationMs / 1000L) else ""
    // The reference's outer slots are shuffle and repeat, but custom actions carry no semantic type,
    // so whatever the app offers goes there with its own icon — and the slot vanishes if it has none.
    val extras = session?.additionalActions.orEmpty()
    val leadingExtra = extras.getOrNull(0)
    val trailingExtra = extras.getOrNull(1)
    val seed = session?.key?.hashCode() ?: 0
    val sweep = remember(colors.primary) { MediaChrome.accentSweep(colors.primary) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        // Art is a tall pane on the left; everything about the track — text, output, band — stacks
        // beside it, as in the reference.
        Row(
            modifier = Modifier.fillMaxWidth().height(WaveformArtSize),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArtPane(
                session = session,
                size = WaveformArtSize,
                shape = RoundedCornerShape(MediaChrome.LockscreenArtCorner),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        AnimatedMediaText(
                            text = title,
                            color = colors.foreground,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        if (subtitle.isNotEmpty()) {
                            AnimatedMediaText(
                                text = subtitle,
                                color = MediaChrome.OnGlassSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Where the reference puts a second small waveform glyph, we put the output
                    // switcher: a decorative duplicate of the band would earn nothing. Bare and
                    // white, at the weight of the transport glyphs.
                    MediaOutputChip(
                        session = session,
                        viewModel = viewModel,
                        colors =
                            colors.copy(
                                primary = Color.Transparent,
                                onPrimary = MediaChrome.ControlBare,
                            ),
                        interactive = interactive,
                        compact = false,
                        iconSize = 22.dp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AxWaveform(
                    playing = playing,
                    color = sweep,
                    // Derived from the width, so the band fills whatever the art leaves.
                    barCount = null,
                    barWidth = 2.dp,
                    barGap = 2.dp,
                    seed = seed,
                    modifier = Modifier.fillMaxWidth().height(WaveformBandHeight),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().height(WaveformTransportHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingExtra != null) {
                    MediaAction(
                        action = leadingExtra,
                        viewModel = viewModel,
                        width = 36.dp,
                        iconSize = 20.dp,
                        tint = MediaChrome.OnGlassSecondary,
                        interactive = interactive,
                    )
                }
                if (showCoreActions) {
                    CoreMediaAction(
                        action = session?.leftAction,
                        imageVector = Icons.Filled.SkipPrevious,
                        descriptionRes = R.string.controls_media_button_prev,
                        viewModel = viewModel,
                        width = 40.dp,
                        iconSize = 26.dp,
                        tint = MediaChrome.ControlBare,
                        interactive = interactive,
                    )
                    // Hollow play, ringed with the same sweep the band uses.
                    Box(
                        modifier =
                            Modifier.size(WaveformPlaySize)
                                .border(WaveformRingWidth, sweep, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        CoreMediaAction(
                            action = session?.playPauseAction,
                            imageVector = playPauseIcon(session),
                            descriptionRes = playPauseDescription(session),
                            animatedIconRes = R.drawable.ic_media_play_button,
                            animatedIconAtEnd = playing,
                            viewModel = viewModel,
                            width = WaveformPlaySize,
                            iconSize = 26.dp,
                            tint = MediaChrome.ControlBare,
                            shape = CircleShape,
                            interactive = interactive,
                        )
                    }
                    CoreMediaAction(
                        action = session?.rightAction,
                        imageVector = Icons.Filled.SkipNext,
                        descriptionRes = R.string.controls_media_button_next,
                        viewModel = viewModel,
                        width = 40.dp,
                        iconSize = 26.dp,
                        tint = MediaChrome.ControlBare,
                        interactive = interactive,
                    )
                }
                if (trailingExtra != null) {
                    MediaAction(
                        action = trailingExtra,
                        viewModel = viewModel,
                        width = 36.dp,
                        iconSize = 20.dp,
                        tint = MediaChrome.OnGlassSecondary,
                        interactive = interactive,
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasDuration) {
                    Text(
                        text = elapsedLabel,
                        color = MediaChrome.OnGlassSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                LockscreenSeekBar(
                    session = session,
                    viewModel = viewModel,
                    interactive = interactive,
                    // Accent fill rather than the Glass trail — the reference is a solid bar.
                    trail = { SolidColor(colors.primary) },
                    modifier = Modifier.weight(1f),
                )
                if (hasDuration) {
                    Text(
                        text = totalLabel,
                        color = MediaChrome.OnGlassSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Progress as a row of dashes, filled up to [progress]. Indicator only — never interactive. */
@Composable
private fun SegmentedProgress(progress: Float, modifier: Modifier = Modifier) {
    val filledColor = MediaChrome.OnGlass
    val emptyColor = MediaChrome.LockscreenProgressTrack
    Canvas(modifier) {
        val segment = MinimalSegmentWidth.toPx()
        val gap = MinimalSegmentGap.toPx()
        val count = floor((size.width + gap) / (segment + gap)).toInt().coerceIn(1, 64)
        val filled = (count * progress.coerceIn(0f, 1f)).roundToInt()
        val radius = CornerRadius(segment / 2f, segment / 2f)
        // The dots keep their own height inside a taller touch box, centred.
        val barHeight = MinimalSegmentHeight.toPx().coerceAtMost(size.height)
        val top = (size.height - barHeight) / 2f
        for (index in 0 until count) {
            drawRoundRect(
                color = if (index < filled) filledColor else emptyColor,
                topLeft = Offset(index * (segment + gap), top),
                size = Size(segment, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Minimal's timeline. Same scrubbing as [LockscreenSeekBar] — the dots were previously a bare
 * [SegmentedProgress] canvas with no gestures at all, so this was the one style you could not seek in.
 *
 * Segments are drawn flush to both edges, so the tap mapping takes no inset.
 */
@Composable
private fun SegmentedSeekBar(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = session?.let(viewModel::progress) ?: 0f
    val gestures = rememberScrubGestures(session, viewModel, interactive, inset = 0.dp)
    val description = seekBarDescription(session, progress)
    SegmentedProgress(
        progress = progress,
        modifier =
            modifier
                .then(gestures)
                .then(
                    if (description != null) {
                        Modifier.semantics { contentDescription = description }
                    } else {
                        Modifier
                    }
                ),
    )
}

/**
 * Tap-and-drag scrubbing, shared by every lockscreen timeline.
 *
 * [inset] is the horizontal margin the track is painted with, so the mapping matches the canvas at
 * both extremes — the thumb radius for a line, zero for a segment row that runs edge to edge.
 */
@Composable
private fun rememberScrubGestures(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    inset: Dp,
): Modifier {
    var dragged by remember(session?.key) { mutableStateOf(Offset.Zero) }
    if (!interactive || session == null || !session.canBeScrubbed) return Modifier
    return Modifier.pointerInput(session.key) {
            val insetPx = inset.toPx()
            fun progressAt(x: Float): Float {
                val usable = (size.width - insetPx * 2f).coerceAtLeast(1f)
                return ((x - insetPx) / usable).coerceIn(0f, 1f)
            }
            detectHorizontalDragGestures(
                onDragStart = { start ->
                    dragged = Offset.Zero
                    viewModel.onScrubChange(session, progressAt(start.x))
                },
                onHorizontalDrag = { change, delta ->
                    dragged += Offset(delta, 0f)
                    viewModel.onScrubChange(session, progressAt(change.position.x))
                },
                onDragEnd = { viewModel.onScrubFinished(session, dragged) },
                onDragCancel = { viewModel.onScrubFinished(session, Offset.Zero) },
            )
        }
        .pointerInput(session.key) {
            val insetPx = inset.toPx()
            detectTapGestures { tap ->
                val usable = (size.width - insetPx * 2f).coerceAtLeast(1f)
                viewModel.onScrubChange(session, ((tap.x - insetPx) / usable).coerceIn(0f, 1f))
                // A tap has no drag vector; hand the falsing check a horizontal one.
                viewModel.onScrubFinished(session, Offset(size.width.toFloat(), 0f))
            }
        }
}

/** Elapsed-of-total announcement, shared by the timelines. Null when the session has no duration. */
@Composable
private fun seekBarDescription(session: MediaSessionModel?, progress: Float): String? =
    if (session != null && session.durationMs > 0L) {
        stringResource(
            R.string.controls_media_seekbar_description,
            DateUtils.formatElapsedTime((progress * session.durationMs).toLong() / 1000L),
            DateUtils.formatElapsedTime(session.durationMs / 1000L),
        )
    } else {
        null
    }

/**
 * Timeline for the lockscreen styles: a quiet track, a played portion and a small round thumb. The
 * platform [MediaSeekBar] cannot draw either look — its progress drawable is a flat fill or the
 * squiggle — so the lockscreen owns its own bar.
 *
 * Glass fades the played portion in behind the thumb; Waveform passes a solid accent [trail].
 */
@Composable
private fun LockscreenSeekBar(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    trail: ((Float) -> Brush)? = null,
) {
    val progress = session?.let(viewModel::progress) ?: 0f
    val description = seekBarDescription(session, progress)
    // Capture theme colours here — Canvas draw scope is not @Composable.
    val trackColor = MediaChrome.LockscreenProgressTrack
    val thumbColor = MediaChrome.LockscreenProgressThumb
    val progressTip = MediaChrome.LockscreenProgress
    val resolvedTrail = trail ?: { endX -> MediaChrome.lockscreenProgressTrail(endX, progressTip) }
    // Inset by the thumb radius, the same margin the canvas paints with, so it tracks the finger at
    // both ends.
    val gestures = rememberScrubGestures(session, viewModel, interactive, inset = GlassSeekBarThumb)

    Box(
        modifier =
            modifier
                .height(GlassSeekBarHeight)
                .then(gestures)
                .then(
                    if (description != null) {
                        Modifier.semantics { contentDescription = description }
                    } else {
                        Modifier
                    }
                )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val centreY = size.height / 2f
            val stroke = GlassSeekBarTrack.toPx()
            val thumbRadius = GlassSeekBarThumb.toPx()
            // Keep the thumb fully inside the bar at either extreme.
            val usable = (size.width - thumbRadius * 2f).coerceAtLeast(0f)
            val playedX = thumbRadius + usable * progress.coerceIn(0f, 1f)
            drawLine(
                color = trackColor,
                start = Offset(thumbRadius, centreY),
                end = Offset(size.width - thumbRadius, centreY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (playedX > thumbRadius) {
                drawLine(
                    brush = resolvedTrail(playedX),
                    start = Offset(thumbRadius, centreY),
                    end = Offset(playedX, centreY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(playedX, centreY),
            )
        }
    }
}

/**
 * Album art, or the app's own icon while the session has none. Bordered like the card, so art and
 * body read as the same material — rounded square on Glass, a ringed circle on Minimal.
 */
@Composable
internal fun MediaArtPane(
    session: MediaSessionModel?,
    size: Dp,
    shape: Shape,
    borderWidth: Dp = MediaChrome.LockscreenGlassBorderWidth,
    borderColor: Color? = null,
) {
    val resolvedBorder = borderColor ?: MediaChrome.LockscreenGlassBorder
    Box(
        modifier =
            Modifier.size(size)
                .clip(shape)
                .background(MediaChrome.SkipNeutral)
                .border(borderWidth, resolvedBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = session?.background, label = "AxLockscreenMediaArt") { artwork ->
            when (artwork) {
                is IconModel.Loaded -> {
                    val bitmap = remember(artwork) { artwork.asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is IconModel.Resource ->
                    Icon(icon = artwork, tint = Color.Unspecified, modifier = Modifier.fillMaxSize())
                null ->
                    MediaAppIcon(
                        session = session,
                        size = size * 0.4f,
                        tint = MediaChrome.OnGlassHint,
                    )
            }
        }
    }
}

/** Decorative waveform: it follows playback state, not the audio (see [AxWaveform]). */
@Composable
private fun WaveformBadge(playing: Boolean, seed: Int, size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MediaChrome.SkipNeutral),
        contentAlignment = Alignment.Center,
    ) {
        AxWaveform(
            playing = playing,
            color = SolidColor(MediaChrome.OnGlass),
            // Count is derived from the width so the row always fits the inset. A fixed 13 bars did
            // not: 13 × 2dp + 12 × 2dp = 50dp of bars in a 37dp box, overflowing to the circle's
            // clip and leaving the badge looking edge to edge.
            barCount = null,
            barWidth = 2.dp,
            barGap = 2.dp,
            seed = seed,
            // Horizontal inset only — bar height still tracks the badge, so this narrows the
            // animation without flattening it.
            modifier =
                Modifier.size(width = size - WaveformBadgeInset * 2, height = size * 0.46f),
        )
    }
}

private val WaveformBadgeSize = 60.dp

/** Side padding inside the badge: 60 − 2 × 13 = 34dp of bars, i.e. 9 at the current width + gap. */
private val WaveformBadgeInset = 13.dp

private val PlayButtonSize = 56.dp
private val TransportRowHeight = 56.dp
private val GlassSeekBarHeight = 20.dp
private val GlassSeekBarTrack = 3.dp
private val GlassSeekBarThumb = 5.dp

// Waveform budget inside ax_lockscreen_media_height_waveform (204dp): 20 padding + 104 art row +
// 48 transport + 4 + 20 timeline = 196, leaving the weighted spacer ~8dp. The band lives inside the
// art row now, so the card is shorter than the first cut even with a bigger thumbnail.
private val WaveformArtSize = 104.dp
private val WaveformBandHeight = 40.dp
private val WaveformTransportHeight = 48.dp
private val WaveformPlaySize = 48.dp
private val WaveformRingWidth = 2.dp

/** Half of `ax_lockscreen_media_height_minimal` (104dp) — keep the two in step. */
private val MinimalPillCorner = 52.dp
private val MinimalArtRingWidth = 2.dp
private val MinimalSegmentWidth = 3.dp
private val MinimalSegmentGap = 3.dp
private val MinimalSegmentHeight = 4.dp

/** Touch box around the 4dp dots. Centres them where the old 8dp spacer + canvas put them. */
private val MinimalSeekHeight = 20.dp
