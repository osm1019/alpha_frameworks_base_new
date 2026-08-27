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

package com.oplus.vfxsdk.charge;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.Surface;

/**
 * GLES charging-ring engine base. JNI names are frozen by
 * {@code libnativeChargingRing.so}.
 */
public abstract class BaseEngine implements IEngine {
    static final String TAG = "BaseEngine";

    public enum TextureID {
        BACKGROUND(0),
        FOREGROUND(1),
        TEXTURE3(2),
        TEXTURE4(3),
        TEXTURE5(4),
        TEXTURE6(5),
        TEXTURE7(6),
        TEXTURE8(7),
        TEXTURE9(8),
        TEXTURE10(9),
        TEXTURE11(10),
        TEXTURE12(11);

        public final int id;

        TextureID(int id) {
            this.id = id;
        }
    }

    protected long mHandle = 0;

    static {
        System.loadLibrary("nativeChargingRing");
    }

    public static native void nativeDestroySurface(long handle);

    public static native void nativeExit(long handle);

    public static native void nativeSetAssetManager(long handle, AssetManager assetManager);

    public static native void nativeSetBitmap(long handle, Bitmap bitmap, int textureId);

    public static native void nativeSetDensity(long handle, double density);

    public static native void nativeSetSurface(long handle, Surface surface);

    public void destroySurface() {
        if (mHandle != 0) {
            nativeDestroySurface(mHandle);
        }
    }

    public void exit() {
        if (mHandle != 0) {
            nativeExit(mHandle);
            mHandle = 0;
        }
    }

    @Override
    public void setAssetManager(AssetManager assetManager) {
        if (mHandle != 0) {
            nativeSetAssetManager(mHandle, assetManager);
        }
    }

    public void setBitmap(Bitmap bitmap, TextureID textureID) {
        Debugger.i(TAG, "setBitmap handler" + mHandle);
        if (mHandle != 0 && bitmap != null) {
            nativeSetBitmap(mHandle, bitmap, textureID.id);
        }
    }

    @Override
    public void setDensity(float density) {
        if (mHandle != 0) {
            nativeSetDensity(mHandle, density);
        }
    }

    @Override
    public void setSurface(Surface surface) {
        Debugger.i(TAG, "setSurface");
        if (mHandle != 0) {
            nativeSetSurface(mHandle, surface);
        }
    }
}
