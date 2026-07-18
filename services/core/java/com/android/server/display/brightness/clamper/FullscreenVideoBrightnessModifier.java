/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static com.android.server.display.DisplayBrightnessState.CUSTOM_ANIMATION_RATE_NOT_SET;

import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.database.ContentObserver;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.SurfaceControlHdrLayerInfoListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.utils.DebugUtils;

import java.io.PrintWriter;

/**
 * Fullscreen HDR brightness pin.
 *
 * <p>While <b>fullscreen HDR content</b> is on-screen and the user has
 * {@link Settings.Secure#HDR_BRIGHTNESS_ENABLED} on (Enhanced HDR brightness), pin the
 * auto-brightness base to a coarse band from compensated ambient lux:
 * <ul>
 *   <li>lux &lt; 40 → 50% of user max (float 0.40)</li>
 *   <li>lux ≥ 50 and &lt; 300 → 80% of user max (float 0.64)</li>
 *   <li>lux ≥ 300 → 100% of user max (float 0.80)</li>
 *   <li>40–50: hysteresis dead-zone (keep previous band)</li>
 * </ul>
 * User max is the normal (non-HBM) ceiling: float {@link #USER_MAX_BRIGHTNESS} (0.8).
 *
 * <p><b>Detection</b>: SurfaceFlinger {@link SurfaceControlHdrLayerInfoListener} with
 * layer area ≥ {@link #MIN_LAYER_FRACTION} of the screen (aligned with HDR boost /
 * {@code minimumHdrPercentOfScreen}), Enhanced HDR brightness enabled, focused task
 * <b>not</b> freeform / PiP ({@code WINDOWING_MODE_PINNED}), and only on
 * {@link Display#DEFAULT_DISPLAY} (ignores freeform virtual displays).
 *
 * <p>Band changes only apply when lux moves by a large delta vs the lux used at
 * the last retarget. Retargets always ramp (never snap).
 *
 * <p>Gated by {@code persist.alpha.video_brightness_pin} (default true).
 *
 * <p>Registered before {@link HdrBrightnessModifier} so the HDR ratio multiplies this pin.
 *
 * <p><b>SDR video floor</b>: when SurfaceFlinger flags a fullscreen video-buffer layer
 * ({@link #HDR_INFO_FLAG_FULLSCREEN_VIDEO} on the same listener callback) and the HDR
 * pin is not active, the auto-brightness base is lifted to at least
 * {@link #SDR_VIDEO_FLOOR} (max(), never lowered) so dark-room SDR playback is not
 * reproduced too dim. Same gates as the pin (Enhanced HDR brightness setting,
 * auto-brightness, default display, not freeform/PiP). Camera previews count as video
 * by design.
 *
 * <p>Implements {@link BrightnessClamperController.StatefulModifier} so pin enter/exit
 * and band retargets notify DisplayPowerController (otherwise {@code apply()} never
 * re-runs and {@code mNeedRamp} stays stuck).
 *
 * <p>HDR listener register/unregister failures are swallowed so a bad display token
 * (e.g. freeform virtual display not yet known to SF) cannot crash system_server.
 */
