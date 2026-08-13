package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R
import kotlinx.coroutines.delay
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.alpha.theme.AlphaMetrics
import com.android.systemui.media.ax.ui.compose.MediaChrome

// Compact stack card — keep controls usable but shave vertical bulk vs full-sheet media.
private val AlbumArtSize = 56.dp
private val PlayPauseSize = 44.dp
private val ControlButtonSize = 36.dp
private val ControlIconSize = 20.dp

/**
 * Stack card. Reads as one pane, not a header over a tinted tray: the media chip and the expand
 * shell are both neutral glass, so a tinted band under the transport was the only tinted thing in
 * an otherwise neutral stack — and it split the card in two.
 *
 * Track text, timeline and transport follow the lockscreen styles (neutral chrome, one accented
 * control) while the shell, shape and padding stay whatever the rest of the stack uses.
 */
@Composable
internal fun MediaCard(event: IslandEvent.Media, interactor: IslandActions) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent
    val chrome = islandCardChrome()

    Surface(
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, chrome.border, ShapeCard)
            .pointerInput(Unit) { detectTapGestures {} },
        shape = ShapeCard,
        color = chrome.body,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceXxl, vertical = SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        interactor.openMediaApp()
                        interactor.collapseIsland()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                MediaArtThumbnail(event, AlbumArtSize)

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpaceXxs),
                ) {
                    Text(
                        event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) },
                        color = OnCardText,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (event.artist.isNotEmpty()) {
                        Text(
                            event.artist,
                            color = SubtleGray,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                event.appIcon?.let { icon ->
                    Image(
                        bitmap = icon.toScaledBitmap(SizeIconSm),
                        contentDescription = null,
                        modifier = Modifier.size(SizeIconSm).clip(ShapeXs),
                        colorFilter = ColorFilter.tint(OnCardText),
                    )
                }
            }

            if (event.duration > 0L) {
                MediaTimeline(event, interactor)
            }
            MediaControls(event, interactor, accent)
        }
    }
}

/** Album art, or a neutral plate carrying the note glyph — the same pair [MediaArtPane] draws. */
@Composable
private fun MediaArtThumbnail(event: IslandEvent.Media, size: Dp) {
    val art = event.albumArt
    if (art != null) {
        Image(
            bitmap = art.toScaledBitmap(size),
            contentDescription = null,
            modifier = Modifier.size(size).clip(ShapeLg),
            contentScale = ContentScale.Crop,
        )
        return
    }
    Box(
        modifier = Modifier.size(size).clip(ShapeLg).background(MediaChrome.SkipNeutral),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            null,
            tint = MediaChrome.OnGlassHint,
            modifier = Modifier.size(size / 2),
        )
    }
}

@Composable
internal fun MediaExpanded(
    event: IslandEvent.Media,
    interactor: IslandActions,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMediaColors(event)
    val accent = colors.accent

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SpaceXxl)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable {
                interactor.openMediaApp()
                interactor.collapseIsland()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXxl),
        ) {
            MediaArtThumbnail(event, SizeAlbumSm)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXs),
            ) {
                Text(
                    event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_now_playing) },
                    color = OnCardText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.artist.isNotEmpty()) {
                    Text(
                        event.artist,
                        color = SubtleGray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            event.appIcon?.let { icon ->
                Image(
                    bitmap = icon.toScaledBitmap(SizeIconSm),
                    contentDescription = null,
                    modifier = Modifier.size(SizeIconSm).clip(ShapeXs),
                    colorFilter = ColorFilter.tint(OnCardText),
                )
            }
        }

        MediaControls(event, interactor, accent)
        if (event.duration > 0L) {
            MediaTimeline(event, interactor)
        }
    }
}

/**
 * Transport, in the lockscreen grammar: everything bare except play, which is the card's one
 * accented control. The plates the skips used to carry were `accent.copy(alpha = 0.15f)` — over a
 * light body that is a pastel wash with a pastel glyph on it.
 */
