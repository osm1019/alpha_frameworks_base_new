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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.android.systemui.qs.ax.ui.model.AxLockscreenMediaStyle
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

/**
 * Styles whose layout has landed. The rest fall back to [AxLockscreenMediaStyle.GLASS] — layout and
 * card height alike — so the tree stays shippable while the remaining styles are built.
 */
private val ImplementedStyles = setOf(AxLockscreenMediaStyle.GLASS)

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
        AxLockscreenMediaStyle.GLASS,
        // Land in M2 / M3; until then every selection renders Glass (see [ImplementedStyles]).
        AxLockscreenMediaStyle.MINIMAL,
        AxLockscreenMediaStyle.WAVEFORM ->
            GlassLockscreenMedia(
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
            MediaArtThumbnail(session = session, size = MediaChrome.LockscreenArtSize)
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
                GlassSeekBar(
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
                            tint = MediaChrome.OnPlayNeutral,
                            background = MediaChrome.PlayNeutralFill,
                            shape = CircleShape,
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
                // Output switcher balances the custom action across the transport row, in neutral
                // chrome so it reads as chrome rather than as the card's accent.
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors =
                        colors.copy(
                            primary = MediaChrome.SkipNeutral,
                            onPrimary = MediaChrome.OnGlassSecondary,
                        ),
                    interactive = interactive,
                    showLabel = false,
                    compact = true,
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
 * Timeline for the Glass style: a quiet track, a played portion that fades in behind the thumb, and
 * a small round thumb. The platform [MediaSeekBar] cannot draw the trail — its progress drawable is
 * a flat fill or the squiggle — so this style owns its own bar.
 */
@Composable
private fun GlassSeekBar(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = session?.let(viewModel::progress) ?: 0f
    val scrubbable = interactive && session != null && session.canBeScrubbed
    val description =
        if (session != null && session.durationMs > 0L) {
            stringResource(
                R.string.controls_media_seekbar_description,
                DateUtils.formatElapsedTime((progress * session.durationMs).toLong() / 1000L),
                DateUtils.formatElapsedTime(session.durationMs / 1000L),
            )
        } else {
            null
        }
    var dragged by remember(session?.key) { mutableStateOf(Offset.Zero) }
    val gestures =
        if (scrubbable && session != null) {
            Modifier.pointerInput(session.key) {
                    // Same mapping the canvas uses, so the thumb tracks the finger at both ends.
                    fun progressAt(x: Float): Float {
                        val inset = GlassSeekBarThumb.toPx()
                        val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                        return ((x - inset) / usable).coerceIn(0f, 1f)
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
                    detectTapGestures { tap ->
                        val inset = GlassSeekBarThumb.toPx()
                        val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                        viewModel.onScrubChange(session, ((tap.x - inset) / usable).coerceIn(0f, 1f))
                        // A tap has no drag vector; hand the falsing check a horizontal one.
                        viewModel.onScrubFinished(session, Offset(size.width.toFloat(), 0f))
                    }
                }
        } else {
            Modifier
        }

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
                color = MediaChrome.LockscreenProgressTrack,
                start = Offset(thumbRadius, centreY),
                end = Offset(size.width - thumbRadius, centreY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (playedX > thumbRadius) {
                drawLine(
                    brush = MediaChrome.lockscreenProgressTrail(playedX),
                    start = Offset(thumbRadius, centreY),
                    end = Offset(playedX, centreY),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = MediaChrome.LockscreenProgressThumb,
                radius = thumbRadius,
                center = Offset(playedX, centreY),
            )
        }
    }
}

/** Album art, or the app's own icon while the session has none. */
@Composable
private fun MediaArtThumbnail(session: MediaSessionModel?, size: Dp) {
    val shape = RoundedCornerShape(MediaChrome.LockscreenArtCorner)
    Box(
        modifier =
            Modifier.size(size)
                .clip(shape)
                .background(MediaChrome.SkipNeutral)
                // Same hairline as the card: the art reads as a second pane of the same glass.
                .border(MediaChrome.LockscreenGlassBorderWidth, MediaChrome.LockscreenGlassBorder, shape),
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
            barCount = 13,
            barWidth = 2.dp,
            barGap = 2.dp,
            seed = seed,
            modifier = Modifier.size(width = size * 0.62f, height = size * 0.46f),
        )
    }
}

private val WaveformBadgeSize = 60.dp
private val PlayButtonSize = 56.dp
private val TransportRowHeight = 56.dp
private val GlassSeekBarHeight = 20.dp
private val GlassSeekBarTrack = 3.dp
private val GlassSeekBarThumb = 5.dp
