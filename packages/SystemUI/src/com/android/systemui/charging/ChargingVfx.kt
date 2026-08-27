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

package com.android.systemui.charging

import android.content.Context
import android.util.Log
import com.android.systemui.res.R

/**
 * Probe for the stock GLES charging ring ([libnativeChargingRing]). Does not
 * reference [com.oplus.vfxsdk] classes so [System.loadLibrary] can run before
 * those classes' static initializers.
 */
object ChargingVfx {
    private const val TAG = "ChargingVfx"
    private const val LIBRARY = "nativeChargingRing"
    const val OVERLAY_PACKAGE = "com.android.systemui.charging_animation.supervooc"

    private val ASSET_TEXTURES = arrayOf("ring_4_in_1.png", "glow.png", "flash.png")
    private val DRAWABLE_TEXTURES = arrayOf(
        "oplus_vfx_ring_4_in_1",
        "oplus_vfx_glow",
        "oplus_vfx_flash",
    )

    @Volatile
    private var libraryLoaded: Boolean? = null

    fun isEnabled(context: Context): Boolean {
        val wantVfx = try {
            context.resources.getBoolean(R.bool.config_chargingAnimUseStockVfx)
        } catch (_: Exception) {
            false
        }
        if (!wantVfx) {
            return false
        }
        val lib = isLibraryAvailable()
        val textures = hasTextures(context)
        Log.i(TAG, "wantVfx=true lib=$lib textures=$textures")
        return lib && textures
    }

    @Synchronized
    fun isLibraryAvailable(): Boolean {
        libraryLoaded?.let { return it }
        val loaded = try {
            System.loadLibrary(LIBRARY)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "libnativeChargingRing not available", t)
            false
        }
        libraryLoaded = loaded
        return loaded
    }

    fun hasTextures(context: Context): Boolean {
        if (hasDrawables(context, context.packageName)) {
            return true
        }
        try {
            val overlay = context.createPackageContext(OVERLAY_PACKAGE, 0)
            if (hasDrawables(overlay, OVERLAY_PACKAGE)) {
                return true
            }
        } catch (_: Exception) {
        }
        return ASSET_TEXTURES.all { name ->
            try {
                context.assets.open(name).close()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun hasDrawables(context: Context, packageName: String): Boolean {
        return DRAWABLE_TEXTURES.all { name ->
            context.resources.getIdentifier(name, "drawable", packageName) != 0
        }
    }
}