@Composable
private fun MediaControls(
    event: IslandEvent.Media,
    interactor: IslandActions,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaCustomActionButton(event, interactor)

        BareControlButton(onClick = { interactor.skipPrev() }) {
            Icon(
                Icons.Filled.SkipPrevious, null,
                tint = OnCardText,
                modifier = Modifier.size(ControlIconSize),
            )
        }

        Surface(
            onClick = { interactor.togglePlayPause() },
            shape = CircleShape,
            color = MediaChrome.accentFill(accent),
            modifier = Modifier.size(PlayPauseSize),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (event.isPlaying)
                        stringResource(R.string.ax_dynamic_bar_pause)
                    else
                        stringResource(R.string.ax_dynamic_bar_play),
                    tint = AlphaColors.onAccentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        BareControlButton(onClick = { interactor.skipNext() }) {
            Icon(
                Icons.Filled.SkipNext, null,
                tint = OnCardText,
                modifier = Modifier.size(ControlIconSize),
            )
        }

        MediaEndActionButton(event, interactor)
    }
}

/** Secondary transport slot: hit target and ripple, no plate. */
@Composable
private fun BareControlButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        enabled = enabled,
        modifier = Modifier.size(ControlButtonSize),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/**
 * The stack card's timeline — elapsed, bar, total on one row, drawn with the same track / trail /
 * thumb the lockscreen styles use. It replaces an accent-tinted platform `SeekBar` whose squiggle
 * matched no style we ship and whose colour came from the artwork, so on a light card the played
 * portion read as unfilled.
 *
 * Gestures were already Compose (the old `SeekBar` was `isEnabled = false` and painted only), so
 * only the renderer changed.
 */
@Composable
private fun MediaTimeline(
    event: IslandEvent.Media,
    interactor: IslandActions,
) {
    val mediaProgress = rememberMediaProgress(event)
    val isPlaying = event.isPlaying
    val durationMs = event.duration
    val positionMs = mediaProgress.positionMs
    val serverFraction = mediaProgress.progress

    var isScrubbing by remember { mutableStateOf(false) }
    var displayFraction by remember { mutableStateOf(serverFraction) }

    val interactorRef = rememberUpdatedState(interactor)

    // Read the dismiss swipe lock provided by MagneticSwipeToDismiss
    val swipeLock = LocalDismissSwipeLock.current

    // Smooth frame-interpolated progress when playing, snaps when paused or scrubbing
    LaunchedEffect(positionMs, durationMs, isPlaying) {
        if (isScrubbing) return@LaunchedEffect

        displayFraction = serverFraction

        if (!isPlaying || durationMs <= 0L) return@LaunchedEffect

        val startWallMs = System.currentTimeMillis()
        val startProgressMs = positionMs
        while (true) {
            delay(16L) // ~60 fps
            if (isScrubbing) break
            val elapsed = System.currentTimeMillis() - startWallMs
            val interpolated = ((startProgressMs + elapsed).toFloat() / durationMs).coerceIn(0f, 1f)
            displayFraction = interpolated
            if (interpolated >= 1f) break
        }
    }

    val displayMs = (displayFraction * durationMs).toLong()
    // Captured in composition — the Canvas draw scope is not @Composable.
    val trackColor = MediaChrome.LockscreenProgressTrack
    val thumbColor = MediaChrome.LockscreenProgressThumb
    val progressTip = MediaChrome.LockscreenProgress
    val thumbRadiusDp = AlphaMetrics.mediaTimelineThumbRadius
    val trackWidthDp = AlphaMetrics.mediaTimelineTrackWidth

    Row(
        modifier = Modifier.fillMaxWidth().height(AlphaMetrics.mediaTimelineHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatElapsedTime(displayMs),
            color = MediaChrome.OnGlassHint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(end = SpaceMd),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(swipeLock) {
                    awaitEachGesture {
                        awaitPointerEvent() // DOWN
                        swipeLock.value = true
                        try {
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                        } finally {
                            swipeLock.value = false
                        }
                    }
                }
                .pointerInput("tap") {
                    detectTapGestures { offset ->
                        val fraction = progressAt(offset.x, size.width, thumbRadiusDp.toPx())
                        displayFraction = fraction
                        interactorRef.value.seekTo((fraction * durationMs).toLong())
                    }
                }
                .pointerInput("drag") {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isScrubbing = true
                            displayFraction =
                                progressAt(offset.x, size.width, thumbRadiusDp.toPx())
                        },
                        onDragEnd = {
                            interactorRef.value.seekTo((displayFraction * durationMs).toLong())
                            isScrubbing = false
                        },
                        onDragCancel = { isScrubbing = false },
                        onHorizontalDrag = { change, _ ->
                            displayFraction =
                                progressAt(change.position.x, size.width, thumbRadiusDp.toPx())
                            change.consume()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centreY = size.height / 2f
                val stroke = trackWidthDp.toPx()
                val thumbRadius = thumbRadiusDp.toPx()
                // Keep the thumb fully inside the bar at either extreme — the same inset the
                // gesture mapping takes, so it tracks the finger all the way to both ends.
                val usable = (size.width - thumbRadius * 2f).coerceAtLeast(0f)
                val playedX = thumbRadius + usable * displayFraction.coerceIn(0f, 1f)
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
                drawCircle(color = thumbColor, radius = thumbRadius, center = Offset(playedX, centreY))
            }
        }

        Text(
            formatElapsedTime(durationMs),
            color = MediaChrome.OnGlassHint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(start = SpaceMd),
        )
    }
}

