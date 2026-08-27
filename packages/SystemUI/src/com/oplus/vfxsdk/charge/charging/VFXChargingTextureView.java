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

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;

import com.oplus.vfxsdk.charge.BaseEngine;
import com.oplus.vfxsdk.charge.Debugger;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * GLES charging ring. HIGH/MEDIUM/LOW uniforms match 12R SystemUI
 * {@code setChargineType} (dex values, not the mis-resolved Heytap/Zoom constants).
 */
public class VFXChargingTextureView extends TextureView
        implements TextureView.SurfaceTextureListener {
    private static final String TAG = "VFXChargingTextureView";
    private static final String OVERLAY_PACKAGE =
            "com.android.systemui.charging_animation.supervooc";

    public enum ChargineType {
        HIGH,
        MEDIUM,
        LOW
    }

    private final VFXChargingEngine mInstance = new VFXChargingEngine();
    private final BlockingQueue<Runnable> mQueue = new ArrayBlockingQueue<>(10);
    private final Thread mResourceLoadThread;

    private Surface mSurface;
    private int mWidth;
    private int mHeight;
    private volatile boolean mIsResourceLoaded;
    private volatile boolean mIsSurfaceDestroyed;
    private volatile boolean mResourceLoadFailed;
    private volatile ResourceCallback mResourceCallback;
    private boolean mCallbackDispatched;

    /** Notified on the view thread after the three GLES textures are uploaded (or fail). */
    public interface ResourceCallback {
        void onResourcesLoaded(boolean success);
    }

    public VFXChargingTextureView(Context context) {
        this(context, null);
    }

    public VFXChargingTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOpaque(false);
        setClipToOutline(false);
        setSurfaceTextureListener(this);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        mInstance.init();
        mInstance.setDensity(metrics.density);
        mInstance.setAssetManager(context.getAssets());
        mResourceLoadThread = new Thread(this::loadResources, "VfxChargeTex");
        mResourceLoadThread.start();
    }

    public boolean isEngineReady() {
        return mInstance.isInitialized() && !mResourceLoadFailed;
    }

    public void setResourceCallback(ResourceCallback callback) {
        mResourceCallback = callback;
        if (mIsResourceLoaded) {
            notifyResourceCallback();
        }
    }

    public void setAssetManager(AssetManager assetManager) {
        mInstance.setAssetManager(assetManager);
    }

    public void setChargingEngineControl(IChargingEngineControl control) {
        mInstance.setChargingEngineControl(control);
    }

    public void setDensityScale(float scale) {
        mInstance.setDensityScale(scale);
        Debugger.i(TAG, "setDensityScale: " + scale);
    }

    public void setFlashDuration(float duration) {
        mInstance.setFlashDuration(duration);
    }

    public void setFrameInterval(int interval) {
        mInstance.setFrameInterval(interval);
    }

    public void setParticlesDistortion(float distortion) {
        mInstance.setParticlesDistortion(distortion);
    }

    public void setParticlesLuminance(float luminance) {
        mInstance.setParticlesLuminance(luminance);
    }

    public void setParticlesMoveType(int type) {
        mInstance.setParticlesMoveType(type);
    }

    public void setRingGlowWeight(float weight) {
        mInstance.setRingGlowWeight(weight);
    }

    public void setRingLineWeight(float weight) {
        mInstance.setRingLineWeight(weight);
    }

    public void setRingRadius(int radius) {
        mInstance.setRingRadius(radius);
    }

    public void setRingRingWeight(float weight) {
        mInstance.setRingRingWeight(weight);
    }

    /**
     * SuperVOOC = HIGH, VOOC = MEDIUM, else LOW. Values recovered from 12R dex.
     */
    public void setChargineType(ChargineType type) {
        switch (type) {
            case HIGH:
                mInstance.setParticles(256, 30, 0.003f, 15.0f);
                mInstance.setParticlesCircle(0.0f, 0.4f);
                setParticlesMoveType(0);
                mInstance.setParticlesRing(93.0f, 135.0f);
                mInstance.setRingAndParticlesCDDisplacement(2, 12, 0.08f, 0.08f);
                setRingGlowWeight(1.0f);
                setParticlesLuminance(1.11f);
                setParticlesDistortion(1.8f);
                mInstance.setRingGlowTime(6.0f, 5.0f, 3.0f, 0.6f);
                mInstance.setRingRotateSpeed(0.5f, 0.3f);
                mInstance.setBlurWeight(0.01f, 1.0f);
                setRingRadius(190);
                mInstance.setFlashSizeAndOffset(60, 0.98f);
                setFlashDuration(1.28f);
                mInstance.setAnimationDuration(20.0f, 1.0f, 0.5f, 0.0f);
                break;
            case MEDIUM:
                mInstance.setParticles(120, 30, 0.002f, 15.0f);
                mInstance.setParticlesCircle(0.0f, 0.36f);
                setParticlesMoveType(0);
                setParticlesLuminance(1.0f);
                mInstance.setParticlesRing(93.0f, 135.0f);
                mInstance.setRingAndParticlesCDDisplacement(0, 4, 0.03f, 0.03f);
                setRingGlowWeight(1.0f);
                setParticlesDistortion(1.5f);
                mInstance.setRingGlowTime(6.0f, 3.0f, 3.0f, 0.6f);
                mInstance.setRingRotateSpeed(0.45f, 0.3f);
                mInstance.setBlurWeight(0.01f, 1.0f);
                mInstance.setFlashSizeAndOffset(60, 0.98f);
                setFlashDuration(1.28f);
                mInstance.setAnimationDuration(20.0f, 1.0f, 0.5f, 0.0f);
                setRingRadius(190);
                break;
            case LOW:
            default:
                mInstance.setParticles(196, 20, 0.0006f, 6.0f);
                mInstance.setParticlesCircle(0.1f, 0.15f);
                setParticlesMoveType(1);
                mInstance.setParticlesRing(93.0f, 135.0f);
                mInstance.setRingAndParticlesCDDisplacement(0, 1, 0.03f, 0.03f);
                setRingGlowWeight(1.0f);
                setParticlesDistortion(1.1f);
                mInstance.setRingGlowTime(6.0f, 3.0f, 3.0f, 0.3f);
                mInstance.setBlurWeight(0.02f, 1.0f);
                mInstance.setRingRotateSpeed(0.35f, 0.3f);
                setRingRadius(190);
                setParticlesLuminance(1.0f);
                mInstance.setFlashSizeAndOffset(60, 0.98f);
                setFlashDuration(1.28f);
                mInstance.setAnimationDuration(20.0f, 1.0f, 0.5f, 0.0f);
                break;
        }
        setRingLineWeight(1.0f);
        setRingRingWeight(1.0f);
        mInstance.setRingFadeInCurve(0.35f, 0.75f, 0.0f, 1.0f);
        mInstance.setRingFadeOutCurve(0.36f, 0.36f, 0.59f, 0.59f);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        Debugger.i(TAG, "onSurfaceTextureAvailable " + width + "x" + height
                + " hw=" + isHardwareAccelerated());
        mSurface = new Surface(surfaceTexture);
        mWidth = width;
        mHeight = height;
        setSurfaceAsync();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        Debugger.i(TAG, "onSurfaceTextureSizeChanged");
        if (mWidth == width && mHeight == height) {
            return;
        }
        mWidth = width;
        mHeight = height;
        mSurface = new Surface(surfaceTexture);
        setSurfaceAsync();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        Debugger.i(TAG, "onSurfaceTextureDestroyed");
        mIsSurfaceDestroyed = true;
        mInstance.destroySurface();
        mInstance.release();
        mInstance.exit();
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}

    private void loadResources() {
        Bitmap ring = openTexture("ring_4_in_1", "oplus_vfx_ring_4_in_1");
        Bitmap glow = openTexture("glow", "oplus_vfx_glow");
        Bitmap flash = openTexture("flash", "oplus_vfx_flash");
        if (ring == null || glow == null || flash == null) {
            Debugger.i(TAG, "load resources failed ring=" + (ring != null)
                    + " glow=" + (glow != null) + " flash=" + (flash != null));
            mResourceLoadFailed = true;
            recycleQuietly(ring);
            recycleQuietly(glow);
            recycleQuietly(flash);
            mIsResourceLoaded = true;
            notifyResourceCallback();
            drainQueue();
            return;
        }
        logBitmap("ring_4_in_1", ring);
        logBitmap("glow", glow);
        logBitmap("flash", flash);
        mInstance.setBitmap(ring, BaseEngine.TextureID.TEXTURE3);
        mInstance.setBitmap(glow, BaseEngine.TextureID.TEXTURE5);
        mInstance.setBitmap(flash, BaseEngine.TextureID.TEXTURE9);
        recycleQuietly(ring);
        recycleQuietly(glow);
        recycleQuietly(flash);
        mIsResourceLoaded = true;
        Debugger.i(TAG, "resource loaded.");
        notifyResourceCallback();
        drainQueue();
        Debugger.i(TAG, "resource loaded thread ended");
    }

    private synchronized void notifyResourceCallback() {
        if (mCallbackDispatched) {
            return;
        }
        ResourceCallback callback = mResourceCallback;
        if (callback == null) {
            return;
        }
        mCallbackDispatched = true;
        boolean success = !mResourceLoadFailed;
        post(() -> callback.onResourcesLoaded(success));
    }

    private void drainQueue() {
        while (!mQueue.isEmpty()) {
            try {
                Runnable posted = mQueue.take();
                post(posted);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Bitmap openTexture(String assetName, String drawableName) {
        Bitmap decoded = decodeAsset(assetName + ".png");
        if (decoded == null) {
            decoded = decodeDrawable(getContext(), getContext().getPackageName(), drawableName);
        }
        if (decoded == null) {
            try {
                Context overlay = getContext().createPackageContext(OVERLAY_PACKAGE, 0);
                decoded = decodeDrawable(overlay, overlay.getPackageName(), drawableName);
            } catch (Exception ignored) {
                decoded = null;
            }
        }
        return asSoftwareRgba(decoded);
    }

    private Bitmap decodeAsset(String fileName) {
        try (InputStream in = getContext().getAssets().open(fileName)) {
            BitmapFactory.Options opts = softwareDecodeOptions();
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            Debugger.i(TAG, "asset " + fileName + " missing");
            return null;
        }
    }

    private static Bitmap decodeDrawable(Context context, String packageName, String drawableName) {
        int id = context.getResources().getIdentifier(drawableName, "drawable", packageName);
        if (id == 0) {
            return null;
        }
        return BitmapFactory.decodeResource(context.getResources(), id, softwareDecodeOptions());
    }

    /** Stock decodeStream path: ARGB_8888. Overlay decodeResource can otherwise return HARDWARE. */
    private static BitmapFactory.Options softwareDecodeOptions() {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inScaled = false;
        return opts;
    }

    private static Bitmap asSoftwareRgba(Bitmap src) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        if (src.getConfig() == Bitmap.Config.ARGB_8888) {
            return src;
        }
        Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
        if (copy != src) {
            src.recycle();
        }
        return copy;
    }

    private static void logBitmap(String name, Bitmap bitmap) {
        Debugger.i(TAG, name + " " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " cfg=" + bitmap.getConfig());
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private void applySurface() {
        if (mIsSurfaceDestroyed || mSurface == null) {
            return;
        }
        if (mWidth <= 0 || mHeight <= 0) {
            Debugger.i(TAG, "skip setSurface, size " + mWidth + "x" + mHeight);
            return;
        }
        Debugger.i(TAG, "setSurface " + mWidth + "x" + mHeight
                + " hw=" + isHardwareAccelerated());
        // Center is NDC (0.5, 0.5) in the .so; do not pass pixel coords.
        mInstance.setSurface(mSurface);
    }

    private void setSurfaceAsync() {
        if (mResourceLoadThread.isAlive()) {
            if (!mIsResourceLoaded) {
                Debugger.i(TAG, "add to run after resource loaded");
                mQueue.add(this::applySurface);
                Debugger.i(TAG, "setSurface queued");
                return;
            }
            try {
                Debugger.i(TAG, "wait for loading resource");
                mResourceLoadThread.join();
                applySurface();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        applySurface();
    }
}
