@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.res.R
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.media.ax.ui.compose.AxWaveform
import com.android.systemui.media.ax.ui.compose.MediaChrome
import com.android.systemui.media.ax.ui.compose.rememberAudioWaveformSource
import com.android.systemui.media.ax.ui.model.AxLockscreenMediaStyle
import kotlinx.coroutines.delay

/** Corner radius of the floating album art. */
private val KeyguardArtCorner = 24.dp
/** Art side as a fraction of the slot width. */
private const val KeyguardArtSlotFraction = 0.65f
private val KeyguardWaveformHeight = 40.dp
/** Glass timeline, matching surface B's seek bar so pill and card read as one material. */
private val KeyguardLinearHeight = 20.dp
private val KeyguardLinearTrack = 3.dp
private val KeyguardLinearThumb = 5.dp
private val KeyguardSegmentHeight = 20.dp
private val KeyguardSegmentDash = 4.dp
private val KeyguardSegmentGap = 3.dp
private val KeyguardPlaySize = 48.dp
private val KeyguardBareControlSize = 40.dp
private val KeyguardBareIconSize = 24.dp
/** Inset so transport / output icons are not clipped by the pill corners. */
private val KeyguardSheetIconInset = 12.dp
/** Side margin of the whole panel, wider than the island default so the card is not full-bleed. */
private val KeyguardPanelSideMargin = 36.dp

@Composable
internal fun KeyguardExpandedContent(
    event: IslandEvent,
    allEvents: List<IslandEvent>,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
    lockscreenMediaStyle: AxLockscreenMediaStyle = AxLockscreenMediaStyle.DEFAULT,
) {
    if (event is IslandEvent.AppSwitch) {
        LaunchedEffect(Unit) { onCollapse() }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCollapse() },
        contentAlignment = Alignment.Center,
    ) {
        when (event) {
            is IslandEvent.Media -> KeyguardMediaPanel(event, interactor, lockscreenMediaStyle)
            is IslandEvent.Timer -> KeyguardTimerPanel(event, interactor)
            is IslandEvent.Stopwatch -> KeyguardStopwatchPanel(event, interactor)
            is IslandEvent.AudioRecording -> KeyguardAudioRecordingPanel(event, interactor)
            else -> KeyguardGenericPanel(event, interactor, hapticsViewModelFactory)
        }
    }
}

@Composable
private fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = color.copy(alpha = AlphaFaint),
    strokeWidth: Dp = 10.dp,
    handleRadius: Dp = 8.dp,
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val handle = handleRadius.toPx()
        val pad = handle.coerceAtLeast(stroke / 2)
        val arcSize = Size(size.width - pad * 2, size.height - pad * 2)
        val topLeft = Offset(pad, pad)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (progress > 0f) {
            val sweep = 360f * progress
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            val angleRad = Math.toRadians((-90.0 + sweep)).toFloat()
            val cx = size.width / 2 + (arcSize.width / 2) * cos(angleRad)
            val cy = size.height / 2 + (arcSize.height / 2) * sin(angleRad)
            drawCircle(color = color, radius = handle, center = Offset(cx, cy))
        }
    }
}

@Composable
private fun KeyguardPanelSurface(content: @Composable () -> Unit) {
    val chrome = islandCardChrome()
    Box(
        modifier = Modifier
            .widthIn(max = ExpandedMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = SpaceSection)
            .clip(ShapeXl)
            .background(chrome.body)
            .border(1.dp, chrome.border, ShapeXl)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        content()
    }
}

@Composable
private fun TonalBanner(
    colors: IslandColorScheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShapeLg)
            .background(colors.tonal)
            .then(modifier)
            .padding(horizontal = SpaceXxl, vertical = SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        content()
    }
}

/**
 * Expanded keyguard media panel (surface E): floating art over a frosted sheet. Title/artist sit
 * in the sheet header (app | text | output); styles only change progress form and play treatment.
 *
 * Empty space on/around the card falls through to the outer collapse click — only real controls
 * consume taps (no full-sheet click eater).
 */
@Composable
private fun KeyguardMediaPanel(
    event: IslandEvent.Media,
    interactor: IslandActions,
    style: AxLockscreenMediaStyle,
) {
    KeyguardMediaCard(event, interactor, style)
}

