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
import android.content.Context;

/**
 * Wraps another factory's HAL in {@link SoftwareMusicHal}.
 *
 * <p>This decorates rather than adding a module of its own because clients reach a module through
 * {@code SoundTriggerManager}, which skips anything reporting {@code FAKE_HAL_ARCH} and attaches to
 * the first real one. A separate software module would be listed and never chosen.
 *
 * <p>A device whose {@code config_supportsBackgroundMusicRecognition} overlay is false gets the
 * delegate untouched, so opting out costs it not even the interception.
 */
class SoftwareMusicHalFactory implements HalFactory {
    private final @NonNull Context mContext;
    private final @NonNull HalFactory mDelegate;

    SoftwareMusicHalFactory(@NonNull Context context, @NonNull HalFactory delegate) {
        mContext = context;
        mDelegate = delegate;
    }

    @Override
    public ISoundTriggerHal create() {
        final ISoundTriggerHal delegate = mDelegate.create();
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_supportsBackgroundMusicRecognition)) {
            return delegate;
        }
        return new SoftwareMusicHal(mContext, delegate);
    }
}
