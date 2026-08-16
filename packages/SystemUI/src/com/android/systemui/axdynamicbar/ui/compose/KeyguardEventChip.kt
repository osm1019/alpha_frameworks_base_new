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

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.ShapeXs
import com.android.systemui.axdynamicbar.shared.SizeBadge
import com.android.systemui.axdynamicbar.shared.SpaceLg
import com.android.systemui.axdynamicbar.shared.chipAccentColorFor
import com.android.systemui.axdynamicbar.shared.chipProgressColorFor
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.dbLockscreenPillChrome
import com.android.systemui.axdynamicbar.shared.ProgressTrackAlpha
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.media.ax.ui.compose.AxWaveform
import kotlin.math.abs

/**
 * 48dp circular lane occupant: glass body, [LaneMediaGlyph] (media) or [PillEventIcon],
 * optional progress ring. Badge / overflow chrome is intentionally absent.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun KeyguardEventChip(
    event: IslandEvent,
    size: Dp,
    blurred: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    val rawAccent = chipAccentColorFor(event)
    val accent by
        animateColorAsState(rawAccent, motionScheme.fastEffectsSpec(), label = "kg_chip_accent")
    val chrome = dbLockscreenPillChrome(accent, untinted = event is IslandEvent.Media, blurred = blurred)
    val contentColor by
        animateColorAsState(chrome.content, motionScheme.fastEffectsSpec(), label = "kg_chip_content")
    val rawProgressColor = chipProgressColorFor(event, chrome.body)
    val progressColor by
        animateColorAsState(rawProgressColor, motionScheme.fastEffectsSpec(), label = "kg_chip_progress")
    val rawProgress = chipProgressFor(event)
    val progressTarget = rawProgress ?: 0f
    val progressAnim = remember { Animatable(progressTarget) }
    LaunchedEffect(progressTarget) {
        if (abs(progressTarget - progressAnim.value) > 0.05f) {
            progressAnim.animateTo(progressTarget, tween(300, easing = FastOutSlowInEasing))
        } else {
            progressAnim.snapTo(progressTarget)
        }
    }
    val progress = if (rawProgress != null) progressAnim.value else null

    Box(
        modifier =
            modifier
                .size(size)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (blurred) {
            ChipGlassBackdrop(corner = size / 2, modifier = Modifier.matchParentSize())
        }
        Box(
            modifier =
                Modifier.matchParentSize()
                    .clip(CircleShape)
                    .background(chrome.body)
                    .border(AlphaColors.DbLockscreenPill.rimWidth, chrome.border, CircleShape)
        )
        if (event is IslandEvent.Media) {
            LaneMediaGlyph(event.isPlaying, contentColor, size - 8.dp)
        } else {
            LaneEventIcon(event, contentColor, size - SpaceLg)
        }
        if (progress != null) {
            KeyguardChipProgressRing(
                progress = progress,
                fill = progressColor,
                track = contentColor.copy(alpha = ProgressTrackAlpha),
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * The media circle's glyph: equaliser bars while playing, a play triangle while paused.
 *
 * [AxWaveform] settles to four flat bars when it stops, which names nothing — the chip has to
 * stay readable as *media*, and as resumable, while it is not moving. Bars are the same
 * [AxWaveform] the lockscreen media style uses, the Compose cousin of the volume panel's
 * `ic_sound_bars_anim`; plate is the chip body (theme-following), glyph is [tint].
 *
 * Fixed [size] box with both states centred inside it, so the swap is opacity only and the
 * circle's contents never resize.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LaneMediaGlyph(playing: Boolean, tint: Color, size: Dp) {
    Crossfade(
        targetState = playing,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "kg_media_glyph",
        modifier = Modifier.size(size),
    ) { isPlaying ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isPlaying) {
                AxWaveform(
                    playing = true,
                    color = SolidColor(tint),
                    modifier = Modifier.size(size * 0.55f, size * 0.5f),
                    barCount = 4,
                    barWidth = 2.5.dp,
                    barGap = 2.dp,
                )
            } else {
                // Larger than the bar block it replaces: a Material icon carries padding inside
                // its viewport, so matching the drawn glyph means overshooting the box.
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(size * 0.65f),
                )
            }
        }
    }
}

/**
 * A lane occupant's glyph at the lane's own size.
 *
 * Vectors go through [ScaledPillEventIcon], which magnifies them without cost. Bitmaps do
 * not survive that — [PillEventIcon] rasters album art and app icons at 16dp, and the lane
 * draws them more than twice as large — so those are re-rastered here instead.
 */
@Composable
private fun LaneEventIcon(event: IslandEvent, tint: Color, size: Dp) {
    val drawable = pillIconDrawable(event)
    if (drawable == null) {
        ScaledPillEventIcon(event, tint, size)
    } else {
        Image(
            bitmap = drawable.toScaledBitmap(size),
            contentDescription = null,
            modifier =
                Modifier.size(size).clip(if (pillIconIsRound(event)) CircleShape else ShapeXs),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
internal fun ScaledPillEventIcon(event: IslandEvent, tint: Color, size: Dp) {
    val native = SizeBadge
    val factor = size / native
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.graphicsLayer {
                scaleX = factor
                scaleY = factor
            }
        ) {
            PillEventIcon(event, tint = tint)
        }
    }
}

/**
 * 0–360° sweep from 12 o'clock. Determinate progress only.
 *
 * [track] is the full circle (total / missing), [fill] the swept part (done).
 * Both are picked against the plate by the caller, so nothing is drawn here
 * that has not already been checked for contrast — no halo underneath.
 */
@Composable
internal fun KeyguardChipProgressRing(
    progress: Float,
    fill: Color,
    track: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2 + 1.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        fun arc(color: Color, sweep: Float) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = style,
            )
        }
        arc(track, 360f)
        if (progress > 0f) arc(fill, 360f * progress)
    }
}