/** Tap / drag x to progress, inset by the thumb radius so the mapping matches what is painted. */
private fun progressAt(x: Float, width: Int, inset: Float): Float {
    val usable = (width - inset * 2f).coerceAtLeast(1f)
    return ((x - inset) / usable).coerceIn(0f, 1f)
}

@Composable
private fun MediaCustomActionButton(event: IslandEvent.Media, interactor: IslandActions) {
    val ca = event.customActions.firstOrNull()
    if (ca != null) {
        BareControlButton(onClick = { interactor.sendCustomAction(ca.action) }) {
            CustomActionIcon(ca, tint = SubtleGray, modifier = Modifier.size(ControlIconSize))
        }
    } else {
        BareControlButton(onClick = { }, enabled = false) {
            Icon(
                Icons.Filled.Shuffle, null,
                tint = OnCardText.copy(alpha = AlphaDisabled),
                modifier = Modifier.size(ControlIconSize),
            )
        }
    }
}

@Composable
private fun MediaEndActionButton(event: IslandEvent.Media, interactor: IslandActions) {
    val ca = event.customActions.getOrNull(1)
    if (ca != null) {
        BareControlButton(onClick = { interactor.sendCustomAction(ca.action) }) {
            CustomActionIcon(ca, tint = SubtleGray, modifier = Modifier.size(ControlIconSize))
        }
    } else {
        BareControlButton(
            onClick = {
                interactor.openMediaOutputSwitcher()
                interactor.collapseIsland()
            }
        ) {
            Icon(
                Icons.Filled.VolumeUp, null,
                tint = OnCardText,
                modifier = Modifier.size(ControlIconSize),
            )
        }
    }
}

@Composable
internal fun RowScope.CompactMediaRow(
    event: IslandEvent.Media,
    interactor: IslandActions,
) {
    event.albumArt?.let {
        Image(
            bitmap = it.toScaledBitmap(SizeCompactIcon),
            null,
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact),
            contentScale = ContentScale.Crop,
        )
    } ?: run {
        val accent = rememberMediaColors(event).accent
        Box(
            modifier = Modifier.size(SizeCompactIcon)
                .clip(ShapeCompact)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = AlphaColors.onAccentColor, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (event.artist.isNotEmpty())
            Text(
                event.artist,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
    }
    Spacer(Modifier.width(SpaceMd))
    Surface(
        onClick = { interactor.togglePlayPause() },
        shape = CircleShape,
        color = ActionBg,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                null,
                tint = OnActionText,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
