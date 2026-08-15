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

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.alpha.theme.AlphaColors
import java.util.function.Consumer

/**
 * Frost behind the status bar chip.
 *
 * Same path the volume dialog uses — [android.view.ViewRootImpl.createBackgroundBlurDrawable]
 * blurs whatever is behind the *window*, which for the status bar is app content or wallpaper.
 * That is exactly what should be frosted, and it is why this works here when it would not on the
 * lockscreen media card: that one draws its own artwork inside its window, so a cross-window blur
 * samples straight past it.
 *
 * Without a blur behind it, a translucent chip is not glass — it is the accent averaged with
 * whatever is underneath, and the average of a red pill and a photograph is brown. Callers pair
 * this with a lower body alpha and fall back to a denser one when [rememberChipBlurEnabled] is
 * false.
 */
@Composable
internal fun ChipGlassBackdrop(corner: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val cornerPx = with(density) { corner.toPx() }
    val blurPx = with(density) { AlphaColors.DbStatusBarChip.blurRadius.toPx().toInt() }
    AndroidView(
        factory = { context ->
            View(context).apply {
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            val root = v.viewRootImpl ?: return
                            v.background =
                                root.createBackgroundBlurDrawable().apply {
                                    // Tint is drawn in Compose above this; blur only here.
                                    setColor(Color.Transparent.toArgb())
                                    setBlurRadius(blurPx)
                                    setCornerRadius(cornerPx)
                                }
                        }

                        override fun onViewDetachedFromWindow(v: View) {
                            v.background = ColorDrawable(Color.Transparent.toArgb())
                        }
                    }
                )
            }
        },
        update = { view ->
            (view.background as? BackgroundBlurDrawable)?.apply {
                setBlurRadius(blurPx)
                setCornerRadius(cornerPx)
            }
        },
        modifier = modifier,
    )
}

/** True while the compositor is willing to blur (developer option, power save, GPU support). */
@Composable
internal fun rememberChipBlurEnabled(): Boolean {
    val context = LocalContext.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var enabled by remember(windowManager) {
        mutableStateOf(windowManager?.isCrossWindowBlurEnabled ?: false)
    }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { value -> enabled = value }
        // Called back immediately with the current state on registration.
        windowManager?.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager?.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}
