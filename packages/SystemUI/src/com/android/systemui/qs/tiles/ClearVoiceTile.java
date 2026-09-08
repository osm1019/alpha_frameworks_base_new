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

package com.android.systemui.qs.tiles;

import static com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN;

import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ClearVoiceStatusBarIconController;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/**
 * Quick Settings tile: Clear Voice (Oplus voice-call NC).
 *
 * <p>Toggles {@link ClearVoiceStatusBarIconController#SETTINGS_KEY_ENABLED} and
 * drives the same audio parameters / persist props as Settings.
 */
public class ClearVoiceTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "clear_voice";
    private static final String TAG = "ClearVoiceTile";

    private static final String OP_VOICE_CALL_NC = "op_voice_call_nc_enabled";
    private static final String VOIP_ENHANCE = "voip_enhance";

    private static final Intent CLEAR_VOICE_SETTINGS =
            new Intent()
                    .setComponent(
                            new ComponentName(
                                    "com.android.settings",
                                    "com.oplus.settings.clearvoice.ClearVoiceSettingsActivity"));

    private final UserSettingObserver mSetting;
    private final AudioManager mAudioManager;

    @Inject
    public ClearVoiceTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            UserTracker userTracker,
            SecureSettings secureSettings) {
        super(
                host,
                uiEventLogger,
                backgroundLooper,
                mainHandler,
                falsingManager,
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger);
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mSetting =
                new UserSettingObserver(
                        secureSettings,
                        mHandler,
                        ClearVoiceStatusBarIconController.SETTINGS_KEY_ENABLED,
                        userTracker.getUserId(),
                        /* defaultValue= */ 1) {
                    @Override
                    protected void handleValueChanged(int value, boolean observedChange) {
                        handleRefreshState(value);
                    }
                };
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mSetting.setListening(false);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        mSetting.setUserId(newUserId);
        handleRefreshState(mSetting.getValue());
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        final boolean next = !mState.value;
        mSetting.setValue(next ? 1 : 0);
        // Also mirror Global for Settings parity / older readers.
        try {
            Settings.Global.putInt(
                    mContext.getContentResolver(),
                    ClearVoiceStatusBarIconController.SETTINGS_KEY_ENABLED,
                    next ? 1 : 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Global.putInt failed", e);
        }
        applyBackend(next);
        refreshState(next ? 1 : 0);
    }

    @Override
    public Intent getLongClickIntent() {
        return CLEAR_VOICE_SETTINGS;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean enabled = value != 0;
        state.value = enabled;
        state.state = enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.label = mContext.getString(R.string.quick_settings_clear_voice_label);
        state.icon =
                maybeLoadResourceIcon(
                        enabled
                                ? R.drawable.ic_qs_clear_voice
                                : R.drawable.ic_qs_clear_voice_off);
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.contentDescription =
                mContext.getString(
                        enabled
                                ? R.string.accessibility_quick_settings_clear_voice_on
                                : R.string.accessibility_quick_settings_clear_voice_off);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_clear_voice_label);
    }

    @Override
    public int getMetricsCategory() {
        return VIEW_UNKNOWN;
    }

    private void applyBackend(boolean enabled) {
        if (mAudioManager != null) {
            final String boolVal = enabled ? "true" : "false";
            final String params =
                    OP_VOICE_CALL_NC + "=" + boolVal + ";" + VOIP_ENHANCE + "=" + boolVal;
            try {
                mAudioManager.setParameters(params);
            } catch (RuntimeException e) {
                Log.w(TAG, "setParameters failed", e);
            }
        }
        final String v = enabled ? "1" : "0";
        for (String prop :
                new String[] {
                    "persist.vendor.audio.op_voice_call_nc_enabled",
                    "persist.vendor.audio.voip_enhance",
                    "persist.vendor.audio.fluence.voicecall",
                }) {
            try {
                SystemProperties.set(prop, v);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