/**
 * The expand card, for every style: floating art over a frosted control sheet. Title and artist
 * live in the sheet header (app | text | output) so nothing sits on open wallpaper between art and
 * card. Geometry is fixed — a style only picks the art silhouette, the rim, the progress form and
 * the play treatment. No sheet-wide click eater; empty sheet chrome and padding collapse the panel.
 */
@Composable
private fun KeyguardMediaCard(
    event: IslandEvent.Media,
    interactor: IslandActions,
    style: AxLockscreenMediaStyle,
) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent
    val onAccent = colors.onAccent
    val motionScheme = MaterialTheme.motionScheme
    val artShape = RoundedCornerShape(KeyguardArtCorner)
    val leadingExtra = event.customActions.firstOrNull()
    val trailingExtra = event.customActions.getOrNull(1)
    val seed = event.packageName.hashCode() xor event.track.hashCode()
    val waveformBrush: Brush = MediaChrome.accentSweep(accent)
    val sheetShape = ShapeCard
    // One rim for the sheet and the art, so the two panes read as the same material.
    val rimBrush: Brush =
        if (style == AxLockscreenMediaStyle.WAVEFORM) MediaChrome.accentSweep(accent)
        else SolidColor(AlphaColors.DbKeyguardCard.artRim)
    val trackText = event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) }

    Column(
        modifier =
            Modifier.widthIn(max = ExpandedMaxWidth)
                .fillMaxSize()
                .padding(horizontal = KeyguardPanelSideMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Art takes leftover height; the sheet below is fixed so this slot cannot breathe when
        // metadata updates on skip. Title/artist no longer sit between art and sheet.
        BoxWithConstraints(
            modifier = Modifier.weight(1f, fill = true).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val artSide = (maxWidth * KeyguardArtSlotFraction).coerceAtMost(maxHeight)
            AnimatedContent(
                targetState = event.albumArt,
                transitionSpec = {
                    // No SizeTransform: a default size anim feeds back into artSide via weight.
                    fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                        fadeOut(motionScheme.fastEffectsSpec()) using null
                },
                contentKey = { it?.hashCode() ?: 0 },
                label = "kg_media_album_art",
                contentAlignment = Alignment.Center,
            ) { art ->
                Box(
                    modifier =
                        Modifier.size(artSide)
                            .clip(artShape)
                            .border(AlphaColors.DbKeyguardCard.artRimWidth, rimBrush, artShape)
                            .background(AlphaColors.DbKeyguardCard.buttonPlate),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        Image(
                            bitmap = art.toCoverBitmap(artSide),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(artShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            null,
                            tint = accent.copy(alpha = AlphaDisabled),
                            modifier = Modifier.size(SpacePanelLarge),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(SpaceXxl))

        // Frosted sheet — no click eater. Only controls below consume taps; empty chrome collapses.
        val sheetBlurred = rememberChipBlurEnabled()
        val sheetBody =
            AlphaColors.DbKeyguardCard.body.copy(
                alpha =
                    if (sheetBlurred) AlphaColors.DbKeyguardCard.bodyAlpha
                    else AlphaColors.DbKeyguardCard.bodyAlphaNoBlur,
            )
        Box {
            if (sheetBlurred) {
                ChipGlassBackdrop(corner = 28.dp, modifier = Modifier.matchParentSize())
            }
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(sheetShape)
                    .background(sheetBody)
                    .border(AlphaColors.DbKeyguardCard.artRimWidth, rimBrush, sheetShape)
                    .padding(
                        horizontal = SpaceXxl + KeyguardSheetIconInset,
                        vertical = SpaceMd,
                    ),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: app | title+artist | output. Text is inside the glass, not on wallpaper.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyguardBareControl(
                    onClick = {
                        interactor.openMediaApp()
                        interactor.collapseIsland()
                    },
                ) {
                    val appIcon = event.appIcon
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon.toScaledBitmap(KeyguardBareIconSize),
                            contentDescription = null,
                            modifier = Modifier.size(KeyguardBareIconSize),
                            colorFilter = ColorFilter.tint(AlphaColors.DbKeyguardCard.skipGlyph),
                        )
                    } else {
                        Icon(
                            Icons.Filled.MusicNote,
                            null,
                            tint = AlphaColors.DbKeyguardCard.skipGlyph,
                            modifier = Modifier.size(KeyguardBareIconSize),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = SpaceSm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedContent(
                        targetState = trackText,
                        transitionSpec = {
                            fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                                fadeOut(motionScheme.fastEffectsSpec()) using null
                        },
                        label = "kg_media_track",
                        contentAlignment = Alignment.Center,
                    ) { title ->
                        Text(
                            title,
                            color = AlphaColors.DbKeyguardCard.text,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            minLines = 1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Always laid out so skip metadata blips do not resize the sheet.
                    AnimatedContent(
                        targetState = event.artist,
                        transitionSpec = {
                            fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                                fadeOut(motionScheme.fastEffectsSpec()) using null
                        },
                        label = "kg_media_artist",
                        contentAlignment = Alignment.Center,
                    ) { artist ->
                        Text(
                            artist,
                            color = AlphaColors.DbKeyguardCard.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            minLines = 1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                KeyguardBareControl(
                    onClick = {
                        interactor.openMediaOutputSwitcher()
                        interactor.collapseIsland()
                    },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_ax_media_output),
                        stringResource(R.string.ax_dynamic_bar_output),
                        tint = AlphaColors.DbKeyguardCard.skipGlyph,
                        modifier = Modifier.size(KeyguardBareIconSize),
                    )
                }
            }

            Spacer(Modifier.height(SpaceMd))

            // Always reserve the progress slot. Duration often blips to 0 on skip while metadata
            // reloads; gating on duration>0 is what made the sheet shrink then grow.
            when (style) {
                AxLockscreenMediaStyle.MINIMAL ->
                    KeyguardMediaSegmentedProgress(event, interactor)
                AxLockscreenMediaStyle.GLASS ->
                    KeyguardMediaLinearProgress(event, interactor)
                AxLockscreenMediaStyle.WAVEFORM ->
                    KeyguardMediaWaveformProgress(
                        event = event,
                        interactor = interactor,
                        playing = event.isPlaying,
                        waveformBrush = waveformBrush,
                        seed = seed,
                    )
            }
            Spacer(Modifier.height(SpaceMd))

            // Centred, not distributed: the transport holds its place whatever the edges carry.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeyguardBareControl(onClick = { interactor.skipPrev() }) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            stringResource(R.string.ax_dynamic_bar_previous),
                            tint = AlphaColors.DbKeyguardCard.skipGlyph,
                            modifier = Modifier.size(KeyguardBareIconSize),
                        )
                    }

                    KeyguardPlayButton(
                        style = style,
                        accent = accent,
                        isPlaying = event.isPlaying,
                        onClick = { interactor.togglePlayPause() },
                    )

                    KeyguardBareControl(onClick = { interactor.skipNext() }) {
                        Icon(
                            Icons.Filled.SkipNext,
                            stringResource(R.string.ax_dynamic_bar_next),
                            tint = AlphaColors.DbKeyguardCard.skipGlyph,
                            modifier = Modifier.size(KeyguardBareIconSize),
                        )
                    }
                }

                if (leadingExtra != null) {
                    KeyguardBareControl(
                        onClick = { interactor.sendCustomAction(leadingExtra.action) },
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        CustomActionIcon(
                            leadingExtra,
                            tint = AlphaColors.DbKeyguardCard.skipGlyph,
                            modifier = Modifier.size(KeyguardBareIconSize),
                        )
                    }
                }

                if (trailingExtra != null) {
                    KeyguardBareControl(
                        onClick = { interactor.sendCustomAction(trailingExtra.action) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        CustomActionIcon(
                            trailingExtra,
                            tint = AlphaColors.DbKeyguardCard.skipGlyph,
                            modifier = Modifier.size(KeyguardBareIconSize),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun KeyguardBareControl(
    onClick: () -> Unit,
    size: Dp = KeyguardBareControlSize,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun KeyguardPlayButton(
    style: AxLockscreenMediaStyle,
    accent: Color,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val motionScheme = MaterialTheme.motionScheme
    val icon: @Composable () -> Unit = {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                    fadeOut(motionScheme.fastEffectsSpec()) using null
            },
            label = "kg_media_playpause",
            contentAlignment = Alignment.Center,
        ) { playing ->
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) {
                    stringResource(R.string.ax_dynamic_bar_pause)
                } else {
                    stringResource(R.string.ax_dynamic_bar_play)
                },
                tint =
                    when (style) {
                        // Bare glyphs sit on the sheet, so they take the sheet's content colour.
                        AxLockscreenMediaStyle.MINIMAL -> AlphaColors.DbKeyguardCard.skipGlyph
                        AxLockscreenMediaStyle.WAVEFORM -> AlphaColors.DbKeyguardCard.skipGlyph
                        // Glass fills the button, so the glyph follows the filled-accent rule.
                        AxLockscreenMediaStyle.GLASS -> AlphaColors.DbKeyguardCard.playGlyph
                    },
                modifier = Modifier.size(26.dp),
            )
        }
    }

    when (style) {
        AxLockscreenMediaStyle.MINIMAL -> {
            // Bare play — no fill, no ring.
            Box(
                modifier =
                    Modifier.size(KeyguardPlaySize)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        AxLockscreenMediaStyle.WAVEFORM -> {
            // Hollow circle with accent-sweep ring.
            val sweep = remember(accent) { MediaChrome.accentSweep(accent) }
            Box(
                modifier =
                    Modifier.size(KeyguardPlaySize)
                        .border(2.dp, sweep, CircleShape)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        AxLockscreenMediaStyle.GLASS -> {
            // Filled accent circle + glass hairline — the one accented control on the sheet.
            // Normalised rather than borrowed from the pill: the pill's colour is a *tint* for a
            // body it gets measured against afterwards, and darkening a pastel to make one turns
            // it to slate. A fill has to hold its hue against the sheet on its own.
            Surface(
                onClick = onClick,
                modifier = Modifier.size(KeyguardPlaySize),
                shape = CircleShape,
                color = MediaChrome.accentFill(accent),
                border =
                    BorderStroke(
                        AlphaColors.DbKeyguardCard.artRimWidth,
                        AlphaColors.DbKeyguardCard.artRim,
                    ),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    icon()
                }
            }
        }
    }
}

/**
 * Position and scrub machinery for every expand-panel progress form.
 *
 * [content] only paints the track for the fraction it is handed; the box it draws into already
 * holds the dismiss-swipe lock for the duration of a touch (without it [MagneticSwipeToDismiss]
 * eats the drag) and carries tap and horizontal-drag seeking. The 16 ms ticker interpolates between
 * position updates, and pauses while a finger owns the bar.
 */
@Composable
private fun KeyguardMediaScrubber(
    event: IslandEvent.Media,
    interactor: IslandActions,
    height: Dp,
    showTimes: Boolean = true,
    inset: Dp = 0.dp,
    content: @Composable BoxScope.(fraction: FloatState, scrubbing: Boolean) -> Unit,
) {
    val mediaProgress = rememberMediaProgress(event)
    val isPlaying = event.isPlaying
    val durationMs = event.duration
    val positionMs = mediaProgress.positionMs
    val serverFraction = mediaProgress.progress

    var isScrubbing by remember { mutableStateOf(false) }
    // Nothing in this scope may read it, or the ticker recomposes the bar every frame.
    val displayFraction = remember { mutableFloatStateOf(serverFraction) }
    val interactorRef = rememberUpdatedState(interactor)
    val swipeLock = LocalDismissSwipeLock.current

    LaunchedEffect(positionMs, durationMs, isPlaying) {
        if (isScrubbing) return@LaunchedEffect
        displayFraction.floatValue = serverFraction
        if (!isPlaying || durationMs <= 0L) return@LaunchedEffect
        val startWallMs = System.currentTimeMillis()
        val startProgressMs = positionMs
        while (true) {
            delay(16L)
            if (isScrubbing) break
            val elapsed = System.currentTimeMillis() - startWallMs
            val interpolated = ((startProgressMs + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
            displayFraction.floatValue = interpolated
            if (interpolated >= 1f) break
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(height)
                    .pointerInput(swipeLock) {
                        awaitEachGesture {
                            awaitPointerEvent()
                            swipeLock.value = true
                            try {
                                do {
                                    val e = awaitPointerEvent()
                                } while (e.changes.any { it.pressed })
                            } finally {
                                swipeLock.value = false
                            }
                        }
                    }
                    .pointerInput("tap", inset) {
                        detectTapGestures { offset ->
                            val f = fractionAt(offset.x, size.width, inset.toPx())
                            displayFraction.floatValue = f
                            interactorRef.value.seekTo((f * durationMs).toLong())
                        }
                    }
                    .pointerInput("drag", inset) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                isScrubbing = true
                                displayFraction.floatValue =
                                    fractionAt(offset.x, size.width, inset.toPx())
                            },
                            onDragEnd = {
                                interactorRef.value.seekTo(
                                    (displayFraction.floatValue * durationMs).toLong()
                                )
                                isScrubbing = false
                            },
                            onDragCancel = { isScrubbing = false },
                            onHorizontalDrag = { change, _ ->
                                displayFraction.floatValue =
                                    fractionAt(change.position.x, size.width, inset.toPx())
                                change.consume()
                            },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            content(displayFraction, isScrubbing)
        }
        if (showTimes) {
            Spacer(Modifier.height(SpaceXs))
            ScrubTimes(displayFraction, durationMs)
        }
    }
}

/**
 * Where [x] falls along a track the canvas paints with [insetPx] of margin at each end, so the
 * finger and the drawing agree at both extremes.
 */
private fun fractionAt(x: Float, width: Int, insetPx: Float): Float {
    val usable = (width - insetPx * 2f).coerceAtLeast(1f)
    return ((x - insetPx) / usable).coerceIn(0f, 1f)
}

/**
 * Elapsed and total, in a scope of its own so the ticker invalidates two [Text]s per frame.
 */
@Composable
private fun ScrubTimes(fraction: FloatState, durationMs: Long) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            formatElapsedTime((fraction.floatValue * durationMs).toLong()),
            color = AlphaColors.DbKeyguardCard.textHint,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            formatElapsedTime(durationMs),
            color = AlphaColors.DbKeyguardCard.textHint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun KeyguardMediaWaveformProgress(
    event: IslandEvent.Media,
    interactor: IslandActions,
    playing: Boolean,
    waveformBrush: Brush,
    seed: Int,
) {
    KeyguardMediaScrubber(event, interactor, KeyguardWaveformHeight) { _, scrubbing ->
        // No vertical playhead — the mockup is a pure waveform; times carry position.
        AxWaveform(
            playing = playing && !scrubbing,
            color = waveformBrush,
            barCount = null,
            barWidth = 1.5.dp,
            barGap = 1.5.dp,
            seed = seed,
            source = rememberAudioWaveformSource(playing && !scrubbing),
            modifier = Modifier.fillMaxWidth().height(KeyguardWaveformHeight),
        )
    }
}

/**
 * Glass progress: quiet track, played portion fading in behind a round thumb. Shares the tokens and
 * metrics of `LockscreenSeekBar` so this and the lockscreen media card read as one material.
 */
@Composable
private fun KeyguardMediaLinearProgress(event: IslandEvent.Media, interactor: IslandActions) {
    val trackColor = AlphaColors.DbKeyguardCard.progressTrack
    val thumbColor = AlphaColors.DbKeyguardCard.progressThumb
    val progressTip = AlphaColors.DbKeyguardCard.progressTrail
    KeyguardMediaScrubber(
        event = event,
        interactor = interactor,
        height = KeyguardLinearHeight,
        inset = KeyguardLinearThumb,
    ) { fraction, _ ->
        Canvas(Modifier.fillMaxSize()) {
            val centreY = size.height / 2f
            val stroke = KeyguardLinearTrack.toPx()
            val thumbRadius = KeyguardLinearThumb.toPx()
            val usable = (size.width - thumbRadius * 2f).coerceAtLeast(0f)
            val playedX = thumbRadius + usable * fraction.floatValue.coerceIn(0f, 1f)
            drawLine(
                color = trackColor,
                start = Offset(thumbRadius, centreY),
                end = Offset(size.width - thumbRadius, centreY),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            if (playedX > thumbRadius) {
                drawLine(
                    brush = MediaChrome.lockscreenProgressTrail(playedX, progressTip),
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
 * Minimal expand progress: row of neutral dashes (B Minimal language), scrubbable with the same
 * dismiss-swipe lock as the waveform path. [showTimes] is off on the 104dp card (no room for a
 * second time row under the dots).
 */
@Composable
private fun KeyguardMediaSegmentedProgress(
    event: IslandEvent.Media,
    interactor: IslandActions,
    showTimes: Boolean = true,
) {
    val filledColor = AlphaColors.DbKeyguardCard.text
    val emptyColor = AlphaColors.DbKeyguardCard.progressTrack
    KeyguardMediaScrubber(event, interactor, KeyguardSegmentHeight, showTimes) { fraction, _ ->
        Canvas(modifier = Modifier.fillMaxWidth().height(KeyguardSegmentHeight)) {
            val segment = KeyguardSegmentDash.toPx()
            val gap = KeyguardSegmentGap.toPx()
            val count = floor((size.width + gap) / (segment + gap)).toInt().coerceIn(1, 64)
            val filled = (count * fraction.floatValue.coerceIn(0f, 1f)).roundToInt()
            val radius = CornerRadius(segment / 2f, segment / 2f)
            val barHeight = (segment * 0.85f).coerceAtMost(size.height)
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
}

@Composable
private fun KeyguardTimerPanel(event: IslandEvent.Timer, interactor: IslandActions) {
    val colors = rememberIslandColors(event)
    var remainingMs by remember(event.endTimeMs) {
        mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }
    if (!event.isPaused) {
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
    }

    val elapsedFraction = if (event.originalDurationMs > 0L)
        (remainingMs.toFloat() / event.originalDurationMs).coerceIn(0f, 1f)
    else 0f

    val pulseTransition = rememberInfiniteTransition(label = "timer_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f, targetValue = if (remainingMs < 10_000L) 1.06f else 1f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "timer_pulse_scale",
    )

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(
            colors,
            modifier = event.contentIntent?.let { intent ->
                Modifier.clickable { interactor.launchDismissingKeyguard(intent) }
            } ?: Modifier,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                eventStyleFor(event).icon?.let { Icon(it, null, tint = AlphaColors.DbKeyguardCard.playGlyph, modifier = Modifier.size(18.dp)) }
            }
            Text(
                event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(SizeAlbumLg)
                .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale },
        ) {
            ProgressRing(
                progress = elapsedFraction,
                color = colors.accent,
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                if (event.isPaused) stringResource(R.string.ax_dynamic_bar_paused) else formatCountdownLong(remainingMs),
                color = AlphaColors.DbKeyguardCard.text,
                style = MaterialTheme.typography.displayMedium,
            )
        }

        KeyguardEventActions(event.actions, colors) {
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_dismiss),
                icon = Icons.Filled.Close,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

@Composable
private fun KeyguardStopwatchPanel(event: IslandEvent.Stopwatch, interactor: IslandActions) {
    val colors = rememberIslandColors(event)
    var elapsedMs by remember(event.startTimeMs) {
        mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
    }
    if (event.isRunning) {
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
    }

    val secFraction = if (event.isRunning) (elapsedMs % 60000) / 60000f else 0f

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TonalBanner(
            colors,
            modifier = event.contentIntent?.let { intent ->
                Modifier.clickable { interactor.launchDismissingKeyguard(intent) }
            } ?: Modifier,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AvTimer, null, tint = AlphaColors.DbKeyguardCard.playGlyph, modifier = Modifier.size(18.dp))
            }
            Text(
                event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_stopwatch) }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // The ring stays centred whether or not a lap is showing, so nothing shifts when the
        // count appears on the first lap or leaves on pause.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(SizeAlbumLg)) {
                ProgressRing(
                    progress = secFraction,
                    color = colors.accent,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    if (event.isRunning) formatStopwatch(elapsedMs) else stringResource(R.string.ax_dynamic_bar_paused),
                    color = AlphaColors.DbKeyguardCard.text,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            event.lapNumber?.let { LapCount(it, Modifier.align(Alignment.CenterEnd)) }
        }

        KeyguardEventActions(event.actions, colors) {
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_dismiss),
                icon = Icons.Filled.Close,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

@Composable
private fun KeyguardAudioRecordingPanel(event: IslandEvent.AudioRecording, interactor: IslandActions) {
    val colors = rememberIslandColors(event)
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(event.startTimeMs, event.state, event.pausedDurationMs) {
        if (event.state == RecordingState.RECORDING) {
            while (true) {
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                    .coerceAtLeast(0L)
                delay(1000)
            }
        } else {
            elapsedMs = (System.currentTimeMillis() - event.startTimeMs - event.pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }

    val isRecording = event.state == RecordingState.RECORDING

    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The banner is the card's only non-button surface that reads as tappable, so it carries
        // the notification's own tap target — the recorder decides where that lands, not us.
        TonalBanner(
            colors,
            modifier = event.contentIntent?.let { intent ->
                Modifier.clickable { interactor.launchDismissingKeyguard(intent) }
            } ?: Modifier,
        ) {
            if (isRecording) PulsingDot(color = colors.accent, size = SpaceMd)
            Icon(Icons.Filled.Mic, null, tint = colors.accent, modifier = Modifier.size(SizeIconSm))
            Text(
                when (event.state) {
                    RecordingState.RECORDING -> stringResource(R.string.ax_dynamic_bar_recording)
                    RecordingState.PAUSED -> stringResource(R.string.ax_dynamic_bar_paused)
                    RecordingState.SAVED -> stringResource(R.string.ax_dynamic_bar_saved)
                }.uppercase(),
                color = colors.accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Text(
            formatElapsedTime(elapsedMs),
            color = AlphaColors.DbKeyguardCard.text,
            style = MaterialTheme.typography.displayLarge,
        )

        if (isRecording) {
            LinearWavyProgressIndicator(
                modifier = Modifier.fillMaxWidth().clip(ShapeChip),
                color = colors.accent,
                trackColor = colors.accent.copy(alpha = AlphaFaint),
            )
        } else {
            LinearWavyProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth().clip(ShapeChip),
                color = colors.accent.copy(alpha = AlphaDisabled),
                trackColor = colors.accent.copy(alpha = AlphaFaint),
            )
        }

        // Pause and Stop only. Once stopped the recorder posts Play / Share / Delete, and not one
        // of them finishes on this card — delete wants a confirmation, share opens a chooser, play
        // needs a player. Stop takes the event with it, so that state is never reached from here.
        KeyguardEventActions(
            actions = event.actions,
            colors = colors,
            allowed = RecorderActions,
            afterSend = { kind -> if (kind == NotificationActionType.STOP) interactor.dismissEvent(event) },
        ) {
            ExpressivePillButton(
                label = stringResource(R.string.ax_dynamic_bar_dismiss),
                icon = Icons.Filled.Close,
                contentColor = colors.accent,
                backgroundColor = colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = { interactor.dismissEvent(event) },
            )
        }
    }
}
}

/**
 * The app's own actions, for the panels whose event is a clock or a recorder notification.
 *
 * These cards used to draw a fixed pair: `actions.first()` labelled Pause/Resume from the event's
 * own state, and a second button that only ever called `dismissEvent`. Both halves were wrong.
 * `Notification.Action` order is the app's business — AOSP's clock posts **+1 min** second on a
 * running timer and **Lap** second on a running stopwatch, and the recorder posts the real **Stop**
 * there — so an index is not a meaning, and a button that says Stop while calling `dismissEvent`
 * leaves a recorder recording.
 *
 * So: [classify] decides the icon, the app's own label decides the text, and the action's own
 * intent is what fires. Nothing is invented. A running timer or stopwatch has no terminal action to
 * offer — `Reset` only appears once paused — and this draws no substitute for one; the scrim
 * collapses the card.
 */
@Composable
private fun KeyguardEventActions(
    actions: List<IslandEvent.NotificationAction>,
    colors: IslandColorScheme,
    allowed: Set<NotificationActionType>? = null,
    afterSend: (NotificationActionType) -> Unit = {},
    onEmpty: (@Composable RowScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val classified =
        actions.map { notifAction ->
            val pkg = notifAction.action.actionIntent?.creatorPackage ?: context.packageName
            notifAction to notifAction.action.classify(context, pkg)
        }
        .filter { (_, kind) -> allowed == null || kind in allowed }
        .take(MaxKeyguardActions)

    if (classified.isEmpty()) {
        onEmpty?.let { Row(modifier = Modifier.fillMaxWidth()) { it() } }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        classified.forEachIndexed { index, (notifAction, kind) ->
            // The app's first action is its primary one on every notification we render here
            // (Pause / Resume), so it keeps the filled plate the panels already used.
            val filled = index == 0
            ExpressivePillButton(
                label = notifAction.label.toString(),
                icon = keyguardActionIcon(kind),
                contentColor = if (filled) colors.onAccent else colors.accent,
                backgroundColor = if (filled) colors.accent else colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = {
                    try {
                        notifAction.action.actionIntent?.sendWithBal(context)
                    } catch (_: Exception) {}
                    afterSend(kind)
                },
            )
        }
    }
}

/**
 * The lap the stopwatch is on. A bare cardinal — it only ever appears after the user has pressed
 * Lap, so there is nobody to explain it to, and AOSP's own notification is no wordier.
 *
 * One kick when the number changes, not a loop: the point is confirming the tap landed, which a
 * constant pulse cannot say, and a permanently animating element on this blurred surface is what
 * the motion sweep removed everywhere else.
 */
@Composable
private fun LapCount(lap: Int, modifier: Modifier = Modifier) {
    val motionScheme = MaterialTheme.motionScheme
    val scale = remember { Animatable(1f) }
    var previous by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(lap) {
        if (previous != null && previous != lap) {
            scale.snapTo(LapKickScale)
            scale.animateTo(1f, motionScheme.fastSpatialSpec())
        }
        previous = lap
    }
    Text(
        lap.toString(),
        color = RedAccent,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}

private const val LapKickScale = 1.35f

private const val MaxKeyguardActions = 3

private val RecorderActions =
    setOf(NotificationActionType.PAUSE, NotificationActionType.RESUME, NotificationActionType.STOP)

private fun keyguardActionIcon(kind: NotificationActionType): ImageVector? =
    when (kind) {
        NotificationActionType.PAUSE -> Icons.Filled.Pause
        NotificationActionType.RESUME -> Icons.Filled.PlayArrow
        NotificationActionType.STOP -> Icons.Filled.Stop
        NotificationActionType.DELETE -> Icons.Filled.Delete
        NotificationActionType.RESET -> Icons.Filled.RestartAlt
        NotificationActionType.LAP -> Icons.Filled.Flag
        NotificationActionType.ADD_MINUTE -> Icons.Filled.Add
        NotificationActionType.SNOOZE -> Icons.Filled.Snooze
        NotificationActionType.DISMISS -> Icons.Filled.Close
        NotificationActionType.OTHER -> null
    }

/**
 * The battery card, opened from the lane's battery occupant.
 *
 * Its own entry point rather than a panel of [KeyguardExpandedContent]: `IslandEvent.Charging` is
 * filtered on the keyguard so there is never a second charging display, which leaves the occupant
 * with no stack event to pin. The [event] is read straight off `ChargingEventSource`.
 *
 * The body is [ChargingExpanded] unchanged — that layout already states charge type, level, time
 * remaining and the power / current / voltage / temperature block, and [KeyguardPanelSurface] is
 * the same `DbStackCard` surface its `OnCard*` colours were measured against. Only the actions are
 * new; the stack card has none. No battery saver among them: the card only ever opens on a charging
 * battery, so the toggle would have no context to act in.
 *
 * [onDismiss] drops the lane occupant, not the card — the card closing is a side effect, the same
 * way it is for every other dismiss on this surface. [onCollapse] is the scrim.
 */
@Composable
internal fun KeyguardBatteryPanel(
    event: IslandEvent.Charging,
    interactor: IslandActions,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCollapse() },
        contentAlignment = Alignment.Center,
    ) {
        KeyguardPanelSurface {
            Column(
                modifier = Modifier.fillMaxWidth().padding(SpaceSection),
                verticalArrangement = Arrangement.spacedBy(SpaceXxl),
            ) {
                ChargingExpanded(event, interactor)

                Row(horizontalArrangement = Arrangement.spacedBy(SpaceMd)) {
                    ExpressivePillButton(
                        label = stringResource(R.string.ax_dynamic_bar_battery_usage),
                        icon = Icons.Filled.BarChart,
                        contentColor = BlueAccent,
                        backgroundColor = BlueAccent.copy(alpha = AlphaFaint),
                        modifier = Modifier.weight(1f),
                        onClick = { interactor.openBatteryStats() },
                    )
                    ExpressivePillButton(
                        label = stringResource(R.string.ax_dynamic_bar_dismiss),
                        icon = Icons.Filled.Close,
                        contentColor = RedAccent,
                        backgroundColor = RedAccent.copy(alpha = AlphaFaint),
                        modifier = Modifier.weight(1f),
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyguardGenericPanel(
    event: IslandEvent,
    interactor: IslandActions,
    hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) {
    val colors = rememberIslandColors(event)
    KeyguardPanelSurface { Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpaceSection),
        verticalArrangement = Arrangement.spacedBy(SpaceXxl),
    ) {
        ExpandedEventContent(event, interactor, hapticsViewModelFactory)

        ExpressivePillButton(
            label = stringResource(R.string.ax_dynamic_bar_dismiss),
            contentColor = colors.onAccent,
            backgroundColor = colors.accent,
            modifier = Modifier.fillMaxWidth(),
            onClick = { interactor.dismissEvent(event) },
        )
    }
}
}
