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

package com.android.systemui.media.ax.ui.compose

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the media seek bar may animate its squiggle.
 *
 * The compose players (Ax QS media panel, Dynamic Bar expanded media) draw [SquigglyProgress]
 * themselves, so they have to honour `media_squiggle_animation` the way [MediaControlPanel] did for
 * the legacy player - it fed the same setting, together with the animator duration scale, into
 * `SeekBarObserver.setAnimationEnabled`.
 */
@Composable
fun rememberSquiggleAnimationEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember(resolver) { mutableStateOf(isSquiggleAnimationEnabled(resolver)) }

    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled = isSquiggleAnimationEnabled(resolver)
                }
            }
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.MEDIA_SQUIGGLE_ANIMATION),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return enabled
}

/**
 * Whether system animations are on at all ([Settings.Global.ANIMATOR_DURATION_SCALE]).
 *
 * Media surfaces that run their own frame loop — the waveform badge and band — must idle when the
 * user has animations off, the same way the seek bar stops its squiggle.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember(resolver) { mutableStateOf(areAnimationsEnabled(resolver)) }

    DisposableEffect(resolver) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    enabled = areAnimationsEnabled(resolver)
                }
            }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
            UserHandle.USER_ALL,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return enabled
}

private fun areAnimationsEnabled(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

private fun isSquiggleAnimationEnabled(resolver: ContentResolver): Boolean {
    val squiggleEnabled =
        Settings.Secure.getIntForUser(
            resolver,
            Settings.Secure.MEDIA_SQUIGGLE_ANIMATION,
            1,
            UserHandle.USER_CURRENT,
        ) != 0
    return squiggleEnabled && areAnimationsEnabled(resolver)
}
