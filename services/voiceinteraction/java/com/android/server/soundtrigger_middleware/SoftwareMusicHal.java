/*
 * Copyright (C) 2026 AlphaDroid
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.database.ContentObserver;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.SoundModelType;
import android.media.soundtrigger_middleware.RecognitionEventSys;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Serves the ambient music model in software, forwarding every other model to the real HAL.
 *
 * <p>The model this intercepts is a TFLite blob built for a specific vendor's DSP, and no other
 * DSP will load it. The recognition it drives is not the DSP's either: the ambient music app opens
 * the mic itself once triggered and matches against its own catalogue on the CPU. All the sound
 * trigger layer ever contributes is the moment - so serving that moment here costs nothing the DSP
 * was providing, and works on any device with a microphone.
 *
 * <p>No detection happens here or anywhere else on such a device. Waking the app on a timer means
 * it also listens to empty rooms, which a real always-on model would have skipped. The gate below
 * is what keeps that bounded: it is user consent, and nothing listens without it.
 *
 * <p>That consent is read here, from the Secure setting, because this is what owns the
 * microphone. It used to arrive as a system property written by the app that renders the
 * results, which made a display surface the thing deciding whether the mic ran -- its teardown
 * disarmed recognition, and nothing re-armed it if that surface never came back.
 */
class SoftwareMusicHal implements ISoundTriggerHal {
    private static final String TAG = "SoftwareMusicHal";

    /** Pixel Now Playing music_detector. Its own DSP is the only one that can load the blob. */
    private static final String MUSIC_MODEL_VENDOR_UUID =
            "9f6ad62a-1f0b-11e7-87c5-40a8f03d3f15";

    /** User consent, read straight from the setting; nothing listens while this is false. */
    private static final String GATE_SETTING = "now_playing_enabled";

    private static final String POLL_INTERVAL_PROP = "sys.now_playing.poll_ms";
    private static final int DEFAULT_POLL_INTERVAL_MILLIS = 30_000;
    private static final int MIN_POLL_INTERVAL_MILLIS = 10_000;

    /** Kept clear of the vendor's handle space, which counts up from zero. */
    private static final int HANDLE_BASE = 0x10000000;

    private final @NonNull ISoundTriggerHal mDelegate;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<Integer, Model> mModels = new HashMap<>();

    @GuardedBy("mLock")
    private int mNextHandle = HANDLE_BASE;

    @GuardedBy("mLock")
    private ScheduledExecutorService mExecutor;

    @GuardedBy("mLock")
    private ContentObserver mGateObserver;

    @GuardedBy("mLock")
    private boolean mGateArmed;

    /**
     * The app unloads and reloads the model around every recognition, so each cycle arrives as a
     * fresh session. Tracking the last trigger across all of them is what holds the cadence
     * steady; per-session state would restart the interval every few seconds.
     */
    @GuardedBy("mLock")
    private long mLastTriggerElapsed = -1;
    private final @NonNull Context mContext;

    private static final class Model {
        final @NonNull ModelCallback callback;
        boolean started;
        boolean captureRequested;
        @Nullable ScheduledFuture<?> future;

        Model(@NonNull ModelCallback callback) {
            this.callback = callback;
        }
    }

    SoftwareMusicHal(@NonNull Context context, @NonNull ISoundTriggerHal delegate) {
        mContext = context;
        mDelegate = delegate;
    }

    private static boolean isMusicModel(@Nullable SoundModel model) {
        return model != null && MUSIC_MODEL_VENDOR_UUID.equalsIgnoreCase(model.vendorUuid);
    }

    private static int pollIntervalMillis() {
        return Math.max(MIN_POLL_INTERVAL_MILLIS,
                SystemProperties.getInt(POLL_INTERVAL_PROP, DEFAULT_POLL_INTERVAL_MILLIS));
    }

