/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.AlphaTertiary

/**
 * Ring drawn around the physical display cutout when the cutout pill is idle-collapsed
 * and "Collapse to ring" is enabled.
 *
 * Five rendering modes:
 * - **Charging arc**: growing arc showing battery level (sweepAngle = level/100 × 360°)
 * - **Progress ring**: partial arc (sweepAngle = progress × 360°) over a dim track
 * - **Static ring**: full solid ring
 * - **Pulsing ring**: full ring with alpha oscillation (time events)
 * - **Media rotate**: full circle in accent color with two contrasting segments that rotate
 */

// Ring geometry
private val RingStrokeWidth = 3.dp
private val RingPadding = 1.dp // extra breathing room beyond cutout pad

/**
 * Classifies what ring animation mode to use for a given event.
 */
internal enum class RingMode {
    /** Charging: growing arc showing battery level */
    CHARGING_ARC,
    /** Events with determinate progress: partial arc */
    PROGRESS,
    /** No animation, solid ring */
    STATIC,
    /** Time-based events: pulsing full ring */
    PULSING,
    /** Media: rotating ring with contrasting segments */
    MEDIA_ROTATE,
}

internal fun ringModeFor(event: IslandEvent, progress: Float?): RingMode = when {
    event is IslandEvent.Charging && event.isCharging && event.level < 100 -> RingMode.CHARGING_ARC
    event is IslandEvent.Charging -> RingMode.STATIC
    event is IslandEvent.Media && event.isPlaying -> RingMode.MEDIA_ROTATE
    event is IslandEvent.Media -> RingMode.STATIC
    event is IslandEvent.Timer -> RingMode.PULSING
    event is IslandEvent.Stopwatch -> RingMode.PULSING
    event is IslandEvent.AospChip && event.active.key == "ScreenRecord" -> RingMode.PULSING
    event is IslandEvent.AudioRecording && event.state == RecordingState.RECORDING -> RingMode.PULSING
    progress != null && progress > 0f -> RingMode.PROGRESS
    else -> RingMode.STATIC
}

/**
 * Animated version of [CutoutRing] that wraps the Canvas in the appropriate
 * infinite transition for each ring mode.
 *
 * This is the primary entry point used by [CutoutPillCenter].
 */
@Composable
internal fun AnimatedCutoutRing(
    event: IslandEvent,
    cutoutRectPx: Rect,
    accent: Color,
    progress: Float?,
    ringGap: Float = 1.155f,
    ringScaleX: Float = 1f,
    ringScaleY: Float = 1f,
    ringOffsetXDp: Float = 0f,
    ringOffsetYDp: Float = 0f,
    ringOpacity: Int = 90,
    ringStrokeDp: Float = 3f,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val strokePx = with(density) { ringStrokeDp.dp.toPx() }
    val padPx = with(density) { RingPadding.toPx() }

    val cutoutW = cutoutRectPx.width().toFloat()
    val cutoutH = cutoutRectPx.height().toFloat()
    val baseRadius = maxOf(cutoutW, cutoutH) / 2f
    val gapRadius = baseRadius * ringGap + padPx + strokePx / 2f
    val diameterX = gapRadius * 2f * ringScaleX
    val diameterY = gapRadius * 2f * ringScaleY
    val diameter = maxOf(diameterX, diameterY)
    val diameterDp = with(density) { diameter.toDp() }
    val opacityFactor = ringOpacity / 100f

    val mode = ringModeFor(event, progress)

    // Charging arc: animate sweep to the current battery level.
    val chargingLevel = if (mode == RingMode.CHARGING_ARC && event is IslandEvent.Charging) {
        event.level / 100f
    } else 0f
    val chargingSweep = remember { Animatable(0f) }
    LaunchedEffect(chargingLevel) {
        chargingSweep.animateTo(
            targetValue = chargingLevel,
            animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        )
    }

    val pulseAlpha = if (mode == RingMode.PULSING) {
        val transition = rememberInfiniteTransition(label = "ring_pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ring_pulse_alpha",
        )
        alpha
    } else 1f

    // Rotation for media (only when playing)
    val rotation = if (mode == RingMode.MEDIA_ROTATE) {
        val transition = rememberInfiniteTransition(label = "ring_rotate")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ring_rotate_angle",
        )
        angle
    } else 0f

    Canvas(
        modifier = modifier
            .size(diameterDp),
    ) {
        val rx = gapRadius * ringScaleX - strokePx / 2f
        val ry = gapRadius * ringScaleY - strokePx / 2f
        val radius = maxOf(rx, ry)
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

        val effAccent = accent.copy(alpha = accent.alpha * opacityFactor)
        val ringColor = effAccent.copy(alpha = effAccent.alpha * pulseAlpha)

        when (mode) {
            RingMode.STATIC -> {
                drawCircle(color = ringColor, radius = radius, style = stroke)
            }

            RingMode.CHARGING_ARC -> {
                val trackColor = effAccent.copy(alpha = effAccent.alpha * AlphaTertiary)
                drawCircle(color = trackColor, radius = radius, style = stroke)
                val halfSweep = chargingSweep.value * 180f
                drawArc(
                    color = effAccent,
                    startAngle = 90f - halfSweep,
                    sweepAngle = halfSweep,
                    useCenter = false,
                    style = stroke,
                )
                drawArc(
                    color = effAccent,
                    startAngle = 90f,
                    sweepAngle = halfSweep,
                    useCenter = false,
                    style = stroke,
                )
            }

            RingMode.PULSING -> {
                drawCircle(color = ringColor, radius = radius, style = stroke)
            }

            RingMode.PROGRESS -> {
                val trackColor = effAccent.copy(alpha = effAccent.alpha * AlphaTertiary)
                drawCircle(color = trackColor, radius = radius, style = stroke)
                val sweep = (progress ?: 0f) * 360f
                drawArc(
                    color = effAccent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke,
                )
            }

            RingMode.MEDIA_ROTATE -> {
                val contrastColor = lerp(effAccent, AlphaColors.DbStatusBarChip.ringHighlight, 0.6f)
                val segmentSweep = 30f
                drawCircle(color = ringColor, radius = radius, style = stroke)
                drawArc(
                    color = contrastColor,
                    startAngle = rotation + 180f - segmentSweep / 2f,
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    style = stroke,
                )
                drawArc(
                    color = contrastColor,
                    startAngle = rotation + 0f - segmentSweep / 2f,
                    sweepAngle = segmentSweep,
                    useCenter = false,
                    style = stroke,
                )
            }
        }
    }
}
