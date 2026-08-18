/*
 * Copyright (C) 2025 The AxionAOSP Project
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
package com.android.systemui.pulse

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.systemui.audio.GlobalAudioSpectrum
import java.lang.ref.WeakReference

/**
 * Pulse's consumer of the session-0 tap.
 *
 * Does not construct a [android.media.audiofx.Visualizer]. AudioFlinger will not create a second
 * session-0 effect, so this [acquire]s [GlobalAudioSpectrum] and keeps its own refresh throttle
 * and [PulseData] copy on the listener.
 */
class PulseAudioDataProcessor(private val context: Context) {

    companion object {
        private const val TAG = "PulseAudioProcessor"
    }

    private var dataListener: WeakReference<DataListener>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false

    private val pulseData = PulseFFTData()
    private var lastUpdateTime = 0L
    private var updateThrottle = 16L
    private var lastKnownRefreshRateHz: Float = 60f

    private val fftListener =
        GlobalAudioSpectrum.FftListener { fft ->
            updateThrottle()
            processFFTData(fft)
        }

    interface DataListener {
        fun onDataUpdate(data: PulseData)
    }

    fun setDataListener(listener: DataListener) {
        dataListener = WeakReference(listener)
    }

    fun startCapture() {
        if (isProcessing) return
        if (!GlobalAudioSpectrum.acquire()) {
            Log.w(TAG, "session-0 tap refused")
            return
        }
        GlobalAudioSpectrum.addListener(fftListener)
        isProcessing = true
    }

    fun stopCapture() {
        if (!isProcessing) return
        GlobalAudioSpectrum.removeListener(fftListener)
        GlobalAudioSpectrum.release()
        isProcessing = false
        pulseData.reset()
    }

    fun cleanup() {
        stopCapture()
        dataListener?.clear()
        dataListener = null
    }

    private fun processFFTData(fftBytes: ByteArray) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < updateThrottle) {
            return
        }
        lastUpdateTime = currentTime
        pulseData.updateFFTData(fftBytes)
        handler.post {
            dataListener?.get()?.onDataUpdate(pulseData)
        }
    }

    private fun updateThrottle() {
        val display = context.display ?: return
        val refreshRate = display.refreshRate
        if (refreshRate != lastKnownRefreshRateHz) {
            lastKnownRefreshRateHz = refreshRate
            updateThrottle = (1000f / refreshRate).toLong()
        }
    }

    fun isCapturing(): Boolean = isProcessing
}
