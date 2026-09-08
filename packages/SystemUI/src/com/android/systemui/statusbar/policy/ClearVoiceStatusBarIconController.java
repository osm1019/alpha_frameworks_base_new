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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.telephony.TelephonyListenerManager;

import javax.inject.Inject;

/**
 * Shows the Clear Voice status-bar icon during calls when the feature is on
 * and the user has “Show Clear Voice icon during calls” enabled.
 *
 * <p>Reads Secure settings written by Settings
 * ({@code vendor/oplus/clearcalling}):
 * <ul>
 *   <li>{@code oplus_clear_voice_enabled}</li>
 *   <li>{@code oplus_clear_voice_status_icon}</li>
 * </ul>
 */
@SysUISingleton
public class ClearVoiceStatusBarIconController implements CoreStartable {

    private static final String TAG = "ClearVoiceStatusIcon";

    public static final String SETTINGS_KEY_ENABLED = "oplus_clear_voice_enabled";
    public static final String SETTINGS_KEY_STATUS_ICON = "oplus_clear_voice_status_icon";
    /** Pre-rename master key — still honored. */
    private static final String LEGACY_SETTINGS_KEY_ENABLED = "oplus_clear_calling_enabled";

    private final Context mContext;
    private final StatusBarIconController mIconController;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private final AudioManager mAudioManager;
    private final Handler mHandler;
    private final String mSlot;

    private int mCallState = TelephonyManager.CALL_STATE_IDLE;
    private int mAudioMode = AudioManager.MODE_NORMAL;
    private boolean mVisible;

    private final TelephonyCallback.CallStateListener mCallStateListener = this::onCallStateChanged;

    private final AudioManager.OnModeChangedListener mModeListener =
            (mode) -> {
                mAudioMode = mode;
                updateIconVisibility();
            };

    private final ContentObserver mSettingsObserver;

    @Inject
    public ClearVoiceStatusBarIconController(
            Context context,
            StatusBarIconController iconController,
            TelephonyListenerManager telephonyListenerManager,
            @Main Handler handler) {
        mContext = context;
        mIconController = iconController;
        mTelephonyListenerManager = telephonyListenerManager;
        mHandler = handler;
        mAudioManager = context.getSystemService(AudioManager.class);
        mSlot = context.getString(R.string.status_bar_clear_voice_slot);
        mSettingsObserver =
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        updateIconVisibility();
                    }
                };
    }

    @Override
    public void start() {
        mIconController.setIcon(
                mSlot,
                R.drawable.stat_sys_clear_voice,
                mContext.getString(R.string.accessibility_status_bar_clear_voice));
        mIconController.setIconVisibility(mSlot, false);

        mTelephonyListenerManager.addCallStateListener(mCallStateListener);

        if (mAudioManager != null) {
            mAudioMode = mAudioManager.getMode();
            mAudioManager.addOnModeChangedListener(mContext.getMainExecutor(), mModeListener);
        }

        final var cr = mContext.getContentResolver();
        cr.registerContentObserver(
                Settings.Secure.getUriFor(SETTINGS_KEY_ENABLED),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL);
        cr.registerContentObserver(
                Settings.Secure.getUriFor(SETTINGS_KEY_STATUS_ICON),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL);
        cr.registerContentObserver(
                Settings.Secure.getUriFor(LEGACY_SETTINGS_KEY_ENABLED),
                false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        updateIconVisibility();
        Log.i(TAG, "started slot=" + mSlot);
    }

    private void onCallStateChanged(int state) {
        mCallState = state;
        updateIconVisibility();
    }

    private void updateIconVisibility() {
        final boolean show = shouldShow();
        if (show == mVisible) {
            return;
        }
        mVisible = show;
        mIconController.setIconVisibility(mSlot, show);
        Log.d(TAG, "visibility=" + show
                + " callState=" + mCallState
                + " audioMode=" + mAudioMode);
    }

    private boolean shouldShow() {
        if (!isFeatureEnabled() || !isStatusIconPrefEnabled()) {
            return false;
        }
        return isInCall();
    }

    private boolean isInCall() {
        // Active call only (not ringing) — matches “during calls” copy.
        if (mCallState == TelephonyManager.CALL_STATE_OFFHOOK) {
            return true;
        }
        return mAudioMode == AudioManager.MODE_IN_CALL
                || mAudioMode == AudioManager.MODE_IN_COMMUNICATION;
    }

    private boolean isFeatureEnabled() {
        final int v =
                Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        SETTINGS_KEY_ENABLED,
                        -1,
                        UserHandle.USER_CURRENT);
        if (v == 0 || v == 1) {
            return v == 1;
        }
        final int legacy =
                Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        LEGACY_SETTINGS_KEY_ENABLED,
                        1,
                        UserHandle.USER_CURRENT);
        return legacy == 1;
    }

    private boolean isStatusIconPrefEnabled() {
        return Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        SETTINGS_KEY_STATUS_ICON,
                        1,
                        UserHandle.USER_CURRENT)
                == 1;
    }
}
