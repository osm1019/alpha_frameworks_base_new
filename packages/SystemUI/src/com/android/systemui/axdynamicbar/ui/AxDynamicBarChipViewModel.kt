package com.android.systemui.axdynamicbar.ui

import com.android.systemui.animation.Expandable
import com.android.systemui.axdynamicbar.data.ChargingEventSource
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.media.ax.ui.model.AxLockscreenMediaStyle
import com.android.systemui.qs.ax.data.repository.AxMediaSettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.android.systemui.axdynamicbar.model.CutoutPlacementHint

data class AxDynamicBarChipState(
    val event: IslandEvent,
    val eventCount: Int,
    val pinnedIndex: Int,
    val allEvents: List<IslandEvent>,
    val notificationAlert: IslandEvent.Notification? = null,
)

data class KeyguardBatteryInfo(
    val level: Int,
    val isCharging: Boolean,
    val isPowerSave: Boolean,
    val isWireless: Boolean,
    val timeRemaining: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class AxDynamicBarChipViewModel
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    val interactor: AxDynamicBarInteractor,
    batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
    authController: AuthController,
    udfpsOverlayInteractor: UdfpsOverlayInteractor,
    val keyguardExpansion: AxDynamicBarKeyguardExpansion,
    val statusBarExpansion: AxDynamicBarStatusBarExpansion,
    private val keyguardIndicationController: KeyguardIndicationController,
    chargingEventSource: ChargingEventSource,
    mediaSettingsRepository: AxMediaSettingsRepository,
) {
    /**
     * Lockscreen media style (Glass / Minimal / Waveform). Shared with surface B so A and E follow
     * the same setting when the user has Dynamic Bar keyguard media on.
     */
    val lockscreenMediaStyle: StateFlow<AxLockscreenMediaStyle> =
        mediaSettingsRepository.lockscreenMediaStyle
    val isLowUdfps: StateFlow<Boolean> =
        udfpsOverlayInteractor.udfpsOverlayParams
            .map { params ->
                if (!authController.isUdfpsSupported) return@map false
                val sensorBottom = params.sensorBounds.bottom
                val displayHeight = params.naturalDisplayHeight
                if (displayHeight <= 0) return@map false
                sensorBottom > displayHeight * LOW_UDFPS_THRESHOLD
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val chipState: StateFlow<AxDynamicBarChipState?> =
        interactor.uiState
            .map { uiState ->
                if (!uiState.shouldShow) return@map null
                val alert = uiState.notificationAlert
                val topEvent = uiState.topEvent ?: alert ?: return@map null
                AxDynamicBarChipState(
                    event = topEvent,
                    eventCount = uiState.activeEvents.size,
                    pinnedIndex = uiState.pinnedEventIndex,
                    allEvents = uiState.events,
                    notificationAlert = alert,
                )
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, null)

    val isEnabled: StateFlow<Boolean> = interactor.settings.isEnabled
    val isKeyguardEnabled: StateFlow<Boolean> = interactor.settings.isKeyguardEnabled
    val keyguardBatteryChipMode: StateFlow<Int> = interactor.settings.keyguardBatteryChipMode
    val collapseToRing: StateFlow<Boolean> = interactor.settings.collapseToRing
    val ringGap: StateFlow<Float> = interactor.settings.ringGap
    val ringScaleX: StateFlow<Float> = interactor.settings.ringScaleX
    val ringScaleY: StateFlow<Float> = interactor.settings.ringScaleY
    val ringOffsetXDp: StateFlow<Float> = interactor.settings.ringOffsetXDp
    val ringOffsetYDp: StateFlow<Float> = interactor.settings.ringOffsetYDp
    val ringOpacity: StateFlow<Int> = interactor.settings.ringOpacity
    val ringStrokeDp: StateFlow<Float> = interactor.settings.ringStrokeDp

    /** Hardware-derived cutout placement hint — used by OverlayContent for pill alignment. */
    val cutoutPlacementHint: StateFlow<CutoutPlacementHint> =
        interactor.cutoutPlacementHint
            .stateIn(
                applicationScope,
                SharingStarted.Eagerly,
                CutoutPlacementHint.CENTER,
            )

    /** Physical cutout bounding rect in screen pixels — null when cutout mode is off. */
    val cutoutRectPx: StateFlow<android.graphics.Rect?> =
        interactor.cutoutRectPx
            .stateIn(applicationScope, SharingStarted.Eagerly, null)

    // isActuallyCharging, not batteryInteractor.isCharging: the latter is "plugged in", which
    // stays true under bypass charging and would leave this chip showing a charging session
    // that never advances. This mirrors what the stock lock screen indication does.
    val keyguardBatteryInfo: StateFlow<KeyguardBatteryInfo> =
        combine(
            batteryInteractor.level,
            chargingEventSource.isActuallyCharging,
            batteryInteractor.powerSave,
            batteryInteractor.batteryTimeRemainingEstimate,
        ) { level, charging, powerSave, timeRemaining ->
            KeyguardBatteryInfo(
                level = level ?: 0,
                isCharging = charging,
                isPowerSave = powerSave,
                isWireless = batteryController.isPluggedInWireless,
                timeRemaining = timeRemaining,
            )
        }.stateIn(
            applicationScope,
            SharingStarted.Lazily,
            KeyguardBatteryInfo(0, false, false, false, null),
        )

    /**
     * Lockscreen lane inputs. Occupancy (temporary indication / row / persistent indication)
     * is resolved in composition once the usable width is known.
     */
    internal val keyguardLaneInputs: StateFlow<KeyguardLaneInputs> =
        combine(
            interactor.keyguardIndications,
            keyguardBatteryChipMode,
            keyguardBatteryInfo,
            interactor.uiState,
            interactor.isOnKeyguard,
        ) { indications, mode, battery, ui, onKg ->
            if (!onKg) {
                KeyguardLaneInputs(null, null, null, emptyList())
            } else {
                val values = indications.values
                KeyguardLaneInputs(
                    temporaryIndication = pickTemporaryIndication(values),
                    persistentIndication = pickPersistentIndication(values),
                    battery = batteryForLane(mode, battery),
                    events = ui.events,
                )
            }
        }
            .stateIn(
                applicationScope,
                SharingStarted.Lazily,
                KeyguardLaneInputs(null, null, null, emptyList()),
            )

    init {
        applicationScope.launch {
            interactor.uiState
                .map { state ->
                    state.events
                        .filterIsInstance<IslandEvent.Call>()
                        .firstOrNull { it.callType == "Phone:incoming" }
                        ?.id
                }
                .distinctUntilChanged()
                .collect { incomingCallId ->
                    if (incomingCallId != null) {
                        interactor.dismissNotificationAlert()
                        if (interactor.isOnKeyguard.value) {
                            keyguardExpansion.expand()
                        } else {
                            statusBarExpansion.expand()
                        }
                    } else {
                        statusBarExpansion.collapse()
                        keyguardExpansion.collapse()
                    }
                }
        }
    }

    // Re-compute charging string only when battery state changes. Polling this while idle is costly
    // because KeyguardIndicationController formats through Resources on the main thread.
    val batteryString: StateFlow<String> =
        keyguardBatteryInfo
            .map {
                if (it.isCharging) {
                    formatChargingString(keyguardIndicationController.powerChargingString)
                } else {
                    ""
                }
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, "")

    val isOnKeyguard: StateFlow<Boolean> = interactor.isOnKeyguard

    val isKeyguardFadingAway: StateFlow<Boolean> = interactor.isKeyguardFadingAway

    val isBouncerShowing: StateFlow<Boolean> = interactor.isBouncerShowing

    private val _keyguardCarrierText = MutableStateFlow("")
    val keyguardCarrierText: StateFlow<String> = _keyguardCarrierText.asStateFlow()

    fun updateKeyguardCarrierText(text: String) {
        _keyguardCarrierText.value = text
    }

    private val _chipCenterXFraction = MutableStateFlow(0.5f)
    val chipCenterXFraction: StateFlow<Float> = _chipCenterXFraction.asStateFlow()

    fun updateChipCenterX(fraction: Float) {
        _chipCenterXFraction.value = fraction
    }

    /**
     * Actual on-screen bounds (window px, badge-safe padding included) of the cutout pill or
     * ring — whatever is currently rendered. [AxDynamicBarExpandedPanel] carves the overlay
     * window's touchable region to this rect so the badge/guard slack in the WM window stays
     * visual-only and never swallows touches meant for the app or status bar underneath.
     */
    private val _pillTouchBoundsPx = MutableStateFlow<android.graphics.Rect?>(null)
    val pillTouchBoundsPx: StateFlow<android.graphics.Rect?> = _pillTouchBoundsPx.asStateFlow()

    fun updatePillTouchBounds(bounds: android.graphics.Rect?) {
        _pillTouchBoundsPx.value = bounds
    }

    val isExpanded: StateFlow<Boolean> = statusBarExpansion.isExpanded

    /** True when the user tapped expand-all (full stack UI). */
    private val _showAllEvents = MutableStateFlow(false)
    val showAllEvents: StateFlow<Boolean> = _showAllEvents.asStateFlow()

    val isKeyguardExpanded: StateFlow<Boolean> = keyguardExpansion.isExpanded

    @Volatile private var collapseOnNullJob: Job? = null

    init {
        chipState.onEach { state ->
            if (state == null) {
                collapseOnNullJob?.cancel()
                collapseOnNullJob = applicationScope.launch {
                    delay(200)
                    collapseDynamicBarPanel()
                }
            } else {
                collapseOnNullJob?.cancel()
                collapseOnNullJob = null
            }
        }.launchIn(applicationScope)

        interactor.isPanelExpanded.onEach { if (it) collapseDynamicBarPanel() }.launchIn(applicationScope)
        interactor.qsExpansion.map { it > 0f }.distinctUntilChanged().onEach { if (it) collapseDynamicBarPanel() }.launchIn(applicationScope)
        isBouncerShowing.onEach { if (it) collapseDynamicBarPanel() }.launchIn(applicationScope)
        isOnKeyguard.onEach { if (!it) collapseDynamicBarPanel() }.launchIn(applicationScope)
        combine(interactor.legacyShadeExpansion, isOnKeyguard) { expansion, onKg ->
            onKg && expansion < 0.95f
        }.onEach { dismissing -> if (dismissing) collapseDynamicBarPanel() }.launchIn(applicationScope)
        interactor.isDozing.drop(1).onEach { collapseDynamicBarPanel() }.launchIn(applicationScope)
        interactor.dozeAmount.map { it > 0f }.distinctUntilChanged().onEach { if (it) collapseDynamicBarPanel() }.launchIn(applicationScope)
    }

    private fun collapseDynamicBarPanel() {
        statusBarExpansion.collapse()
        _showAllEvents.value = false
    }

    fun expandPanel() {
        if (chipState.value != null) statusBarExpansion.expand()
    }

    fun collapsePanel() = collapseDynamicBarPanel()

    fun expandAll() {
        if (chipState.value != null) {
            _showAllEvents.value = true
            statusBarExpansion.expand()
        }
    }

    fun togglePanel() {
        if (isExpanded.value) collapsePanel() else expandPanel()
    }

    fun cycleNext() {
        if (_showAllEvents.value) _showAllEvents.value = false
        interactor.cycleNext()
    }

    fun cyclePrev() {
        if (_showAllEvents.value) _showAllEvents.value = false
        interactor.cyclePrev()
    }

    fun openSettings() {
        collapsePanel()
        interactor.openSettings()
    }

    fun dismissEvent(event: IslandEvent) = interactor.dismissEvent(event)

    fun togglePlayPause() = interactor.togglePlayPause()

    fun skipNext() = interactor.skipNext()

    fun skipPrev() = interactor.skipPrev()

    fun toggleTorch() = interactor.toggleTorch()

    fun launchNotificationFromKeyguard(event: IslandEvent.Notification) {
        interactor.launchNotificationDismissingKeyguard(event)
    }

    fun handleAospChipTap(event: IslandEvent.AospChip, expandable: Expandable): Boolean {
        val active = event.active
        return when (val behavior = active.clickBehavior) {
            is OngoingActivityChipModel.ClickBehavior.ShowHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.HideHeadsUpNotification -> {
                behavior.onClick()
                true
            }
            is OngoingActivityChipModel.ClickBehavior.ExpandAction -> {
                behavior.onClick(expandable)
                true
            }
            is OngoingActivityChipModel.ClickBehavior.None -> false
        }
    }

    companion object {
        private const val LOW_UDFPS_THRESHOLD = 0.93f
    }

    private fun formatChargingString(text: String?): String {
        val cleaned = text?.trim()
        return if (cleaned.isNullOrEmpty()) "" else cleaned
    }
}
