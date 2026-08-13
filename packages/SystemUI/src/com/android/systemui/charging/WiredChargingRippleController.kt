/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import java.io.File
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.surfaceeffects.ripple.RippleView
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

private const val MAX_DEBOUNCE_LEVEL = 3
private const val BASE_DEBOUNCE_TIME = 2000

/**
 * Wired plug-in charging feedback: AOSP [RippleView] or custom [AXRippleView] / [AXChargingCircleView]
 * per [R.bool.config_useCustomChargingAnim]. Independent of [com.android.systemui.charging.WirelessChargingAnimation]
 * (driven from power [com.android.server.power.Notifier]).
 *
 * Both paths honor [Settings.System.CHARGING_ANIMATION] and the [Flags.CHARGING_RIPPLE] flag
 * (same idea as notifier-driven charging UI: respect user toggle before showing feedback).
 */
@SysUISingleton
class WiredChargingRippleController @Inject constructor(
    commandRegistry: CommandRegistry,
    private val batteryController: BatteryController,
    private val configurationController: ConfigurationController,
    featureFlags: FeatureFlags,
    private val context: Context,
    private val windowManager: WindowManager,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
) {
    private var pluggedIn: Boolean = false
    private var batteryLevel: Int = 0
    private val rippleEnabled: Boolean = featureFlags.isEnabled(Flags.CHARGING_RIPPLE) &&
            !SystemProperties.getBoolean("persist.debug.suppress-charging-ripple", false)
    private var normalizedPortPosX: Float = context.resources.getFloat(
            R.dimen.physical_charger_port_location_normalized_x)
    private var normalizedPortPosY: Float = context.resources.getFloat(
            R.dimen.physical_charger_port_location_normalized_y)
    private val windowLayoutParams = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        format = PixelFormat.TRANSLUCENT
        type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
        fitInsetsTypes = 0 // Ignore insets from all system bars
        title = "Wired Charging Animation"
        flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        setTrustedOverlay()
    }
    private var lastTriggerTime: Long? = null
    private var debounceLevel = 0
    private var handshakePending = false
    private var handshakeAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handshakePoll = Runnable { pollHandshakeAndStart() }

    @VisibleForTesting
    var rippleView: RippleView = RippleView(context, attrs = null).also { it.setupShader() }
    @VisibleForTesting
    var axRippleView: AXRippleView = AXRippleView(context, attrs = null)
    @VisibleForTesting
    var axChargingCircleView: AXChargingCircleView = AXChargingCircleView(context, attrs = null)

    init {
        pluggedIn = batteryController.isPluggedIn
        commandRegistry.registerCommand("charging-ripple") { ChargingRippleCommand() }
        updateRippleColor()
    }

    fun registerCallbacks() {
        val batteryStateChangeCallback = object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(
                level: Int,
                nowPluggedIn: Boolean,
                charging: Boolean
            ) {
                // Suppresses the ripple when the state change comes from wireless charging or
                // its dock.
                if (batteryController.isPluggedInWireless ||
                        batteryController.isChargingSourceDock) {
                    return
                }

                batteryLevel = level
                if (!pluggedIn && nowPluggedIn) {
                    startRippleWithDebounce()
                }
                pluggedIn = nowPluggedIn
            }
        }
        batteryController.addCallback(batteryStateChangeCallback)

        val configurationChangedListener = object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                updateRippleColor()
            }
            override fun onThemeChanged() {
                updateRippleColor()
            }

            override fun onConfigChanged(newConfig: Configuration?) {
                normalizedPortPosX = context.resources.getFloat(
                        R.dimen.physical_charger_port_location_normalized_x)
                normalizedPortPosY = context.resources.getFloat(
                        R.dimen.physical_charger_port_location_normalized_y)
            }
        }
        configurationController.addCallback(configurationChangedListener)
    }

    // Lazily debounce ripple to avoid triggering ripple constantly (e.g. from flaky chargers).
    internal fun startRippleWithDebounce() {
        val now = systemClock.elapsedRealtime()
        // Debounce wait time = 2 ^ debounce level
        if (lastTriggerTime == null ||
                (now - lastTriggerTime!!) > BASE_DEBOUNCE_TIME * (2.0.pow(debounceLevel))) {
            // Not waiting for debounce. Start ripple.
            startRipple()
            debounceLevel = 0
        } else {
            // Still waiting for debounce. Ignore ripple and bump debounce level.
            debounceLevel = min(MAX_DEBOUNCE_LEVEL, debounceLevel + 1)
        }
        lastTriggerTime = now
    }

    fun startRipple() {
        if (!shouldPlayWiredChargingRipple()) {
            return
        }
        if (context.resources.getBoolean(R.bool.config_useCustomChargingAnim)) {
            startCustomRipple()
        } else {
            startAospRipple()
        }
    }

    private fun shouldPlayWiredChargingRipple(): Boolean =
        rippleEnabled && isChargingAnimationSettingEnabled()

    private fun isChargingAnimationSettingEnabled(): Boolean {
        return Settings.System.getIntForUser(
            context.contentResolver,
            Settings.System.CHARGING_ANIMATION,
            1,
            UserHandle.USER_CURRENT
        ) == 1
    }

    private fun startAospRipple() {
        if (rippleView.rippleInProgress() || rippleView.parent != null) {
            // Skip if ripple is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        windowLayoutParams.packageName = context.opPackageName

        val container = FrameLayout(context)
        container.addView(rippleView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val percentText = TextView(context).apply {
            text = "$batteryLevel%"
            textSize = 48f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0f
        }
        container.addView(percentText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {}

            override fun onViewAttachedToWindow(view: View) {
                layoutRipple()
                var cleanupInvoked = false
                rippleView.startRipple(Runnable {
                    if (cleanupInvoked) {
                        return@Runnable
                    }
                    cleanupInvoked = true
                    removeRippleContainer(container)
                })
                val fadeIn = ObjectAnimator.ofFloat(percentText, "alpha", 0f, 1f).apply {
                    duration = 300
                    startDelay = 100
                }
                val fadeOut = ObjectAnimator.ofFloat(percentText, "alpha", 1f, 0f).apply {
                    duration = 400
                    startDelay = 900
                }
                AnimatorSet().apply {
                    playTogether(fadeIn, fadeOut)
                    start()
                }
                container.removeOnAttachStateChangeListener(this)
            }
        })
        windowManager.addView(container, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun startCustomRipple() {
        val mode = context.resources.getInteger(R.integer.config_chargingAnimMode)
        if (mode == CHARGING_ANIM_MODE_CIRCLE) {
            startCircleAnimation()
        } else {
            startRippleAnimation()
        }
    }

    private fun startRippleAnimation() {
        if (axRippleView.rippleInProgress() || axRippleView.parent != null || handshakePending) {
            // Skip if ripple is still playing, already attached, or waiting on SuperVOOC handshake.
            return
        }
        val detectSvooc = context.resources.getBoolean(R.bool.config_chargingAnimDetectSvooc)
                || hasSvoocFrames()
        if (detectSvooc) {
            handshakePending = true
            handshakeAttempts = 0
            pollHandshakeAndStart()
        } else {
            playRippleFrames(useSvooc = false, ratedWatts = 0)
        }
    }

    private fun hasSvoocFrames(): Boolean {
        val ta = context.resources.obtainTypedArray(R.array.config_chargingAnimSvoocFrames)
        val has = ta.length() > 0
        ta.recycle()
        return has
    }

    private fun pollHandshakeAndStart() {
        if (!shouldPlayWiredChargingRipple() || !batteryController.isPluggedIn) {
            handshakePending = false
            return
        }
        val type = readFastChgType()
        val svooc = isSuperVooc(type)
        val timeoutMs = context.resources.getInteger(R.integer.config_chargingAnimHandshakeTimeoutMs)
        val maxAttempts = (timeoutMs / HANDSHAKE_POLL_MS).coerceAtLeast(1)
        handshakeAttempts++
        if (svooc || handshakeAttempts >= maxAttempts) {
            handshakePending = false
            playRippleFrames(useSvooc = svooc, ratedWatts = if (svooc) readRatedWatts() else 0)
        } else {
            mainHandler.postDelayed(handshakePoll, HANDSHAKE_POLL_MS.toLong())
        }
    }

    private fun playRippleFrames(useSvooc: Boolean, ratedWatts: Int) {
        if (axRippleView.rippleInProgress() || axRippleView.parent != null) {
            return
        }
        if (!shouldPlayWiredChargingRipple() || !batteryController.isPluggedIn) {
            return
        }
        axRippleView.setRatedWatts(if (useSvooc && ratedWatts > 0) ratedWatts else if (useSvooc) 100 else 0)
        axRippleView.preloadRes(useSvoocFrames = useSvooc)
        windowLayoutParams.packageName = context.opPackageName
        axRippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {}

            override fun onViewAttachedToWindow(view: View) {
                var cleanupInvoked = false
                axRippleView.startRipple(Runnable {
                    if (cleanupInvoked) {
                        return@Runnable
                    }
                    cleanupInvoked = true
                    removeWindowViewIfAttached(axRippleView)
                })
                axRippleView.removeOnAttachStateChangeListener(this)
            }
        })
        windowManager.addView(axRippleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun readFastChgType(): Int {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val extra = sticky?.getIntExtra(BatteryManager.EXTRA_OEM_FAST_CHG_TYPE, 0) ?: 0
        if (extra > 0) {
            return extra
        }
        return readSysfsInt(FAST_CHG_TYPE_USB)
            ?: readSysfsInt(FAST_CHG_TYPE_BATT)
            ?: 0
    }

    private fun readRatedWatts(): Int {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val extra = sticky?.getIntExtra(BatteryManager.EXTRA_OEM_CHARGER_WATTS, 0) ?: 0
        if (extra > 0) {
            return extra
        }
        val type = readFastChgType()
        // 100W SuperVOOC adapter ids (oplus table) + dodge 0x65.
        if (type == 101 || (type in 0x3b..0x3e) || type == 0x69 || type == 0x6a) {
            return 100
        }
        return 0
    }

    private fun readSysfsInt(path: String): Int? {
        return try {
            File(path).takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun isSuperVooc(type: Int): Boolean =
        type == CHARGER_SUBTYPE_FASTCHG_SVOOC || type >= OPLUS_SVOOC_ID_MIN

    private fun startCircleAnimation() {
        if (axChargingCircleView.animationInProgress() || axChargingCircleView.parent != null) {
            // Skip if animation is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        axChargingCircleView.setBatteryLevel(batteryLevel)
        axChargingCircleView.preloadRes()
        windowLayoutParams.packageName = context.opPackageName
        axChargingCircleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {}

            override fun onViewAttachedToWindow(view: View) {
                var cleanupInvoked = false
                axChargingCircleView.startAnimation(Runnable {
                    if (cleanupInvoked) {
                        return@Runnable
                    }
                    cleanupInvoked = true
                    removeWindowViewIfAttached(axChargingCircleView)
                })
                axChargingCircleView.removeOnAttachStateChangeListener(this)
            }
        })
        windowManager.addView(axChargingCircleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    companion object {
        private const val CHARGING_ANIM_MODE_CIRCLE = 1
        private const val HANDSHAKE_POLL_MS = 150
        // Matches kernel CHARGER_SUBTYPE_FASTCHG_SVOOC / OPLUS_SVOOC_ID_MIN.
        private const val CHARGER_SUBTYPE_FASTCHG_SVOOC = 2
        private const val OPLUS_SVOOC_ID_MIN = 10
        private const val FAST_CHG_TYPE_USB = "/sys/class/oplus_chg/usb/fast_chg_type"
        private const val FAST_CHG_TYPE_BATT = "/sys/class/oplus_chg/battery/fast_chg_type"
    }

    private fun removeRippleContainer(container: FrameLayout) {
        if (rippleView.parent === container) {
            container.removeView(rippleView)
        }
        removeWindowViewIfAttached(container)
    }

    private fun removeWindowViewIfAttached(view: View) {
        if (view.isAttachedToWindow) {
            windowManager.removeView(view)
        }
    }

    private fun layoutRipple() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val maxDiameter = Integer.max(width, height) * 2f
        rippleView.setMaxSize(maxDiameter, maxDiameter)
        when (context.display?.rotation) {
            Surface.ROTATION_0 -> {
                rippleView.setCenter(
                        width * normalizedPortPosX, height * normalizedPortPosY)
            }
            Surface.ROTATION_90 -> {
                rippleView.setCenter(
                        width * normalizedPortPosY, height * (1 - normalizedPortPosX))
            }
            Surface.ROTATION_180 -> {
                rippleView.setCenter(
                        width * (1 - normalizedPortPosX), height * (1 - normalizedPortPosY))
            }
            Surface.ROTATION_270 -> {
                rippleView.setCenter(
                        width * (1 - normalizedPortPosY), height * normalizedPortPosX)
            }
        }
    }

    private fun updateRippleColor() {
        rippleView.setColor(Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor)
    }

    inner class ChargingRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            startRipple()
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar charging-ripple")
        }
    }

    enum class WiredChargingRippleEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Wired charging ripple effect played")
        CHARGING_RIPPLE_PLAYED(829);

        override fun getId() = _id
    }
}
