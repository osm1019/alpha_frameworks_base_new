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

package com.oplus.vfxsdk.charge.charging;

import android.util.Log;

import com.oplus.vfxsdk.charge.BaseEngine;
import com.oplus.vfxsdk.charge.Debugger;
import com.oplus.vfxsdk.charge.IEngine;

/**
 * JNI wrapper for {@code libnativeChargingRing} version 1.0.9.
 * Native method names and {@link #onFlashAnimationFinishedCallBack()} are ABI-frozen.
 */
public class VFXChargingEngine extends BaseEngine {
    static final String TAG = "VFXChargingEngine";
    static final String VERSION = "1.0.9";

    private IChargingEngineControl mChargingEngineControl;

    public static native long nativeInit(IEngine engine);

    public static native void nativeSetAnimationDuration(
            long handle, float a, float b, float c, float d);

    public static native void nativeSetBlurWeight(long handle, float a, float b);

    public static native void nativeSetCenter(long handle, float x, float y);

    public static native void nativeSetDensityScale(long handle, float scale);

    public static native void nativeSetFlashDuration(long handle, float duration);

    public static native void nativeSetFlashSizeAndOffset(long handle, int size, float offset);

    public static native void nativeSetFrameInterval(long handle, int interval);

    public static native void nativeSetParticles(
            long handle, int count, int life, float speed, float size);

    public static native void nativeSetParticlesCircle(long handle, float inner, float outer);

    public static native void nativeSetParticlesDistortion(long handle, float distortion);

    public static native void nativeSetParticlesLuminance(long handle, float luminance);

    public static native void nativeSetParticlesMoveType(long handle, int type);

    public static native void nativeSetParticlesRing(long handle, float inner, float outer);

    public static native void nativeSetRingAndParticlesCDDisplacement(
            long handle, int a, int b, float c, float d);

    public static native void nativeSetRingFadeInCurve(
            long handle, float a, float b, float c, float d);

    public static native void nativeSetRingFadeOutCurve(
            long handle, float a, float b, float c, float d);

    public static native void nativeSetRingGlowTime(
            long handle, float a, float b, float c, float d);

    public static native void nativeSetRingGlowWeight(long handle, float weight);

    public static native void nativeSetRingLineWeight(long handle, float weight);

    public static native void nativeSetRingRadius(long handle, int radius);

    public static native void nativeSetRingRingWeight(long handle, float weight);

    public static native void nativeSetRingRotateSpeed(long handle, float a, float b);

    public static native void nativeStart(long handle);

    public static native void nativeStop(long handle);

    /** Called from native after the 1.28s flash. Name is ABI-frozen. */
    private void onFlashAnimationFinishedCallBack() {
        Debugger.i(TAG, "onFlashAnimationFinishedCallBack");
        IChargingEngineControl control = mChargingEngineControl;
        if (control != null) {
            control.onFlashAnimationFinishedCallBack();
        }
    }

    public void init() {
        if (mHandle != 0) {
            Log.e("ChargingEffect_VFXChargingEngine", "A render thread exist.");
            return;
        }
        mHandle = nativeInit(this);
        Debugger.i(TAG, VERSION);
    }

    public boolean isInitialized() {
        return mHandle != 0;
    }

    public void release() {
        mChargingEngineControl = null;
    }

    public void setAnimationDuration(float a, float b, float c, float d) {
        if (mHandle != 0) {
            nativeSetAnimationDuration(mHandle, a, b, c, d);
        }
    }

    public void setBlurWeight(float a, float b) {
        if (mHandle != 0) {
            nativeSetBlurWeight(mHandle, a, b);
        }
    }

    public void setCenter(float x, float y) {
        if (mHandle != 0) {
            nativeSetCenter(mHandle, x, y);
        }
    }

    public void setChargingEngineControl(IChargingEngineControl control) {
        mChargingEngineControl = control;
    }

    public void setDensityScale(float scale) {
        if (mHandle != 0) {
            nativeSetDensityScale(mHandle, scale);
        }
    }

    public void setFlashDuration(float duration) {
        if (mHandle != 0) {
            nativeSetFlashDuration(mHandle, duration);
        }
    }

    public void setFlashSizeAndOffset(int size, float offset) {
        if (mHandle != 0) {
            nativeSetFlashSizeAndOffset(mHandle, size, offset);
        }
    }

    public void setFrameInterval(int interval) {
        if (mHandle != 0) {
            nativeSetFrameInterval(mHandle, interval);
        }
    }

    public void setParticles(int count, int life, float speed, float size) {
        if (mHandle != 0) {
            nativeSetParticles(mHandle, count, life, speed, size);
        }
    }

    public void setParticlesCircle(float inner, float outer) {
        if (mHandle != 0) {
            nativeSetParticlesCircle(mHandle, inner, outer);
        }
    }

    public void setParticlesDistortion(float distortion) {
        if (mHandle != 0) {
            nativeSetParticlesDistortion(mHandle, distortion);
        }
    }

    public void setParticlesLuminance(float luminance) {
        if (mHandle != 0) {
            nativeSetParticlesLuminance(mHandle, luminance);
        }
    }

    public void setParticlesMoveType(int type) {
        if (mHandle != 0) {
            nativeSetParticlesMoveType(mHandle, type);
        }
    }

    public void setParticlesRing(float inner, float outer) {
        if (mHandle != 0) {
            nativeSetParticlesRing(mHandle, inner, outer);
        }
    }

    public void setRingAndParticlesCDDisplacement(int a, int b, float c, float d) {
        if (mHandle != 0) {
            nativeSetRingAndParticlesCDDisplacement(mHandle, a, b, c, d);
        }
    }

    public void setRingFadeInCurve(float a, float b, float c, float d) {
        if (mHandle != 0) {
            nativeSetRingFadeInCurve(mHandle, a, b, c, d);
        }
    }

    public void setRingFadeOutCurve(float a, float b, float c, float d) {
        if (mHandle != 0) {
            nativeSetRingFadeOutCurve(mHandle, a, b, c, d);
        }
    }

    public void setRingGlowTime(float a, float b, float c, float d) {
        if (mHandle != 0) {
            nativeSetRingGlowTime(mHandle, a, b, c, d);
        }
    }

    public void setRingGlowWeight(float weight) {
        if (mHandle != 0) {
            nativeSetRingGlowWeight(mHandle, weight);
        }
    }

    public void setRingLineWeight(float weight) {
        if (mHandle != 0) {
            nativeSetRingLineWeight(mHandle, weight);
        }
    }

    public void setRingRadius(int radius) {
        if (mHandle != 0) {
            nativeSetRingRadius(mHandle, radius);
        }
    }

    public void setRingRingWeight(float weight) {
        if (mHandle != 0) {
            nativeSetRingRingWeight(mHandle, weight);
        }
    }

    public void setRingRotateSpeed(float a, float b) {
        if (mHandle != 0) {
            nativeSetRingRotateSpeed(mHandle, a, b);
        }
    }

    public void start() {
        if (mHandle != 0) {
            nativeStart(mHandle);
        }
    }

    public void stop() {
        if (mHandle != 0) {
            nativeStop(mHandle);
        }
    }
}
