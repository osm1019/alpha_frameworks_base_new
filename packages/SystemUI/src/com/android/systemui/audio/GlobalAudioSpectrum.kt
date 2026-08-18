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

package com.android.systemui.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * The output mix (audio session 0), as one Visualizer.
 *
 * AudioFlinger will not create a second session-0 Visualizer. A second `Visualizer(0)` is another
 * handle on the same module, and only the first attacher has control — `setEnabled` and capture
 * registration then fail with `INVALID_OPERATION` and a silent dead tap. Pulse and the media
 * waveform both need this feed, so they share this object instead of each constructing their own.
 *
 * Refcounted: the tap opens for the first [acquire] and closes after the last [release].
 *
 * Two products of each frame:
 * - [levels] — 32 log-spaced bands for waveforms
 * - [FftListener] — the raw interleaved FFT Pulse's engine and bass haptics already consume
 */
internal object GlobalAudioSpectrum {

    private const val TAG = "GlobalAudioSpectrum"

    fun interface FftListener {
        /** [fft] is the Visualizer's reusable buffer. Copy it if you keep it past return. */
        fun onFft(fft: ByteArray)
    }

    /** Band levels, 0f..1f, low frequency first. Empty until the tap produces a frame. */
    @Volatile
    var levels: FloatArray = FloatArray(0)
        private set

    /**
     * Bumped whenever [levels] changes — a new frame, or the tap closing. Read on the UI thread to
     * notice new data.
     *
     * Atomic rather than a `@Volatile` increment: `++` is read-modify-write, so it is only safe
     * while exactly one thread produces frames. That is true today and is not worth depending on.
     */
    private val revisionCounter = AtomicInteger(0)

    val revision: Int
        get() = revisionCounter.get()

    /** Written under the lock, read by [isLive] outside it. */
    @Volatile private var visualizer: Visualizer? = null
    private var refCount = 0
    @Volatile private var lastEnergyUptimeMs = 0L
    private val listeners = CopyOnWriteArrayList<FftListener>()

    /**
     * True while the tap is producing something other than silence.
     *
     * A vendor that offloads playback to the DSP hands back a flat buffer rather than failing, so
     * there is nothing to catch when the tap is opened — the only reliable signal is that no frame
     * has carried energy for a while.
     */
    val isLive: Boolean
        get() =
            visualizer != null &&
                lastEnergyUptimeMs != 0L &&
                SystemClock.uptimeMillis() - lastEnergyUptimeMs < SILENCE_TIMEOUT_MS

    @Synchronized
    fun acquire(): Boolean {
        refCount++
        if (visualizer != null) return true
        if (open()) return true
        refCount--
        return false
    }

    @Synchronized
    fun release() {
        refCount = (refCount - 1).coerceAtLeast(0)
        if (refCount == 0) teardown()
    }

    fun addListener(listener: FftListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: FftListener) {
        listeners.remove(listener)
    }

    private fun open(): Boolean {
        val viz =
            try {
                Visualizer(0)
            } catch (e: Exception) {
                Log.w(TAG, "Visualizer(0) threw", e)
                return false
            }
        val range = Visualizer.getCaptureSizeRange()
        val captureSize = if (range != null && range.size >= 2) range[1] else 0
        val sizeStatus = viz.setCaptureSize(captureSize)
        val listenStatus =
            viz.setDataCaptureListener(
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
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true,
            )
        val enableStatus = viz.setEnabled(true)
        if (
            sizeStatus != AudioEffect.SUCCESS ||
                listenStatus != AudioEffect.SUCCESS ||
                enableStatus != AudioEffect.SUCCESS
        ) {
            Log.w(
                TAG,
                "session-0 Visualizer refused: captureSize=$sizeStatus " +
                    "listener=$listenStatus enabled=$enableStatus",
            )
            try {
                viz.release()
            } catch (e: Exception) {
                Log.w(TAG, "release after refused open", e)
            }
            return false
        }
        visualizer = viz
        return true
    }

    private fun teardown() {
        try {
            visualizer?.apply {
                enabled = false
                setDataCaptureListener(null, 0, false, false)
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "teardown", e)
        }
        visualizer = null
        levels = FloatArray(0)
        lastEnergyUptimeMs = 0L
        // Publish the clear as well as the frames: a consumer watching [revision] would otherwise
        // hold the last frame's bars until something else woke it.
        revisionCounter.incrementAndGet()
    }

    /**
     * Fold one FFT frame into [BAND_COUNT] log-spaced bands, then hand the raw buffer to listeners.
     *
     * The buffer is interleaved real/imaginary pairs across a linear frequency axis, and music puts
     * nearly all of its movement in the bottom of that range — split it evenly and the top two
     * thirds of the bars would barely move. Band edges grow geometrically instead, so each band
     * covers a comparable share of what a listener would call "the sound".
     */
    private fun onFft(fft: ByteArray) {
        val bins = fft.size / 2
        if (bins >= BAND_COUNT) {
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
                val level = (ln(1f + sum / (next - edge)) / LN_FULL_SCALE).coerceIn(0f, 1f)
                out[band] = level
                if (level > peak) peak = level
                edge = next
            }
            if (peak > SILENCE_FLOOR) lastEnergyUptimeMs = SystemClock.uptimeMillis()
            levels = out
            revisionCounter.incrementAndGet()
        }
        for (listener in listeners) {
            listener.onFft(fft)
        }
    }

    private const val SILENCE_FLOOR = 0.02f
    private const val SILENCE_TIMEOUT_MS = 1_500L
    private const val BAND_COUNT = 32
    private val LN_FULL_SCALE = ln(256f)
}
