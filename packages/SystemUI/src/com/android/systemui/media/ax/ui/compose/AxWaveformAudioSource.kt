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

import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * The output mix, as band levels, for surfaces that want a waveform to follow the music.
 *
 * Refcounted: the tap opens for the first subscriber and closes after the last, so nothing runs
 * while no waveform is on screen.
 *
 * Deliberately not routed through Pulse's `PulseAudioDataProcessor`. That one keeps a single
 * `WeakReference` listener, which a second consumer would silently evict, and its capture is
 * started and stopped by Pulse's own visibility and settings — so with Pulse switched off, which is
 * the default, there would be nothing to listen to.
 */
internal object AxAudioSpectrum {

    /** Band levels, 0f..1f, low frequency first. Empty until the tap produces a frame. */
    @Volatile
    var levels: FloatArray = FloatArray(0)
        private set

    /** Bumped on every captured frame. Read on the UI thread to notice new data. */
    @Volatile
    var revision: Int = 0
        private set

    private var visualizer: Visualizer? = null
    private var refCount = 0
    @Volatile private var lastEnergyUptimeMs = 0L

    /**
     * True while the tap is producing something other than silence.
     *
     * A vendor that offloads playback to the DSP hands back a flat buffer rather than failing, so
     * there is nothing to catch when the tap is opened — the only reliable signal is that no frame
     * has carried energy for a while. Both astonc and grus were confirmed to feed it, but this is
     * `frameworks/base`: the same binary runs on hardware neither of us has checked.
     */
    val isLive: Boolean
        get() =
            visualizer != null &&
                lastEnergyUptimeMs != 0L &&
                SystemClock.uptimeMillis() - lastEnergyUptimeMs < SILENCE_TIMEOUT_MS

    @Synchronized
    fun acquire() {
        refCount++
        if (visualizer != null) return
        try {
            visualizer =
                Visualizer(0).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                visualizer: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int,
                            ) {}

                            override fun onFftDataCapture(
                                visualizer: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int,
                            ) {
                                if (fft != null && fft.size >= 4) onFft(fft)
                            }
                        },
                        // Half the maximum, as Pulse uses. The capture thread costs the same
                        // whether a dozen bars read every frame or not.
                        Visualizer.getMaxCaptureRate() / 2,
                        false,
                        true,
                    )
                    enabled = true
                }
        } catch (e: Exception) {
            // No tap (policy, no permission, session held elsewhere). Callers see isLive = false
            // and keep drawing the synthetic waveform.
            teardown()
        }
    }

    @Synchronized
    fun release() {
        refCount = (refCount - 1).coerceAtLeast(0)
        if (refCount == 0) teardown()
    }

    private fun teardown() {
        try {
            visualizer?.apply {
                enabled = false
                setDataCaptureListener(null, 0, false, false)
                release()
            }
        } catch (_: Exception) {
            // Already torn down by the framework.
        }
        visualizer = null
        levels = FloatArray(0)
        lastEnergyUptimeMs = 0L
    }

    /**
     * Fold one FFT frame into [BAND_COUNT] log-spaced bands.
     *
     * The buffer is interleaved real/imaginary pairs across a linear frequency axis, and music puts
     * nearly all of its movement in the bottom of that range — split it evenly and the top two
     * thirds of the bars would barely move. Band edges grow geometrically instead, so each band
     * covers a comparable share of what a listener would call "the sound".
     */
    private fun onFft(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins < BAND_COUNT) return
        val out = FloatArray(BAND_COUNT)
        var peak = 0f
        var edge = 1
        for (band in 0 until BAND_COUNT) {
            val next =
                bins.toFloat()
                    .pow((band + 1).toFloat() / BAND_COUNT)
                    .toInt()
                    .coerceIn(edge + 1, bins)
            var sum = 0f
            for (bin in edge until next) {
                sum += hypot(fft[bin * 2].toFloat(), fft[bin * 2 + 1].toFloat())
            }
            // Loudness is logarithmic; raw magnitude leaves everything but the chorus on the floor.
            val level = (ln(1f + sum / (next - edge)) / LN_FULL_SCALE).coerceIn(0f, 1f)
            out[band] = level
            if (level > peak) peak = level
            edge = next
        }
        if (peak > SILENCE_FLOOR) lastEnergyUptimeMs = SystemClock.uptimeMillis()
        levels = out
        revision++
    }

    /** Below this a frame counts as silence and the fallback timer keeps running. */
    private const val SILENCE_FLOOR = 0.02f
    private const val SILENCE_TIMEOUT_MS = 1_500L
    private const val BAND_COUNT = 32
    /** ln(1 + 255): the largest magnitude one bin can report. */
    private val LN_FULL_SCALE = ln(256f)
}

/**
 * An [AxWaveformSource] that follows the audio while there is audio to follow.
 *
 * Falls back to [SyntheticWaveformSource] whenever the tap is closed, refused, or handing back
 * silence, so the worst case is exactly the previous behaviour rather than a dead row.
 *
 * Bar geometry is not this function's business: it returns levels, and [AxWaveform] keeps owning
 * the count, the widths, the rest height and the settle on pause.
 */
@Composable
internal fun rememberAudioWaveformSource(playing: Boolean): AxWaveformSource {
    DisposableEffect(playing) {
        if (!playing) return@DisposableEffect onDispose {}
        AxAudioSpectrum.acquire()
        onDispose { AxAudioSpectrum.release() }
    }
    val revision = rememberSpectrumRevision(playing)
    return remember(revision) {
        AxWaveformSource { barCount, phase, seed ->
            // Registers the snapshot read that repaints the canvas when a frame lands. The value
            // is not otherwise used; the levels themselves are read straight off the tap.
            revision.value
            val bands = AxAudioSpectrum.levels
            if (!AxAudioSpectrum.isLive || bands.isEmpty()) {
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
 * [Visualizer] delivers on its own thread far faster than a waveform needs, so writing snapshot
 * state per capture would schedule recompositions the display never shows.
 */
@Composable
private fun rememberSpectrumRevision(playing: Boolean): State<Int> {
    val revision = remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { revision.intValue = AxAudioSpectrum.revision }
        }
    }
    return revision
}
