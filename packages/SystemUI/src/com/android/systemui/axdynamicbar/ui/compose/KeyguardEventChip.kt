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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.dbLockscreenPillChrome
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.media.ax.ui.compose.AxWaveform
import kotlin.math.abs

/**
 * 48dp circular lane occupant: glass body, [LaneMediaWaveform] (media) or [PillEventIcon],
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
    val chrome = dbLockscreenPillChrome(accent, isMedia = event is IslandEvent.Media, blurred = blurred)
    val contentColor by
        animateColorAsState(chrome.content, motionScheme.fastEffectsSpec(), label = "kg_chip_content")
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
            LaneMediaWaveform(event.isPlaying, contentColor, size - 8.dp)
        } else {
            LaneEventIcon(event, contentColor, size - SpaceLg)
        }
        if (progress != null) {
            KeyguardChipProgressRing(
                progress = progress,
                accent = accent,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * Equaliser bars in the media circle — same [AxWaveform] the lockscreen media style uses,
 * the Compose cousin of the volume panel's `ic_sound_bars_anim`.
 *
 * Plate is the chip body (theme-following: dark night, light day). Bars are [tint]
 * (`onSurface`: light on dark, dark on light). Settles when paused. Size is the caller's.
 */
@Composable
private fun LaneMediaWaveform(playing: Boolean, tint: Color, size: Dp) {
    AxWaveform(
        playing = playing,
        color = SolidColor(tint),
        modifier = Modifier.size(size * 0.55f, size * 0.5f),
        barCount = 4,
        barWidth = 2.5.dp,
        barGap = 2.dp,
    )
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
internal fun ScaledPillEventIcon(
    event: IslandEvent,
    tint: Color,
    size: Dp,
    animated: Boolean = true,
) {
    val native = SizeBadge
    val factor = size / native
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.graphicsLayer {
                scaleX = factor
                scaleY = factor
            }
        ) {
            PillEventIcon(event, tint = tint, animated = animated)
        }
    }
}

/**
 * 0–360° sweep from 12 o'clock. Determinate progress only.
 *
 * Fill is the event accent (charging green, album colour, …). A dark halo and a 2dp
 * stroke keep it readable on glass and on album art; the hue is not washed toward
 * onSurface.
 */
@Composable
internal fun KeyguardChipProgressRing(
    progress: Float,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val halo = 3.5.dp.toPx()
        val inset = stroke / 2 + 1.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        val haloStyle = Stroke(width = halo, cap = StrokeCap.Round)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        val haloColor = Color.Black.copy(alpha = 0.5f)
        val track = accent.copy(alpha = 0.32f)
        fun arc(color: Color, sweep: Float, strokeStyle: Stroke) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = strokeStyle,
            )
        }
        arc(haloColor, 360f, haloStyle)
        arc(track, 360f, style)
        if (progress > 0f) {
            val sweep = 360f * progress
            arc(haloColor, sweep, haloStyle)
            arc(accent, sweep, style)
        }
    }
}