public class FullscreenVideoBrightnessModifier implements BrightnessStateModifier,
        BrightnessClamperController.DisplayDeviceDataListener,
        BrightnessClamperController.StatefulModifier {

    private static final String TAG = "FullscreenVideoBrightness";
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    /** System prop to enable/disable (default on). */
    private static final String PROP_ENABLED = "persist.alpha.video_brightness_pin";

    /**
     * User / normal max (slider 100% before HBM). Matches DDC transitionPoint
     * and oplus normal-max 3349/4094 ≈ 0.8.
     */
    @VisibleForTesting
    static final float USER_MAX_BRIGHTNESS = 0.8f;

    @VisibleForTesting static final float PIN_DARK = 0.50f * USER_MAX_BRIGHTNESS;   // 0.40
    @VisibleForTesting static final float PIN_MID = 0.80f * USER_MAX_BRIGHTNESS;    // 0.64
    @VisibleForTesting static final float PIN_BRIGHT = 1.00f * USER_MAX_BRIGHTNESS; // 0.80

    /**
     * Auto-brightness floor while fullscreen SDR video plays (float 0.40 ≈ 175 nits).
     * Unlike the HDR pin this is a max(): bright rooms keep the (now content-immune)
     * auto value, dark rooms are lifted so video is not reproduced too dim.
     */
    @VisibleForTesting static final float SDR_VIDEO_FLOOR = 0.50f * USER_MAX_BRIGHTNESS; // 0.40

    /**
     * Mirror of {@code HdrLayerInfoReporter::HDR_INFO_FLAG_FULLSCREEN_VIDEO}
     * (frameworks/native): SurfaceFlinger sets it when a visible video-buffer layer
     * (video decoder or camera output) covers at least half of the display.
     */
    @VisibleForTesting static final int HDR_INFO_FLAG_FULLSCREEN_VIDEO = 1 << 30;

    @VisibleForTesting static final float LUX_DARK_MAX = 40f;
    @VisibleForTesting static final float LUX_MID_MIN = 50f;
    @VisibleForTesting static final float LUX_BRIGHT_MIN = 300f;

    /** Minimum |Δlux| vs last applied lux required to retarget band. */
    @VisibleForTesting static final float LARGE_LUX_DELTA = 50f;

    /** Min HDR layer area fraction (fullscreen-ish content). */
    @VisibleForTesting static final float MIN_LAYER_FRACTION = 0.5f;

    /** Smooth ramps (brightness float units per second). */
    @VisibleForTesting static final float RAMP_RATE_UP = 0.12f;
    @VisibleForTesting static final float RAMP_RATE_DOWN = 0.08f;

    private static final float INVALID_LUX = -1f;

    /** Min interval between getFocusedRootTaskInfo binder calls. */
    private static final long FOCUS_CHECK_MIN_INTERVAL_MS = 500L;

    private final Handler mHandler;
    private final BrightnessClamperController.ClamperChangeListener mListener;
    private final ContentResolver mContentResolver;
    private final Injector mInjector;

    private final SurfaceControlHdrLayerInfoListener mHdrListener =
            new SurfaceControlHdrLayerInfoListener() {
                @Override
                public void onHdrInfoChanged(IBinder displayToken, int numberOfHdrLayers,
                        int maxW, int maxH, int flags, float maxDesiredHdrSdrRatio) {
                    final float area = numberOfHdrLayers > 0 ? (float) maxW * maxH : 0f;
                    final boolean videoFullscreen =
                            (flags & HDR_INFO_FLAG_FULLSCREEN_VIDEO) != 0;
                    // Capture token so a post after unregister cannot re-arm the pin.
                    final IBinder token = displayToken;
                    mHandler.post(() -> onHdrLayerAreaChanged(token, area, videoFullscreen));
                }
            };

    /** Observes HDR enhancement + auto-brightness mode (initialized after mHandler). */
    private final ContentObserver mSettingsObserver;

    private boolean mEnabled;
    /** True after start() registered settings observers; false after stop(). */
    private boolean mStarted;
    /** Latched when stop() runs so a queued start() is a no-op. */
    private boolean mStopped;
    private IBinder mDisplayToken;
    /** Logical display this modifier is bound to; pin only on DEFAULT_DISPLAY. */
    private int mDisplayId = Display.DEFAULT_DISPLAY;
    private float mScreenArea = 1f;

    /** SF reports a large HDR layer (≥ {@link #MIN_LAYER_FRACTION} of this display). */
    private boolean mHdrFullscreen;
    /** SF reports a fullscreen video-buffer layer (SDR or HDR; flags bit). */
    private boolean mVideoFullscreen;
    /** SDR video floor engaged (fullscreen video, no HDR pin). */
    private boolean mFloorActive;
    /** One-shot ramp for the next floor engage. */
    private boolean mFloorNeedRamp;
    /** Settings.Secure.HDR_BRIGHTNESS_ENABLED (Enhanced HDR brightness). */
    private boolean mHdrBrightnessSettingEnabled = true;
    /** Cached Settings.System.SCREEN_BRIGHTNESS_MODE == AUTOMATIC. */
    private boolean mAutoBrightnessEnabled;
    /** Pin active: HDR fullscreen content AND enhancement setting on. */
    private boolean mVideoActive;

    private float mAmbientLux = INVALID_LUX;
    private Band mLastBand = Band.NONE;
    private float mLastAppliedLux = INVALID_LUX;
    private float mPinnedBrightness = PowerManager.BRIGHTNESS_INVALID;
    private float mPendingRampRate = CUSTOM_ANIMATION_RATE_NOT_SET;
    private boolean mNeedRamp;

    private long mLastFocusCheckUptimeMs;
    private boolean mLastFocusCompatible = true;

    enum Band {
        NONE, DARK, MID, BRIGHT
    }

    FullscreenVideoBrightnessModifier(Handler handler, Context context,
            BrightnessClamperController.ClamperChangeListener listener,
            BrightnessClamperController.DisplayDeviceData data) {
        this(handler, context, listener, data, new Injector());
    }

    @VisibleForTesting
    FullscreenVideoBrightnessModifier(Handler handler, Context context,
            BrightnessClamperController.ClamperChangeListener listener,
            BrightnessClamperController.DisplayDeviceData data, Injector injector) {
        mHandler = handler;
        mSettingsObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                onSettingsChanged(uri);
            }
        };
        mListener = listener;
        mInjector = injector;
        mContentResolver = context.getContentResolver();
        mEnabled = SystemProperties.getBoolean(PROP_ENABLED, true);
        mHandler.post(() -> onDisplayChanged(data));
        if (mEnabled) {
            mHandler.post(this::start);
        }
    }

    private void start() {
        if (mStopped || mStarted || !mEnabled) {
            return;
        }
        mStarted = true;
        mHdrBrightnessSettingEnabled = mInjector.isHdrBrightnessEnabled(mContentResolver);
        mAutoBrightnessEnabled = mInjector.isAutoBrightnessEnabled(mContentResolver);
        mInjector.registerSettingsObserver(mContentResolver, mSettingsObserver);
        Slog.i(TAG, "started (HDR pin, userMax=" + USER_MAX_BRIGHTNESS
                + " pins=" + PIN_DARK + "/" + PIN_MID + "/" + PIN_BRIGHT
                + " hdrSetting=" + mHdrBrightnessSettingEnabled
                + " autoBrt=" + mAutoBrightnessEnabled + ")");
    }

    @Override
    public void onDisplayChanged(BrightnessClamperController.DisplayDeviceData displayData) {
        if (mStopped) {
            return;
        }
        mDisplayId = displayData.mDisplayId;
        mScreenArea = Math.max(1f, (float) displayData.mWidth * displayData.mHeight);
        // Freeform (LMO) and other virtual displays must not register an HDR listener:
        // SF returns NAME_NOT_FOUND for unknown tokens and that used to kill system_server.
        // Pin is only meaningful on the default physical display.
        if (mDisplayId != Display.DEFAULT_DISPLAY) {
            if (DEBUG) {
                Slog.d(TAG, "skip HDR listener on non-default displayId=" + mDisplayId);
            }
            clearHdrListenerState(/* notify= */ true);
            return;
        }
        registerHdrListener(displayData.mDisplayToken);
    }

    private void registerHdrListener(IBinder token) {
        if (mDisplayToken == token) {
            return;
        }
        unregisterHdrListener();
        if (token == null || !mEnabled || mStopped) {
            return;
        }
        try {
            mInjector.registerHdrListener(mHdrListener, token);
            mDisplayToken = token;
        } catch (RuntimeException e) {
            // IllegalStateException from nRegister (NAME_NOT_FOUND, etc.) and any other
            // unexpected failure: leave a clean unregistered state, never crash PMS.
            Slog.w(TAG, "HDR listener register failed (display not ready?); pin disabled", e);
            clearHdrListenerState(/* notify= */ true);
        }
    }

    private void unregisterHdrListener() {
        if (mDisplayToken == null) {
            return;
        }
        final IBinder token = mDisplayToken;
        mDisplayToken = null;
        try {
            mInjector.unregisterHdrListener(mHdrListener, token);
        } catch (RuntimeException e) {
            Slog.w(TAG, "HDR listener unregister failed", e);
        }
        mHdrFullscreen = false;
        mVideoFullscreen = false;
        updatePinActive();
    }

    /**
     * Drop any registered listener / pin state without throwing. Used when the
     * display is not eligible or registration fails.
     */
    private void clearHdrListenerState(boolean notify) {
        if (mDisplayToken != null) {
            final IBinder token = mDisplayToken;
            mDisplayToken = null;
            try {
                mInjector.unregisterHdrListener(mHdrListener, token);
            } catch (RuntimeException e) {
                Slog.w(TAG, "HDR listener cleanup unregister failed", e);
            }
        }
        mHdrFullscreen = false;
        mVideoFullscreen = false;
        if (notify) {
            updatePinActive();
        } else {
            // Force pin + floor off without relying on HDR flags alone.
            setPinActive(false);
            setFloorActive(false);
        }
    }

    private void onHdrLayerAreaChanged(IBinder displayToken, float area,
            boolean videoFullscreen) {
        // Drop events for a previous display token or after unregister/stop.
        if (mStopped || mDisplayToken == null || mDisplayToken != displayToken) {
            return;
        }
        boolean areaFullscreen = (area / mScreenArea) >= MIN_LAYER_FRACTION;
        if (areaFullscreen != mHdrFullscreen) {
            mHdrFullscreen = areaFullscreen;
            if (DEBUG) {
                Slog.d(TAG, "HDR layer area fullscreen=" + areaFullscreen
                        + " areaFrac=" + (area / mScreenArea));
            }
        }
        if (videoFullscreen != mVideoFullscreen) {
            mVideoFullscreen = videoFullscreen;
            if (DEBUG) {
                Slog.d(TAG, "video layer fullscreen=" + videoFullscreen);
            }
        }
        // Always re-evaluate pin: freeform/PiP focus can change without a new HDR area.
        updatePinActive();
    }

    private void onSettingsChanged(Uri uri) {
        if (mStopped || !mStarted) {
            return;
        }
        boolean pinDirty = false;
        if (uri == null
                || Settings.Secure.getUriFor(Settings.Secure.HDR_BRIGHTNESS_ENABLED).equals(uri)) {
            boolean enabled = mInjector.isHdrBrightnessEnabled(mContentResolver);
            if (enabled != mHdrBrightnessSettingEnabled) {
                mHdrBrightnessSettingEnabled = enabled;
                if (DEBUG) {
                    Slog.d(TAG, "HDR_BRIGHTNESS_ENABLED=" + enabled);
                }
                pinDirty = true;
            }
        }
        if (uri == null
                || Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE).equals(uri)) {
            boolean auto = mInjector.isAutoBrightnessEnabled(mContentResolver);
            if (auto != mAutoBrightnessEnabled) {
                mAutoBrightnessEnabled = auto;
                if (DEBUG) {
                    Slog.d(TAG, "auto brightness=" + auto);
                }
                // apply() reads the cache next frame; notify so pin drops immediately on manual.
                mListener.onChanged();
            }
        }
        if (pinDirty) {
            updatePinActive();
        }
    }

    /**
     * Pin when Enhanced HDR brightness is on, SF reports a large HDR layer on the
     * default display, and the focused task is not freeform / PiP. The SDR floor
     * engages under the same gates whenever SF reports a fullscreen video layer
     * and the HDR pin is not active (the pin owns the base when both apply).
     */
    private void updatePinActive() {
        setPinActive(shouldPin());
        setFloorActive(shouldFloor());
    }

    private boolean shouldFloor() {
        if (!mEnabled || mStopped || !mHdrBrightnessSettingEnabled || !mVideoFullscreen) {
            return false;
        }
        if (mVideoActive) {
            return false; // HDR pin owns the base.
        }
        if (mDisplayId != Display.DEFAULT_DISPLAY) {
            return false;
        }
        return isFocusedTaskFullscreenCompatibleCached();
    }

    private void setFloorActive(boolean active) {
        if (active == mFloorActive) {
            return;
        }
        mFloorActive = active;
        mFloorNeedRamp = active;
        Slog.i(TAG, "SDR video floor " + (active ? "ON (" + SDR_VIDEO_FLOOR + ")" : "OFF"));
        mListener.onChanged();
    }

    private boolean shouldPin() {
        if (!mEnabled || mStopped || !mHdrBrightnessSettingEnabled || !mHdrFullscreen) {
            return false;
        }
        if (mDisplayId != Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (!isFocusedTaskFullscreenCompatibleCached()) {
            if (DEBUG) {
                Slog.d(TAG, "skip pin: focused task is freeform or PiP");
            }
            return false;
        }
        return true;
    }

    /** Throttled focus check (binder). Fail closed only on the actual injector call. */
    private boolean isFocusedTaskFullscreenCompatibleCached() {
        final long now = SystemClock.uptimeMillis();
        if (now - mLastFocusCheckUptimeMs >= FOCUS_CHECK_MIN_INTERVAL_MS) {
            mLastFocusCompatible = mInjector.isFocusedTaskFullscreenCompatible();
            mLastFocusCheckUptimeMs = now;
        }
        return mLastFocusCompatible;
    }

    private void setPinActive(boolean active) {
        if (active == mVideoActive) {
            return;
        }
        mVideoActive = active;
        if (active) {
            // Enter: apply immediately from current lux (one-shot), then ramp.
            retargetFromLux(/* force= */ true);
        } else {
            // Exit: stop pinning; ABC resumes.
            mLastBand = Band.NONE;
            mLastAppliedLux = INVALID_LUX;
            mPinnedBrightness = PowerManager.BRIGHTNESS_INVALID;
            mPendingRampRate = CUSTOM_ANIMATION_RATE_NOT_SET;
            mNeedRamp = false;
            if (DEBUG) {
                Slog.i(TAG, "HDR pin OFF");
            }
        }
        mListener.onChanged();
    }

    @Override
    public boolean shouldListenToLightSensor() {
        return mEnabled && !mStopped;
    }

    @Override
    public void setAmbientLux(float lux) {
        if (!mEnabled || mStopped) {
            return;
        }
        mAmbientLux = lux;
        // Drop pin/floor if focus moved to freeform/PiP without a new HDR area event.
        if (mHdrFullscreen || mVideoActive || mVideoFullscreen || mFloorActive) {
            updatePinActive();
        }
        if (mVideoActive) {
            retargetFromLux(/* force= */ false);
        }
    }

    /**
     * Map lux → band with 40–50 dead-zone hysteresis, then retarget only on
     * large lux delta (or force on pin enter).
     */
    private void retargetFromLux(boolean force) {
        if (mAmbientLux < 0f && !force) {
            return;
        }
        Band candidate = bandFromLux(mAmbientLux, mLastBand);
        if (candidate == Band.NONE) {
            return;
        }
        if (!force && candidate == mLastBand) {
            return;
        }
        if (!force && mLastAppliedLux >= 0f
                && Math.abs(mAmbientLux - mLastAppliedLux) < LARGE_LUX_DELTA) {
            if (DEBUG) {
                Slog.d(TAG, "hold band=" + mLastBand + " lux=" + mAmbientLux
                        + " lastApplied=" + mLastAppliedLux
                        + " (delta < " + LARGE_LUX_DELTA + ")");
            }
            return;
        }

        float target = pinForBand(candidate);
        float prev = mPinnedBrightness;
        boolean goingDown = prev > 0f && target < prev;

        mLastBand = candidate;
        mLastAppliedLux = mAmbientLux;
        mPinnedBrightness = target;
        mPendingRampRate = goingDown ? RAMP_RATE_DOWN : RAMP_RATE_UP;
        mNeedRamp = true;

        Slog.i(TAG, "retarget band=" + candidate + " lux=" + mAmbientLux
                + " pin=" + target + " ramp=" + mPendingRampRate
                + (force ? " (enter)" : ""));
        mListener.onChanged();
    }

    @VisibleForTesting
    static Band bandFromLux(float lux, Band previous) {
        if (lux < 0f) {
            return previous != Band.NONE ? previous : Band.DARK;
        }
        if (lux < LUX_DARK_MAX) {
            return Band.DARK;
        }
        if (lux >= LUX_BRIGHT_MIN) {
            return Band.BRIGHT;
        }
        if (lux >= LUX_MID_MIN) {
            return Band.MID;
        }
        // 40 ≤ lux < 50: hysteresis dead-zone — keep previous band.
        if (previous != Band.NONE) {
            return previous;
        }
        return Band.MID;
    }

    @VisibleForTesting
    static float pinForBand(Band band) {
        switch (band) {
            case DARK:
                return PIN_DARK;
            case MID:
                return PIN_MID;
            case BRIGHT:
                return PIN_BRIGHT;
            default:
                return PowerManager.BRIGHTNESS_INVALID;
        }
    }

    @Override
    public void apply(DisplayManagerInternal.DisplayPowerRequest request,
            DisplayBrightnessState.Builder stateBuilder) {
        final boolean pinWanted = mVideoActive && mPinnedBrightness >= 0f;
        final boolean floorWanted = mFloorActive;
        // Last-line gate: never pin/floor freeform/PiP even if state is briefly stale.
        if (!mEnabled || mStopped || (!pinWanted && !floorWanted)
                || !isFocusedTaskFullscreenCompatibleCached()
                || mDisplayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        // Cached auto-brightness (no Settings IPC on the display path).
        if (!mAutoBrightnessEnabled) {
            return;
        }
        if (request != null
                && !Float.isNaN(request.screenBrightnessOverride)
                && request.screenBrightnessOverride >= 0f) {
            return;
        }
        if (pinWanted) {
            // Force the brightness base to the pin; HdrBrightnessModifier multiplies later.
            stateBuilder.setBrightness(mPinnedBrightness);
            stateBuilder.getBrightnessReason().addModifier(BrightnessReason.MODIFIER_VIDEO_PIN);
            if (mNeedRamp) {
                stateBuilder.setCustomAnimationRate(mPendingRampRate);
                stateBuilder.setIsSlowChange(true);
                mNeedRamp = false;
                mPendingRampRate = CUSTOM_ANIMATION_RATE_NOT_SET;
            }
            return;
        }
        // SDR video floor: lift the (content-immune) auto base, never lower it.
        if (stateBuilder.getBrightness() < SDR_VIDEO_FLOOR) {
            stateBuilder.setBrightness(SDR_VIDEO_FLOOR);
            stateBuilder.getBrightnessReason().addModifier(BrightnessReason.MODIFIER_VIDEO_PIN);
            if (mFloorNeedRamp) {
                stateBuilder.setCustomAnimationRate(RAMP_RATE_UP);
                stateBuilder.setIsSlowChange(true);
                mFloorNeedRamp = false;
            }
        }
    }

    /**
     * Surface pin active/target into aggregated state so clamper controller notifies
     * DisplayPowerController when the pin changes (enter/exit/reband). Without this,
     * only HDR/thermal aggregated fields were compared and pin retargets never applied.
     */
    @Override
    public void applyStateChange(
            BrightnessClamperController.ModifiersAggregatedState aggregatedState) {
        final boolean commonLive = mEnabled && !mStopped
                && mDisplayId == Display.DEFAULT_DISPLAY
                && isFocusedTaskFullscreenCompatibleCached();
        final boolean pinLive = commonLive && mVideoActive && mPinnedBrightness >= 0f;
        final boolean floorLive = commonLive && !pinLive && mFloorActive;
        if (pinLive || floorLive) {
            aggregatedState.mVideoPinActive = true;
            aggregatedState.mVideoPinBrightness = pinLive ? mPinnedBrightness : SDR_VIDEO_FLOOR;
        } else {
            aggregatedState.mVideoPinActive = false;
            aggregatedState.mVideoPinBrightness = PowerManager.BRIGHTNESS_INVALID;
        }
    }

    @Override
    public void stop() {
        mStopped = true;
        if (mStarted) {
            try {
                mInjector.unregisterSettingsObserver(mContentResolver, mSettingsObserver);
            } catch (RuntimeException e) {
                Slog.w(TAG, "settings observer unregister failed", e);
            }
            mStarted = false;
        }
        clearHdrListenerState(/* notify= */ false);
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("FullscreenVideoBrightnessModifier:");
        pw.println("  mEnabled=" + mEnabled + " mStarted=" + mStarted + " mStopped=" + mStopped);
        pw.println("  mDisplayId=" + mDisplayId);
        pw.println("  mVideoActive=" + mVideoActive
                + " (hdrFs=" + mHdrFullscreen
                + " hdrSetting=" + mHdrBrightnessSettingEnabled
                + " autoBrt=" + mAutoBrightnessEnabled
                + " focusOk=" + isFocusedTaskFullscreenCompatibleCached() + ")");
        pw.println("  mFloorActive=" + mFloorActive
                + " (videoFs=" + mVideoFullscreen + " floor=" + SDR_VIDEO_FLOOR + ")");
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mLastBand=" + mLastBand);
        pw.println("  mLastAppliedLux=" + mLastAppliedLux);
        pw.println("  mPinnedBrightness=" + mPinnedBrightness);
        pw.println("  mNeedRamp=" + mNeedRamp);
        pw.println("  pins dark/mid/bright="
                + PIN_DARK + "/" + PIN_MID + "/" + PIN_BRIGHT);
        pw.println("  LARGE_LUX_DELTA=" + LARGE_LUX_DELTA);
        pw.println("  USER_MAX=" + USER_MAX_BRIGHTNESS);
        pw.println("  detect=DEFAULT_DISPLAY + HDR_layer>=50% (pin) / SF video flag (floor)"
                + " + not freeform/PiP + HDR_BRIGHTNESS_ENABLED");
    }

    @VisibleForTesting
    static class Injector {
        void registerHdrListener(SurfaceControlHdrLayerInfoListener listener, IBinder token) {
            listener.register(token);
        }

        void unregisterHdrListener(SurfaceControlHdrLayerInfoListener listener, IBinder token) {
            listener.unregister(token);
        }

        /**
         * True when the focused root task is eligible for the HDR pin.
         * Freeform and PiP ({@code WINDOWING_MODE_PINNED}) must never arm the pin.
         * If focus cannot be read, refuse to pin (fail closed).
         */
        boolean isFocusedTaskFullscreenCompatible() {
            try {
                final RootTaskInfo info =
                        ActivityTaskManager.getService().getFocusedRootTaskInfo();
                if (info == null) {
                    return false;
                }
                final int mode = info.getWindowingMode();
                // Freeform and PiP must never arm the HDR brightness pin.
                return mode != WINDOWING_MODE_FREEFORM && mode != WINDOWING_MODE_PINNED;
            } catch (RemoteException e) {
                Slog.w(TAG, "getFocusedRootTaskInfo failed; refusing HDR pin", e);
                return false;
            } catch (RuntimeException e) {
                Slog.w(TAG, "focus windowing mode check failed; refusing HDR pin", e);
                return false;
            }
        }

        boolean isHdrBrightnessEnabled(ContentResolver cr) {
            return Settings.Secure.getIntForUser(cr,
                    Settings.Secure.HDR_BRIGHTNESS_ENABLED, /* def= */ 1,
                    UserHandle.USER_CURRENT) != 0;
        }

        boolean isAutoBrightnessEnabled(ContentResolver cr) {
            return Settings.System.getIntForUser(cr,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT)
                    == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        }

        void registerSettingsObserver(ContentResolver cr, ContentObserver observer) {
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.HDR_BRIGHTNESS_ENABLED),
                    /* notifyForDescendants= */ false,
                    observer,
                    UserHandle.USER_ALL);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    /* notifyForDescendants= */ false,
                    observer,
                    UserHandle.USER_ALL);
        }

        void unregisterSettingsObserver(ContentResolver cr, ContentObserver observer) {
            cr.unregisterContentObserver(observer);
        }
    }
}
