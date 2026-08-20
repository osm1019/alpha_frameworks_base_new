package com.android.systemui.axdynamicbar.domain

import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings.Global
import android.provider.Settings.Secure
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.EVENT_TYPE_IDS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

@SysUISingleton
class AxDynamicBarSettings @Inject constructor(
    @Main private val mainHandler: Handler,
    private val secureSettings: SecureSettings,
    private val systemSettings: SystemSettings,
    private val globalSettings: GlobalSettings,
) {
    companion object {
        const val KEY_ENABLED = "ax_dynamic_bar_enabled"
        const val KEY_EVENTS = "ax_dynamic_bar_events"
        const val KEY_KEYGUARD_ENABLED = "ax_dynamic_bar_keyguard_enabled"
        const val KEY_COMPACT_NOTIFICATIONS = "ax_dynamic_bar_compact_notifications"
        // Written by SystemSettingSwitchPreference in dynamic_bar.xml → Settings.System
        const val KEY_SHOW_IN_CUTOUT = "cutout_ring_enabled"
        const val KEY_COLLAPSE_TO_RING = "cutout_collapse_to_ring"

        const val KEY_RING_GAP_X1000 = "cutout_ring_gap_x1000"
        const val KEY_RING_SCALE_X_X1000 = "cutout_ring_scale_x_x1000"
        const val KEY_RING_SCALE_Y_X1000 = "cutout_ring_scale_y_x1000"
        const val KEY_RING_OFFSET_X_DP10 = "cutout_ring_offset_x_dp10"
        const val KEY_RING_OFFSET_Y_DP10 = "cutout_ring_offset_y_dp10"
        const val KEY_RING_OPACITY = "cutout_ring_opacity"
        const val KEY_RING_STROKE_DP10 = "cutout_ring_stroke_dp10"

        private const val DEF_RING_GAP_X1000 = 1155 // 1.155f
        private const val DEF_RING_SCALE_X1000 = 1000 // 1.0f
        private const val DEF_RING_OFFSET_DP10 = 0 // 0.0dp
        private const val DEF_RING_OPACITY = 90 // 90%
        private const val DEF_RING_STROKE_DP10 = 15 // 1.5dp
    }

    private val _isEnabled = MutableStateFlow(false)
    @get:JvmName("getIsEnabled") val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isKeyguardEnabled = MutableStateFlow(true)
    val isKeyguardEnabled: StateFlow<Boolean> = _isKeyguardEnabled.asStateFlow()

    private val _compactNotifications = MutableStateFlow(true)
    val compactNotifications: StateFlow<Boolean> = _compactNotifications.asStateFlow()

    private val _showInCutout = MutableStateFlow(false)
    val showInCutout: StateFlow<Boolean> = _showInCutout.asStateFlow()

    private val _collapseToRing = MutableStateFlow(false)
    val collapseToRing: StateFlow<Boolean> = _collapseToRing.asStateFlow()

    private val _ringGap = MutableStateFlow(1.155f)
    val ringGap: StateFlow<Float> = _ringGap.asStateFlow()

    private val _ringScaleX = MutableStateFlow(1f)
    val ringScaleX: StateFlow<Float> = _ringScaleX.asStateFlow()

    private val _ringScaleY = MutableStateFlow(1f)
    val ringScaleY: StateFlow<Float> = _ringScaleY.asStateFlow()

    private val _ringOffsetXDp = MutableStateFlow(0f)
    val ringOffsetXDp: StateFlow<Float> = _ringOffsetXDp.asStateFlow()

    private val _ringOffsetYDp = MutableStateFlow(0f)
    val ringOffsetYDp: StateFlow<Float> = _ringOffsetYDp.asStateFlow()

    private val _ringOpacity = MutableStateFlow(90)
    val ringOpacity: StateFlow<Int> = _ringOpacity.asStateFlow()

    private val _ringStrokeDp = MutableStateFlow(3f)
    val ringStrokeDp: StateFlow<Float> = _ringStrokeDp.asStateFlow()

    private val _isHeadsUpEnabled = MutableStateFlow(true)
    val isHeadsUpEnabled: StateFlow<Boolean> = _isHeadsUpEnabled.asStateFlow()

    private val _disabledEventTypes = MutableStateFlow<Set<String>>(emptySet())
    val disabledEventTypes: StateFlow<Set<String>> = _disabledEventTypes.asStateFlow()

    /**
     * Settings' "Pin media player" (`Settings.Secure.MEDIA_CONTROLS_RESUME`).
     *
     * Read here for what the name says rather than what AOSP uses it for: a pinned card is one
     * the user has asked to keep, so the stack card refuses swipe-to-dismiss while it is on.
     */
    private val _isMediaPinned = MutableStateFlow(true)
    val isMediaPinned: StateFlow<Boolean> = _isMediaPinned.asStateFlow()

    init {
        refresh()
    }

    private val settingsObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh()
            }
        }

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        refresh()
        secureSettings.registerContentObserverForUserSync(
            KEY_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_EVENTS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_KEYGUARD_ENABLED,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        secureSettings.registerContentObserverForUserSync(
            KEY_COMPACT_NOTIFICATIONS,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        // KEY_SHOW_IN_CUTOUT is in Settings.System (written by SystemSettingSwitchPreference)
        systemSettings.registerContentObserverForUserSync(
            KEY_SHOW_IN_CUTOUT,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        systemSettings.registerContentObserverForUserSync(
            KEY_COLLAPSE_TO_RING,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        for (key in arrayOf(
            KEY_RING_GAP_X1000, KEY_RING_SCALE_X_X1000, KEY_RING_SCALE_Y_X1000,
            KEY_RING_OFFSET_X_DP10, KEY_RING_OFFSET_Y_DP10, KEY_RING_OPACITY,
            KEY_RING_STROKE_DP10,
        )) {
            systemSettings.registerContentObserverForUserSync(
                key, false, settingsObserver, UserHandle.USER_ALL,
            )
        }
        globalSettings.registerContentObserverSync(
            Global.HEADS_UP_NOTIFICATIONS_ENABLED,
            false,
            settingsObserver,
        )
        secureSettings.registerContentObserverForUserSync(
            Secure.MEDIA_CONTROLS_RESUME,
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
    }

    fun destroy() {
        if (!initialized) return
        initialized = false
        secureSettings.getContentResolver().unregisterContentObserver(settingsObserver)
        systemSettings.getContentResolver().unregisterContentObserver(settingsObserver)
        globalSettings.getContentResolver().unregisterContentObserver(settingsObserver)
    }

    private fun refresh() {
        _isEnabled.value =
            secureSettings.getIntForUser(KEY_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        _isKeyguardEnabled.value =
            secureSettings.getIntForUser(KEY_KEYGUARD_ENABLED, 1, UserHandle.USER_CURRENT) == 1
        _compactNotifications.value =
            secureSettings.getIntForUser(KEY_COMPACT_NOTIFICATIONS, 1, UserHandle.USER_CURRENT) == 1
        _showInCutout.value =
            systemSettings.getIntForUser(KEY_SHOW_IN_CUTOUT, 0, UserHandle.USER_CURRENT) == 1
        _collapseToRing.value =
            systemSettings.getIntForUser(KEY_COLLAPSE_TO_RING, 0, UserHandle.USER_CURRENT) == 1
        _ringGap.value =
            systemSettings.getIntForUser(KEY_RING_GAP_X1000, DEF_RING_GAP_X1000, UserHandle.USER_CURRENT) / 1000f
        _ringScaleX.value =
            systemSettings.getIntForUser(KEY_RING_SCALE_X_X1000, DEF_RING_SCALE_X1000, UserHandle.USER_CURRENT) / 1000f
        _ringScaleY.value =
            systemSettings.getIntForUser(KEY_RING_SCALE_Y_X1000, DEF_RING_SCALE_X1000, UserHandle.USER_CURRENT) / 1000f
        _ringOffsetXDp.value =
            systemSettings.getIntForUser(KEY_RING_OFFSET_X_DP10, DEF_RING_OFFSET_DP10, UserHandle.USER_CURRENT) / 10f
        _ringOffsetYDp.value =
            systemSettings.getIntForUser(KEY_RING_OFFSET_Y_DP10, DEF_RING_OFFSET_DP10, UserHandle.USER_CURRENT) / 10f
        _ringOpacity.value =
            systemSettings.getIntForUser(KEY_RING_OPACITY, DEF_RING_OPACITY, UserHandle.USER_CURRENT).coerceIn(0, 100)
        _ringStrokeDp.value =
            systemSettings.getIntForUser(KEY_RING_STROKE_DP10, DEF_RING_STROKE_DP10, UserHandle.USER_CURRENT) / 10f
        _isHeadsUpEnabled.value =
            globalSettings.getInt(Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1) == 1
        _isMediaPinned.value =
            secureSettings.getIntForUser(
                Secure.MEDIA_CONTROLS_RESUME,
                1,
                UserHandle.USER_CURRENT,
            ) == 1

        val json = secureSettings.getStringForUser(KEY_EVENTS, UserHandle.USER_CURRENT) ?: ""
        _disabledEventTypes.value =
            try {
                if (json.isBlank()) emptySet()
                else {
                    val arr = JSONArray(json)
                    (0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
                }
            } catch (_: Exception) {
                emptySet()
            }
    }

    fun isEventEnabled(event: IslandEvent): Boolean {
        val typeId = EVENT_TYPE_IDS[event::class.java] ?: return true
        return typeId !in _disabledEventTypes.value
    }

    fun isNotificationEventsActive(): Boolean =
        _isEnabled.value && "notification" !in _disabledEventTypes.value
}
