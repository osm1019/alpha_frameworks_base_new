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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.android.systemui.audio.GlobalAudioSpectrum

/**
 * An [AxWaveformSource] that follows the audio while there is audio to follow.
 *
 * The tap is [GlobalAudioSpectrum] — shared with Pulse, because AudioFlinger will not create a
 * second session-0 Visualizer. Falls back to [SyntheticWaveformSource] whenever the tap is
 * closed, refused, or handing back silence.
 *
 * Bar geometry is not this function's business: it returns levels, and [AxWaveform] keeps owning
 * the count, the widths, the rest height and the settle on pause.
 */
@Composable
internal fun rememberAudioWaveformSource(playing: Boolean): AxWaveformSource {
    DisposableEffect(playing) {
        if (!playing) return@DisposableEffect onDispose {}
        val held = GlobalAudioSpectrum.acquire()
        onDispose { if (held) GlobalAudioSpectrum.release() }
    }
    val revision = rememberSpectrumRevision(playing)
    return remember(revision) {
        AxWaveformSource { barCount, phase, seed ->
            // Registers the snapshot read that repaints the canvas when a frame lands. The value
            // is not otherwise used; the levels themselves are read straight off the tap.
            revision.value
            val bands = GlobalAudioSpectrum.levels
            if (!GlobalAudioSpectrum.isLive || bands.isEmpty()) {
                SyntheticWaveformSource.amplitudes(barCount, phase, seed)
            } else {
                FloatArray(barCount) { index ->
                    val from = index * bands.size / barCount
                    val to = ((index + 1) * bands.size / barCount).coerceAtLeast(from + 1)
                    var sum = 0f
                    for (band in from until to) sum += bands[band]
                    sum / (to - from)
                }
            }
        }
    }
}

/**
 * Samples the tap once per displayed frame.
 *
 * The session-0 tap delivers on its own thread far faster than a waveform needs, so writing
 * snapshot state per capture would schedule recompositions the display never shows.
 */
@Composable
private fun rememberSpectrumRevision(playing: Boolean): State<Int> {
    val revision = remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { revision.intValue = GlobalAudioSpectrum.revision }
        }
    }
    return revision
}