    private boolean gateArmed() {
        return mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_supportsBackgroundMusicRecognition)
                && Settings.Secure.getIntForUser(mContext.getContentResolver(), GATE_SETTING, 1,
                        UserHandle.USER_CURRENT) != 0;
    }

    // -- Interception --------------------------------------------------------------------------

    @Override
    public int loadSoundModel(SoundModel soundModel, ModelCallback callback) {
        if (!isMusicModel(soundModel)) {
            return mDelegate.loadSoundModel(soundModel, callback);
        }
        // Nothing below us will read the blob, and the descriptor is ours once we stop forwarding.
        if (soundModel.data != null) {
            try {
                soundModel.data.close();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to close unused model data", e);
            }
            soundModel.data = null;
        }
        synchronized (mLock) {
            final int handle = mNextHandle++;
            mModels.put(handle, new Model(callback));
            ensureGateWatchedLocked();
            Slog.i(TAG, "Serving ambient music model in software, handle " + handle);
            return handle;
        }
    }

    @Override
    public void startRecognition(int modelHandle, int deviceHandle, int ioHandle,
            RecognitionConfig config) {
        synchronized (mLock) {
            final Model model = mModels.get(modelHandle);
            if (model != null) {
                model.started = true;
                model.captureRequested = config != null && config.captureRequested;
                mGateArmed = gateArmed();
                scheduleLocked(modelHandle, model);
                return;
            }
        }
        mDelegate.startRecognition(modelHandle, deviceHandle, ioHandle, config);
    }

    @Override
    public void stopRecognition(int modelHandle) {
        synchronized (mLock) {
            final Model model = mModels.get(modelHandle);
            if (model != null) {
                model.started = false;
                cancelLocked(model);
                return;
            }
        }
        mDelegate.stopRecognition(modelHandle);
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        synchronized (mLock) {
            final Model model = mModels.remove(modelHandle);
            if (model != null) {
                model.started = false;
                cancelLocked(model);
                return;
            }
        }
        mDelegate.unloadSoundModel(modelHandle);
    }

    @Override
    public void forceRecognitionEvent(int modelHandle) {
        if (!isSoftware(modelHandle)) {
            mDelegate.forceRecognitionEvent(modelHandle);
            return;
        }
        // The app polls this every few seconds as a liveness check, so honouring it literally
        // would open the mic at that rate. Honouring it no more often than the poll interval
        // costs nothing extra and makes the poke a recovery path: if a session ever loses its
        // timer, the app's own liveness check is what starts it listening again.
        synchronized (mLock) {
            final Model model = mModels.get(modelHandle);
            if (model == null || !model.started || !mGateArmed) {
                Slog.v(TAG, "Ignoring forced recognition on idle handle " + modelHandle);
                return;
            }
            if (mLastTriggerElapsed >= 0
                    && SystemClock.elapsedRealtime() - mLastTriggerElapsed
                            < pollIntervalMillis()) {
                return;
            }
            cancelLocked(model);
        }
        trigger(modelHandle);
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        // Null reports the parameter as unsupported, which is true of every model we serve.
        return isSoftware(modelHandle) ? null : mDelegate.queryParameter(modelHandle, param);
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        return isSoftware(modelHandle) ? 0 : mDelegate.getModelParameter(modelHandle, param);
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        if (isSoftware(modelHandle)) {
            return;
        }
        mDelegate.setModelParameter(modelHandle, param, value);
    }

    private boolean isSoftware(int modelHandle) {
        synchronized (mLock) {
            return mModels.containsKey(modelHandle);
        }
    }

    // -- Triggering ----------------------------------------------------------------------------

    @GuardedBy("mLock")
    private void scheduleLocked(int modelHandle, @NonNull Model model) {
        cancelLocked(model);
        if (!model.started || !mGateArmed) {
            return;
        }
        if (mExecutor == null) {
            mExecutor = Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "SoftwareMusicHal"));
        }
        final int interval = pollIntervalMillis();
        // A session that has never triggered has no cadence to hold yet, so it starts listening
        // now rather than sitting through a full interval before the first recognition.
        long delay = 0;
        if (mLastTriggerElapsed >= 0) {
            delay = Math.max(0, interval - (SystemClock.elapsedRealtime() - mLastTriggerElapsed));
        }
        model.future = mExecutor.schedule(
                () -> trigger(modelHandle), delay, TimeUnit.MILLISECONDS);
    }

    @GuardedBy("mLock")
    private void cancelLocked(@NonNull Model model) {
        if (model.future != null) {
            model.future.cancel(false);
            model.future = null;
        }
    }

    private void trigger(int modelHandle) {
        final ModelCallback callback;
        final boolean captureRequested;
        synchronized (mLock) {
            final Model model = mModels.get(modelHandle);
            if (model == null || !model.started || !mGateArmed) {
                return;
            }
            model.future = null;
            callback = model.callback;
            captureRequested = model.captureRequested;
            mLastTriggerElapsed = SystemClock.elapsedRealtime();
        }

        final RecognitionEvent event = new RecognitionEvent();
        event.status = RecognitionStatus.SUCCESS;
        event.type = SoundModelType.GENERIC;
        event.captureAvailable = captureRequested;
        event.captureDelayMs = 0;
        event.capturePreambleMs = 0;
        event.triggerInData = false;
        // We buffered nothing to describe; the client records its own audio after this returns.
        event.audioConfig = null;
        event.data = new byte[0];
        // The client reloads and restarts around every cycle, so each trigger ends its session.
        event.recognitionStillActive = false;

        final RecognitionEventSys eventSys = new RecognitionEventSys();
        eventSys.recognitionEvent = event;
        eventSys.halEventReceivedMillis = SystemClock.elapsedRealtimeNanos();

        Slog.i(TAG, "Triggering ambient music recognition, handle " + modelHandle);
        callback.recognitionCallback(modelHandle, eventSys);
    }

    // -- Gate ----------------------------------------------------------------------------------

    @GuardedBy("mLock")
    private void ensureGateWatchedLocked() {
        if (mGateObserver != null) {
            return;
        }
        mGateArmed = gateArmed();
        mGateObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                synchronized (mLock) {
                    final boolean armed = gateArmed();
                    if (armed == mGateArmed) {
                        return;
                    }
                    mGateArmed = armed;
                    Slog.i(TAG, "Ambient music gate " + (armed ? "armed" : "disarmed"));
                    for (Map.Entry<Integer, Model> entry : mModels.entrySet()) {
                        if (armed) {
                            scheduleLocked(entry.getKey(), entry.getValue());
                        } else {
                            cancelLocked(entry.getValue());
                        }
                    }
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(GATE_SETTING), false, mGateObserver,
                UserHandle.USER_ALL);
    }

    @GuardedBy("mLock")
    private void releaseGateWatchLocked() {
        if (mGateObserver == null) {
            return;
        }
        mContext.getContentResolver().unregisterContentObserver(mGateObserver);
        mGateObserver = null;
        if (mExecutor != null) {
            mExecutor.shutdown();
            mExecutor = null;
        }
    }

    // -- Pass-through --------------------------------------------------------------------------

    @Override
    public Properties getProperties() {
        return mDelegate.getProperties();
    }

    @Override
    public void registerCallback(GlobalCallback callback) {
        mDelegate.registerCallback(callback);
    }

    @Override
    public int loadPhraseSoundModel(PhraseSoundModel soundModel, ModelCallback callback) {
        return mDelegate.loadPhraseSoundModel(soundModel, callback);
    }

    @Override
    public String interfaceDescriptor() {
        return mDelegate.interfaceDescriptor();
    }

    @Override
    public void linkToDeath(IBinder.DeathRecipient recipient) {
        mDelegate.linkToDeath(recipient);
    }

    @Override
    public void unlinkToDeath(IBinder.DeathRecipient recipient) {
        mDelegate.unlinkToDeath(recipient);
    }

    @Override
    public void flushCallbacks() {
        mDelegate.flushCallbacks();
    }

    @Override
    public void clientAttached(IBinder token) {
        mDelegate.clientAttached(token);
    }

    @Override
    public void clientDetached(IBinder token) {
        mDelegate.clientDetached(token);
    }

    @Override
    public void reboot() {
        mDelegate.reboot();
    }

    @Override
    public void detach() {
        synchronized (mLock) {
            for (Model model : mModels.values()) {
                model.started = false;
                cancelLocked(model);
            }
            mModels.clear();
            releaseGateWatchLocked();
        }
        mDelegate.detach();
    }
}
