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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Bar heights, 0f..1f, for a waveform of [barCount] bars at animation position [phase].
 *
 * Two implementations ship: [SyntheticWaveformSource], which invents a plausible row from the
 * playback state alone, and [rememberAudioWaveformSource], which follows the output mix and hands
 * back to the synthetic one whenever the tap is unavailable or silent.
 */
fun interface AxWaveformSource {
    fun amplitudes(barCount: Int, phase: Float, seed: Int): FloatArray
}

/**
 * Each bar pumps **vertically in place**, like an equaliser — every bar gets its own rate and
 * starting phase from a hash, so nothing marches across the row.
 *
 * A phase offset proportional to the bar index (the obvious formulation) produces a wave that
 * travels sideways, which is not what the reference badge does.
 */
val SyntheticWaveformSource = AxWaveformSource { barCount, phase, seed ->
    FloatArray(barCount) { index ->
        val hash = (index * 2654435761u.toInt() + seed * 40503).let { it xor (it ushr 13) }
        val startPhase = (hash and 0xFF) / 255f
        val rate = 0.75f + ((hash ushr 8) and 0xFF) / 255f * 1.1f
        val wobble = 0.35f + ((hash ushr 16) and 0xFF) / 255f * 0.5f
        val turn = (phase * rate + startPhase) * 2f * PI.toFloat()
        val envelope =
            if (barCount <= 1) 1f
            else {
                val centred = abs(index - (barCount - 1) / 2f) / ((barCount - 1) / 2f)
                0.6f + 0.4f * (1f - centred * centred)
            }
        val level = 0.55f + 0.45f * sin(turn) * wobble + 0.12f * sin(turn * 2.7f)
        level.coerceIn(0f, 1f) * envelope
    }
}

/**
 * Animated bar waveform. What the bars *do* comes from [source]; everything about how the row looks
 * — the count, the widths, the pill caps, the rest height and the settle — is decided here, so a
 * live source and the synthetic one draw the same object.
 *
 * Bars settle to [RestFraction] while paused, and stay there when the user has animations off, so
 * the badge never spins a frame loop for nothing.
 */
@Composable
fun AxWaveform(
    playing: Boolean,
    color: Brush,
    modifier: Modifier = Modifier,
    /** Null derives the count from the available width, so a band fills edge to edge. */
    barCount: Int? = 13,
    barWidth: Dp = 2.dp,
    barGap: Dp = 2.dp,
    seed: Int = 0,
    source: AxWaveformSource = SyntheticWaveformSource,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val animate = playing && animationsEnabled
    val transition = rememberInfiniteTransition(label = "AxWaveform")
    val phase by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
            label = "AxWaveformPhase",
        )
    // One value drives every bar's collapse, so pausing reads as the row settling together.
    val liveness by
        animateFloatAsState(
            targetValue = if (animate) 1f else 0f,
            animationSpec = tween(durationMillis = 320),
            label = "AxWaveformLiveness",
        )
    Canvas(modifier = modifier) {
        val widthPx = barWidth.toPx()
        val gapPx = barGap.toPx()
        val bars =
            barCount
                ?: ((size.width + gapPx) / (widthPx + gapPx)).toInt().coerceIn(1, MaxDerivedBars)
        val totalPx = bars * widthPx + (bars - 1) * gapPx
        val startX = (size.width - totalPx) / 2f
        val radius = CornerRadius(widthPx / 2f, widthPx / 2f)
        val live = source.amplitudes(bars, phase, seed)
        for (index in 0 until bars) {
            // Liveness alone drives the collapse, so pausing settles the row instead of freezing it.
            val fraction = RestFraction + (live[index] - RestFraction) * liveness
            val barHeight = (size.height * fraction).coerceAtLeast(widthPx)
            drawRoundRect(
                brush = color,
                topLeft =
                    Offset(x = startX + index * (widthPx + gapPx), y = (size.height - barHeight) / 2f),
                size = Size(widthPx, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Height of a settled bar, as a fraction of the waveform box.
 *
 * Not a flat line: at 5 fat bars and 12% the paused badge read as a "•••••" overflow button. A
 * settled row still has to look like a waveform.
 */
private const val RestFraction = 0.3f

/** Ceiling for width-derived bar counts, so a wide surface cannot ask for hundreds of bars. */
private const val MaxDerivedBars = 128
