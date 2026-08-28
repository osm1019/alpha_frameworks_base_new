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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
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
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.util.Log
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.TextView
import com.android.internal.annotations.VisibleForTesting
import com.oplus.vfxsdk.charge.charging.IChargingEngineControl
import com.oplus.vfxsdk.charge.charging.VFXChargingTextureView
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.Utils
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.axdynamicbar.data.ChargingEventSource
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
private const val ACTION_BOOST_CHARGING =
        "org.lineageos.device.settings.action.BOOST_CHARGING"
private const val DEVICE_SETTINGS_PACKAGE = "org.lineageos.device.settings"
private const val SETTINGS_CHARGE_BOOST_AVAILABLE =
        "device_settings_charge_boost_available"
private const val SETTINGS_CHARGE_HUD_MODE =
        "device_settings_charge_hud_mode"
private const val HUD_MODE_UNLIMITED = 0
private const val HUD_MODE_STANDARD = 1
private const val HUD_MODE_NIGHT = 2
/** cool_down=5 → svooc_2_0_curr_table 3000 mA at ~10 V SuperVOOC. */
private const val STANDARD_CAP_WATTS = 30

/**
 * Wired plug-in charging feedback: AOSP [RippleView], custom [AXRippleView] / [AXChargingCircleView],
 * or the stock GLES ring ([VFXChargingTextureView] / libnativeChargingRing) when
 * [R.bool.config_chargingAnimUseStockVfx] is set and the prebuilt is present.
 * Independent of [com.android.systemui.charging.WirelessChargingAnimation]
 * (driven from power [com.android.server.power.Notifier]).
 *
 * Both paths honor [Settings.System.CHARGING_ANIMATION] and the [Flags.CHARGING_RIPPLE] flag
 * (same idea as notifier-driven charging UI: respect user toggle before showing feedback).
 *
 * The GLES window is tap-dismissible and tears down on unplug (ColorOS
 * [OplusChargeAnimImpl] CHARGE_STATE_CANCEL). After the HUD is up, a long-press
 * inside the 275dp center circle mint-gradients the number and asks
 * DeviceSettings to uncap cool_down for this plug session only (no HAL, no
 * water-wave). While a cap is in effect on VOOC/SuperVOOC and the pack is
 * not full, [tips_text] shows "Touch and hold to boost speed" above the
 * number and is cleared on boost. USB/AC never gets that tip. That release
 * is not a dismiss; a later tap still is. The window clock starts at show and
 * is capped at the native 20s clip; long-press is a 14s lap on that clock,
 * not a reset — fade at min(now + 14s, start + 20s). HUD watts follow
 * DeviceSettings: 30W when fast charging is off, "Night mode" in place of
 * SUPERVOOC + watts when night mode is on, brick rating after a session
 * boost. Non-VOOC plugs show bolt + source (USB). 100% shows "Charged"
 * instead of source/wordmark.
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
    private val chargingEventSource: ChargingEventSource? = null,
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
        flags = PASS_THROUGH_WINDOW_FLAGS
        setTrustedOverlay()
    }
    private var lastTriggerTime: Long? = null
    private var debounceLevel = 0
    private var handshakePending = false
    private var handshakeAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handshakePoll = Runnable { pollHandshakeAndStart() }
    private var vfxContainer: FrameLayout? = null
    private val vfxWindowEnd = Runnable { fadeOutVfxWindow() }
    private val vfxShowHud = Runnable { revealVfxHud() }
    private val vfxLongPress = Runnable { onVfxLongPress() }
    private val vfxSpeedUpTimeout = Runnable { fadeOutVfxWindow() }
    private var vfxBranding: ChargingVfxBrandingView? = null
    private var vfxHudShown = false
    private var vfxCanAcceptLongClick = false
    private var vfxLongPressTriggered = false
    /** Local until DeviceSettings publishes unlimited after the boost broadcast. */
    private var vfxSessionBoosted = false
    private var vfxTouchInsideCircle = false
    private var vfxDownX = 0f
    private var vfxDownY = 0f
    private var vfxFading = false
    private var vfxFadeAnimator: ObjectAnimator? = null
    private var vfxBatteryReceiver: BroadcastReceiver? = null
    /** [systemClock] elapsedRealtime when the GLES window was shown. */
    private var vfxStartedElapsed: Long = 0L

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
                } else if (pluggedIn && !nowPluggedIn) {
                    dismissChargingAnim("unplug")
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
        if (axRippleView.rippleInProgress() || axRippleView.parent != null || handshakePending ||
                vfxContainer?.parent != null) {
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
            playChargingAnim(resolveChargingSession())
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
        val session = resolveChargingSession()
        val timeoutMs = context.resources.getInteger(R.integer.config_chargingAnimHandshakeTimeoutMs)
        val maxAttempts = (timeoutMs / HANDSHAKE_POLL_MS).coerceAtLeast(1)
        handshakeAttempts++
        Log.i(TAG, "handshake n=" + handshakeAttempts
                + " ready=" + session.handshakeReady
                + " svooc=" + session.showSuperVooc
                + " watts=" + session.ratedWatts
                + " fast=" + session.fastChgType
                + " oem=" + session.oemCharger
                + " db=" + session.dbChargeType)
        // Same readiness as the DB keyguard card: SuperVOOC label, OEM watts,
        // or OEM charger extra — not only kernel fast_chg_type (often 0 at plug-in).
        if (session.handshakeReady || handshakeAttempts >= maxAttempts) {
            handshakePending = false
            playChargingAnim(session.withOverlayFallback())
        } else {
            mainHandler.postDelayed(handshakePoll, HANDSHAKE_POLL_MS.toLong())
        }
    }

    private fun playChargingAnim(session: ChargingSession) {
        if (ChargingVfx.isEnabled(context)) {
            try {
                playVfx(session)
                return
            } catch (t: Throwable) {
                Log.w(TAG, "stock VFX failed, falling back to PNG", t)
            }
        }
        playRippleFrames(useSvooc = session.showSuperVooc, ratedWatts = session.ratedWatts)
    }

    private fun isSvoocOverlay(): Boolean {
        return try {
            context.resources.getBoolean(R.bool.config_chargingAnimDetectSvooc)
        } catch (_: Exception) {
            false
        }
    }

    private fun playVfx(session: ChargingSession) {
        if (vfxContainer?.parent != null) {
            return
        }
        if (!shouldPlayWiredChargingRipple() || !batteryController.isPluggedIn) {
            return
        }
        val chargineType = when {
            session.showSuperVooc -> VFXChargingTextureView.ChargineType.HIGH
            session.fastChgType == CHARGER_SUBTYPE_FASTCHG_VOOC ->
                VFXChargingTextureView.ChargineType.MEDIUM
            else -> VFXChargingTextureView.ChargineType.LOW
        }
        val ringView = VFXChargingTextureView(context)
        if (!ringView.isEngineReady) {
            throw IllegalStateException("VFX engine failed to init")
        }
        ringView.setChargineType(chargineType)
        val branding = ChargingVfxBrandingView(context).apply {
            visibility = View.INVISIBLE
            setBatteryLevel(batteryLevel)
        }
        Log.i(TAG, "vfx type=" + chargineType
                + " fast=" + session.fastChgType
                + " watts=" + session.ratedWatts
                + " svooc=" + session.showSuperVooc
                + " oem=" + session.oemCharger
                + " db=" + session.dbChargeType
                + " level=" + batteryLevel)
        vfxBranding = branding
        vfxHudShown = false
        vfxCanAcceptLongClick = false
        vfxLongPressTriggered = false
        vfxSessionBoosted = false
        vfxTouchInsideCircle = false
        applyVfxHud(session)
        ringView.setChargingEngineControl(object : IChargingEngineControl {
            override fun onFlashAnimationFinishedCallBack() {
                mainHandler.post { revealVfxHud() }
            }
        })
        ringView.setResourceCallback { success ->
            if (success) {
                return@setResourceCallback
            }
            Log.w(TAG, "stock VFX textures failed, falling back to PNG")
            finishVfxWindow()
            playRippleFrames(
                useSvooc = session.showSuperVooc,
                ratedWatts = session.ratedWatts
            )
        }

        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val dim = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        container.addView(dim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container.addView(ringView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container.addView(branding, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {}
            override fun onViewAttachedToWindow(view: View) {
                Log.i(TAG, "vfx attached hw=" + ringView.isHardwareAccelerated
                        + " size=" + ringView.width + "x" + ringView.height)
                ObjectAnimator.ofFloat(dim, View.ALPHA, 0f, 0.6f).apply {
                    duration = 1000
                    start()
                }
                container.removeOnAttachStateChangeListener(this)
            }
        })
        vfxContainer = container
        vfxFading = false
        windowLayoutParams.packageName = context.opPackageName
        applyVfxWindowFlags()
        container.isClickable = true
        container.setOnTouchListener { view, event -> handleVfxTouch(view, event) }
        mainHandler.removeCallbacks(vfxWindowEnd)
        mainHandler.removeCallbacks(vfxShowHud)
        mainHandler.removeCallbacks(vfxLongPress)
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        vfxStartedElapsed = systemClock.elapsedRealtime()
        mainHandler.postDelayed(vfxShowHud, VFX_FLASH_MS)
        // Native setAnimationDuration is 20s. Fade so the surface is gone at that
        // mark; past it ChargingEngine::doFrame wraps (fade-out then fade-in).
        // Do not reuse config_chargingAnimHoldMs (PNG last-frame pause, 1500ms).
        val untilCap = vfxMsUntilNativeCap()
        mainHandler.postDelayed(vfxWindowEnd, untilCap)
        Log.i(TAG, "vfx window " + untilCap + "ms then fade " + VFX_FADE_OUT_MS + "ms")
        windowManager.addView(container, windowLayoutParams)
        startVfxOemUpdates()
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    /**
     * BatteryService extras and the DB card can land after the window opens.
     * Keep the HUD in sync with SuperVOOC detection, then overlay DeviceSettings
     * current-cap policy (30W / night mode) without reading oplus_chg.
     */
    private fun startVfxOemUpdates() {
        stopVfxOemUpdates()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applyVfxHud(resolveChargingSession(intent))
            }
        }
        vfxBatteryReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun stopVfxOemUpdates() {
        val receiver = vfxBatteryReceiver ?: return
        vfxBatteryReceiver = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun applyVfxHud(session: ChargingSession) {
        val branding = vfxBranding ?: return
        branding.setBatteryLevel(batteryLevel)
        if (vfxHudShown) {
            branding.setBoostTip(shouldShowBoostTip(session))
            vfxCanAcceptLongClick = canAcceptChargeBoost(session)
        }
        if (batteryLevel >= 100) {
            vfxCanAcceptLongClick = false
            branding.setBoostTip(false)
            branding.setShowLogo(false)
            branding.setRatedWatts(0)
            branding.setSourceLabel(null)
            branding.setStatusLabel(context.getString(R.string.keyguard_charged))
            Log.i(TAG, "vfx hud charged")
            return
        }
        if (session.showSuperVooc) {
            val mode = chargeHudMode()
            when (mode) {
                HUD_MODE_NIGHT -> {
                    branding.setShowLogo(false)
                    branding.setRatedWatts(0)
                    branding.setSourceLabel(null)
                    branding.setStatusLabel(context.getString(R.string.charging_vfx_night_mode))
                }
                HUD_MODE_STANDARD -> {
                    branding.setStatusLabel(null)
                    branding.setSourceLabel(null)
                    branding.setShowLogo(true)
                    branding.setRatedWatts(STANDARD_CAP_WATTS)
                }
                else -> {
                    branding.setStatusLabel(null)
                    branding.setSourceLabel(null)
                    branding.setShowLogo(true)
                    branding.setRatedWatts(session.ratedWatts)
                }
            }
            Log.i(TAG, "vfx hud update brick=" + session.ratedWatts
                    + " mode=" + mode
                    + " fast=" + session.fastChgType
                    + " db=" + session.dbChargeType)
            return
        }
        branding.setShowLogo(false)
        branding.setRatedWatts(0)
        branding.setStatusLabel(null)
        branding.setSourceLabel(
            when {
                session.showVooc -> context.getString(R.string.charging_vfx_source_vooc)
                session.showPps -> context.getString(R.string.charging_vfx_source_pps)
                else -> plugSourceLabel(session.plugged)
            }
        )
        Log.i(TAG, "vfx hud source vooc=" + session.showVooc
                + " plugged=" + session.plugged
                + " fast=" + session.fastChgType)
    }

    private fun plugSourceLabel(plugged: Int): String {
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                context.getString(R.string.charging_vfx_source_wireless)
            BatteryManager.BATTERY_PLUGGED_DOCK ->
                context.getString(R.string.charging_vfx_source_dock)
            else ->
                context.getString(R.string.charging_vfx_source_usb)
        }
    }

    /** DeviceSettings publishes this; do not read oplus_chg from SystemUI. */
    private fun chargeHudMode(): Int {
        if (vfxSessionBoosted) {
            return HUD_MODE_UNLIMITED
        }
        return Settings.System.getInt(
            context.contentResolver,
            SETTINGS_CHARGE_HUD_MODE,
            HUD_MODE_UNLIMITED
        )
    }

    private fun revealVfxHud() {
        if (vfxHudShown) {
            return
        }
        vfxHudShown = true
        val session = resolveChargingSession()
        mainHandler.removeCallbacks(vfxShowHud)
        applyVfxHud(session)
        vfxBranding?.fadeIn()
        Log.i(TAG, "vfx hud")
    }

    private fun canAcceptChargeBoost(session: ChargingSession): Boolean {
        if (vfxSessionBoosted || batteryLevel >= 100) {
            return false
        }
        if (!session.showSuperVooc && !session.showVooc) {
            return false
        }
        return isChargeBoostAvailable()
    }

    private fun shouldShowBoostTip(session: ChargingSession): Boolean {
        return canAcceptChargeBoost(session)
    }

    /** DeviceSettings publishes this; do not read oplus_chg from SystemUI. */
    private fun isChargeBoostAvailable(): Boolean {
        return Settings.System.getInt(
            context.contentResolver,
            SETTINGS_CHARGE_BOOST_AVAILABLE,
            0
        ) == 1
    }

    /**
     * ColorOS CircleView (275dp, alpha 0) + [View.OnLongClickListener]. Long-press
     * only after HUD, only inside the inscribed circle, one-shot. Visual gradient;
     * HAL [singleChargeSpeedUp] / water-wave are not wired.
     */
    private fun handleVfxTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vfxTouchInsideCircle = isInsideVfxCircle(view, event)
                mainHandler.removeCallbacks(vfxLongPress)
                if (vfxCanAcceptLongClick && vfxTouchInsideCircle) {
                    vfxDownX = event.x
                    vfxDownY = event.y
                    mainHandler.postDelayed(
                        vfxLongPress,
                        ViewConfiguration.getLongPressTimeout().toLong()
                    )
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vfxTouchInsideCircle = isInsideVfxCircle(view, event)
                if (mainHandler.hasCallbacks(vfxLongPress)) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    val dx = event.x - vfxDownX
                    val dy = event.y - vfxDownY
                    if (!vfxTouchInsideCircle || dx * dx + dy * dy > slop * slop) {
                        mainHandler.removeCallbacks(vfxLongPress)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(vfxLongPress)
                if (vfxLongPressTriggered) {
                    // Stock mCircleToucheListener: this UP is the long-press release.
                    vfxLongPressTriggered = false
                } else if (event.pointerCount <= 1) {
                    Log.i(TAG, "vfx dismiss tap")
                    fadeOutVfxWindow()
                }
            }
        }
        return true
    }

    private fun isInsideVfxCircle(view: View, event: MotionEvent): Boolean {
        val radius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            VFX_CIRCLE_DP / 2f,
            context.resources.displayMetrics
        )
        val dx = event.x - view.width / 2f
        val dy = event.y - view.height / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    private fun onVfxLongPress() {
        if (vfxFading || vfxContainer == null) {
            return
        }
        if (!vfxCanAcceptLongClick || !vfxTouchInsideCircle) {
            return
        }
        vfxLongPressTriggered = true
        vfxCanAcceptLongClick = false
        vfxSessionBoosted = true
        vfxBranding?.setBoostTip(false)
        vfxBranding?.setSpeedUpGradient(true)
        applyVfxHud(resolveChargingSession())
        requestSessionChargeBoost()
        // Original show clock keeps running. This is a lap, not a reset:
        // min(now + 14s, start + native 20s).
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        val lapMs = min(VFX_SPEED_UP_HOLD_MS, vfxMsUntilNativeCap())
        if (lapMs <= 0L) {
            fadeOutVfxWindow()
        } else {
            mainHandler.postDelayed(vfxSpeedUpTimeout, lapMs)
        }
        Log.i(TAG, "vfx speed-up lap " + lapMs + "ms")
    }

    /**
     * Delay until Java fade must start so the window is gone at the native 20s
     * clip. The GLES engine wraps after that while the TextureView is alive.
     */
    private fun vfxMsUntilNativeCap(): Long {
        val elapsed = systemClock.elapsedRealtime() - vfxStartedElapsed
        return (VFX_NATIVE_MS - VFX_FADE_OUT_MS - elapsed).coerceAtLeast(0L)
    }

    /** DeviceSettings owns cool_down; this is a session flag, not a sysfs write. */
    private fun requestSessionChargeBoost() {
        val intent = Intent(ACTION_BOOST_CHARGING).apply {
            setPackage(DEVICE_SETTINGS_PACKAGE)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcastAsUser(intent, UserHandle.CURRENT)
    }

    private fun fadeOutVfxWindow() {
        val container = vfxContainer
        if (container == null) {
            finishVfxWindow()
            return
        }
        if (vfxFading) {
            return
        }
        vfxFading = true
        mainHandler.removeCallbacks(vfxWindowEnd)
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        mainHandler.removeCallbacks(vfxLongPress)
        val anim = ObjectAnimator.ofFloat(container, View.ALPHA, container.alpha, 0f).apply {
            duration = VFX_FADE_OUT_MS
            interpolator = LinearInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finishVfxWindow()
                }
            })
        }
        vfxFadeAnimator = anim
        anim.start()
    }

    private fun finishVfxWindow() {
        mainHandler.removeCallbacks(vfxWindowEnd)
        mainHandler.removeCallbacks(vfxShowHud)
        mainHandler.removeCallbacks(vfxLongPress)
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        stopVfxOemUpdates()
        vfxFadeAnimator?.removeAllListeners()
        vfxFadeAnimator?.cancel()
        vfxFadeAnimator = null
        vfxFading = false
        vfxBranding = null
        vfxHudShown = false
        vfxCanAcceptLongClick = false
        vfxLongPressTriggered = false
        vfxSessionBoosted = false
        vfxTouchInsideCircle = false
        vfxStartedElapsed = 0L
        val container = vfxContainer
        vfxContainer = null
        if (container != null) {
            container.setOnTouchListener(null)
            Log.i(TAG, "vfx window removed")
            try {
                windowManager.removeView(container)
            } catch (_: IllegalArgumentException) {
                removeWindowViewIfAttached(container)
            }
        }
        restoreWindowFlags()
    }

    /**
     * ColorOS CHARGE_STATE_CANCEL: tap or unplug. Handshake poll is dropped so a
     * late type read cannot open a new window after the charger is gone.
     */
    private fun dismissChargingAnim(reason: String) {
        Log.i(TAG, "dismiss " + reason)
        handshakePending = false
        mainHandler.removeCallbacks(handshakePoll)
        if (vfxContainer != null) {
            fadeOutVfxWindow()
        }
        if (axRippleView.parent != null) {
            axRippleView.cancelRipple()
            removeWindowViewIfAttached(axRippleView)
        }
        if (axChargingCircleView.parent != null) {
            removeWindowViewIfAttached(axChargingCircleView)
        }
        val aospParent = rippleView.parent
        if (aospParent is FrameLayout && aospParent.isAttachedToWindow) {
            removeRippleContainer(aospParent)
        }
    }

    private fun applyVfxWindowFlags() {
        windowLayoutParams.flags = (PASS_THROUGH_WINDOW_FLAGS
                and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    }

    private fun restoreWindowFlags() {
        windowLayoutParams.flags = PASS_THROUGH_WINDOW_FLAGS
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

    /**
     * SuperVOOC / wattage the same way the DB keyguard card and lockscreen
     * indication do: [BatteryManager.EXTRA_OEM_CHARGER_WATTS],
     * [BatteryManager.EXTRA_OEM_CHARGER] / [BatteryStatus.CHARGING_OEM],
     * [ChargingEventSource] `chargeType` ("100W SuperVOOC Charging"),
     * then protocol [BatteryManager.EXTRA_OEM_FAST_CHG_TYPE].
     */
    private fun resolveChargingSession(intent: Intent? = null): ChargingSession {
        val sticky = intent ?: context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val extraType = sticky?.getIntExtra(BatteryManager.EXTRA_OEM_FAST_CHG_TYPE, 0) ?: 0
        val type = if (extraType > 0) {
            extraType
        } else {
            readSysfsInt(FAST_CHG_TYPE_USB) ?: readSysfsInt(FAST_CHG_TYPE_BATT) ?: 0
        }
        val extraWatts = sticky?.getIntExtra(BatteryManager.EXTRA_OEM_CHARGER_WATTS, 0) ?: 0
        val oemExtra = sticky?.getBooleanExtra(BatteryManager.EXTRA_OEM_CHARGER, false) == true
        val oemSpeed = sticky != null &&
            BatteryStatus(sticky).getChargingSpeed(context) == BatteryStatus.CHARGING_OEM
        val oemCharger = oemExtra || oemSpeed
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val extraLevel = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        if (extraLevel in 0..100) {
            batteryLevel = extraLevel
        }
        val hasVooc = hasVoocCharger()
        val dbChargeType = chargingEventSource?.chargingEvent?.value?.chargeType
        val dbSuperVooc = dbChargeType?.contains("SuperVOOC", ignoreCase = true) == true
        val dbVooc = dbChargeType?.contains("VOOC", ignoreCase = true) == true
        val dbWatts = parseWattsLabel(dbChargeType)
        val protocolSvooc = isSuperVooc(type)
        val protocolVooc = isVooc(type)
        val watts = when {
            extraWatts > 0 -> extraWatts
            dbWatts > 0 -> dbWatts
            protocolSvooc -> ratedWattsFromType(type)
            else -> 0
        }
        // ChargingEventSource: hasVooc + oem watts → "NW SuperVOOC Charging".
        // Protocol VOOC (fast_chg_type=1) is not SuperVOOC even if voocchg_ing
        // sets EXTRA_OEM_CHARGER — otherwise a VOOC brick inherits the 100W mark.
        val showSuperVooc = protocolSvooc
            || dbSuperVooc
            || (!protocolVooc && hasVooc && watts > 0)
            || (!protocolVooc && hasVooc && oemCharger && isSvoocOverlay())
        val showVooc = !showSuperVooc && (protocolVooc || (dbVooc && !dbSuperVooc))
        // PD/PPS is a separate oplus path with its own node; without this it reads as USB.
        // cool_down votes on the VOOC votable only, so a PPS session is never capped
        // and never offers the boost tip.
        val showPps = !showSuperVooc && !showVooc && readSysfsInt(PPS_CHG_ING) == 1
        val handshakeReady = showSuperVooc || showVooc || showPps || oemCharger || dbVooc || watts > 0
        val hudWatts = if (showSuperVooc) {
            if (watts > 0) watts else 100
        } else {
            0
        }
        return ChargingSession(
            fastChgType = type,
            ratedWatts = hudWatts,
            showSuperVooc = showSuperVooc,
            showVooc = showVooc,
            showPps = showPps,
            plugged = plugged,
            oemCharger = oemCharger,
            handshakeReady = handshakeReady,
            dbChargeType = dbChargeType,
        )
    }

    private fun ChargingSession.withOverlayFallback(): ChargingSession {
        if (showSuperVooc || showVooc || !isSvoocOverlay()) {
            return this
        }
        // Handshake timed out. Only assume SuperVOOC when something besides the
        // overlay bool suggests an OEM brick — USB/SDP must stay USB.
        if (!oemCharger && ratedWatts <= 0 && fastChgType <= 0) {
            return this
        }
        return copy(showSuperVooc = true, ratedWatts = if (ratedWatts > 0) ratedWatts else 100)
    }

    private fun parseWattsLabel(label: String?): Int {
        if (label.isNullOrEmpty()) return 0
        return WATTS_IN_LABEL.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun ratedWattsFromType(type: Int): Int {
        // 100W SuperVOOC adapter ids (oplus table) + dodge 0x65 / aston 105.
        if (type == 101 || type == 105 || (type in 0x3b..0x3e) || type == 0x69 || type == 0x6a) {
            return 100
        }
        return 0
    }

    private fun hasVoocCharger(): Boolean {
        return try {
            context.resources.getBoolean(com.android.internal.R.bool.config_hasVoocCharger)
        } catch (_: Exception) {
            false
        }
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

    private fun isVooc(type: Int): Boolean =
        type == CHARGER_SUBTYPE_FASTCHG_VOOC

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

    private data class ChargingSession(
        val fastChgType: Int,
        val ratedWatts: Int,
        val showSuperVooc: Boolean,
        val showVooc: Boolean,
        val showPps: Boolean,
        val plugged: Int,
        val oemCharger: Boolean,
        val handshakeReady: Boolean,
        val dbChargeType: String?,
    )

    companion object {
        private const val CHARGING_ANIM_MODE_CIRCLE = 1
        private const val HANDSHAKE_POLL_MS = 150
        private const val TAG = "WiredChargingRipple"
        private val WATTS_IN_LABEL = Regex("""(\d+)\s*W""")
        // Matches kernel CHARGER_SUBTYPE_FASTCHG_VOOC / SVOOC / OPLUS_SVOOC_ID_MIN.
        private const val CHARGER_SUBTYPE_FASTCHG_VOOC = 1
        private const val CHARGER_SUBTYPE_FASTCHG_SVOOC = 2
        private const val OPLUS_SVOOC_ID_MIN = 10
        // Native setAnimationDuration.x. Overlay must be gone by this or the .so wraps.
        private const val VFX_NATIVE_MS = 20_000L
        private const val VFX_FADE_OUT_MS = 450L
        private const val VFX_FLASH_MS = 1_280L
        // Stock CircleView oplus_charge_circle_view_w / mDelayCancelAnimRunnable.
        private const val VFX_CIRCLE_DP = 275f
        // Lap from long-press, capped by [VFX_NATIVE_MS] — does not rebase show time.
        private const val VFX_SPEED_UP_HOLD_MS = 14_000L
        private const val FAST_CHG_TYPE_USB = "/sys/class/oplus_chg/usb/fast_chg_type"
        private const val PPS_CHG_ING = "/sys/class/oplus_chg/battery/ppschg_ing"
        private const val FAST_CHG_TYPE_BATT = "/sys/class/oplus_chg/battery/fast_chg_type"
        // AOSP / PNG windows pass touches through. The GLES path clears
        // FLAG_NOT_TOUCHABLE so a tap can cancel, then restores this.
        private const val PASS_THROUGH_WINDOW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
