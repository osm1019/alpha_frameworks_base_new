/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui

import android.content.Context
import android.os.SystemProperties
import android.util.Log
import com.android.systemui.dagger.DaggerReferenceGlobalRootComponent
import com.android.systemui.dagger.GlobalRootComponent

/**
 * {@link SystemUIInitializer} that stands up AOSP SystemUI, or the Google graph when a
 * SystemUIGoogle build opts in via [PROP_GOOGLE_DI]. Falls back to AOSP on any failure.
 */
class SystemUIInitializerImpl(context: Context) : SystemUIInitializer(context) {
    override fun getGlobalRootComponentBuilder(): GlobalRootComponent.Builder {
        if (SystemProperties.getBoolean(PROP_GOOGLE_DI, false)) {
            googleRootComponentBuilder()?.let { return it }
        }
        return DaggerReferenceGlobalRootComponent.builder()
    }

    /** Reflective: AOSP builds have no Google source set to link against. */
    private fun googleRootComponentBuilder(): GlobalRootComponent.Builder? =
        try {
            Class.forName(GOOGLE_ROOT_COMPONENT).getMethod("builder").invoke(null)
                as GlobalRootComponent.Builder
        } catch (t: Throwable) {
            Log.w(TAG, "Google root unavailable, falling back to AOSP graph", t)
            null
        }

    companion object {
        private const val TAG = "SystemUIInitializerImpl"
        private const val PROP_GOOGLE_DI = "persist.sys.alpha.sysui_google_di"
        private const val GOOGLE_ROOT_COMPONENT =
            "com.android.systemui.dagger.DaggerSystemUIGoogleGlobalRootComponent"
    }
}
