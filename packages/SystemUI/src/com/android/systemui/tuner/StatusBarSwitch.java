/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.Set;

public class StatusBarSwitch extends SwitchPreferenceCompat implements Tunable {

    /**
     * Live hide-list from Settings / TunerService. Null until first load.
     *
     * Preference restore can call {@link #persistBoolean} before {@link #onTuningChanged}.
     * Never seed an empty set — that would rewrite {@code icon_blacklist} to a single key
     * and wipe the user's other hidden icons. Load the current Secure value instead.
     */
    private Set<String> mHideList;

    public StatusBarSwitch(Context context, AttributeSet attrs) {
        super(context, attrs, androidx.preference.R.attr.switchPreferenceCompatStyle);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_HIDE_LIST);
        setupTheme();
    }

    @Override
    public void onDetached() {
        Dependency.get(TunerService.class).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            return;
        }
        mHideList = StatusBarIconController.getIconHideList(getContext(), newValue);
        setChecked(!mHideList.contains(getKey()));
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        Set<String> hideList = ensureHideList();
        if (!value) {
            // If not enabled add to hideList.
            if (!hideList.contains(getKey())) {
                MetricsLogger.action(getContext(), MetricsEvent.TUNER_STATUS_BAR_DISABLE,
                        getKey());
                hideList.add(getKey());
                setList(hideList);
            }
        } else {
            if (hideList.remove(getKey())) {
                MetricsLogger.action(getContext(), MetricsEvent.TUNER_STATUS_BAR_ENABLE, getKey());
                setList(hideList);
            }
        }
        return true;
    }

    /**
     * Returns the hide list, seeding from the current Secure setting if TunerService has not
     * delivered a value yet (e.g. preference restore → setChecked → persistBoolean).
     */
    private Set<String> ensureHideList() {
        if (mHideList == null) {
            ContentResolver cr = getContext().getContentResolver();
            String current = Settings.Secure.getStringForUser(
                    cr,
                    StatusBarIconController.ICON_HIDE_LIST,
                    ActivityManager.getCurrentUser());
            mHideList = StatusBarIconController.getIconHideList(getContext(), current);
        }
        return mHideList;
    }

    private void setupTheme() {
        Drawable icon = getIcon();
        if (icon != null) {
            TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
            int color = a.getColor(0, 0);
            a.recycle();
            Drawable wrappedIcon = DrawableCompat.wrap(icon);
            DrawableCompat.setTint(wrappedIcon, color);
            setIcon(wrappedIcon);
        }
    }

    private void setList(Set<String> hideList) {
        ContentResolver contentResolver = getContext().getContentResolver();
        Settings.Secure.putStringForUser(contentResolver, StatusBarIconController.ICON_HIDE_LIST,
                TextUtils.join(",", hideList), ActivityManager.getCurrentUser());
    }
}
