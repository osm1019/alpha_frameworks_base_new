package com.android.systemui.axdynamicbar.domain

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings.Global
import com.android.systemui.axdynamicbar.data.IslandEventRepository
import com.android.systemui.axdynamicbar.model.CutoutPlacementHint
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.IslandState
import com.android.systemui.axdynamicbar.model.mirroredStatusBarNotificationKeys
import com.android.systemui.axdynamicbar.model.IslandUiState
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.ScreenRecordChipInteractor
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.settings.GlobalSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.DisplayCutout
import android.view.WindowManager
import kotlin.math.min

@SysUISingleton
class AxDynamicBarInteractor
@Inject
constructor(
    @Application val applicationScope: CoroutineScope,
    @Application private val context: Context,
    private val repository: IslandEventRepository,
    val settings: AxDynamicBarSettings,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    val sliderHapticsViewModelFactory: SliderHapticsViewModel.Factory,
    private val activityStarter: ActivityStarter,
    private val falsingManager: FalsingManager,
    private val indicationController: KeyguardIndicationController,
    private val shadeInteractor: ShadeInteractor,
    private val shadeRepository: ShadeRepository,
    private val zenModeController: ZenModeController,
    private val globalSettings: GlobalSettings,
    private val audioManager: AudioManager,
    private val screenRecordChipInteractor: ScreenRecordChipInteractor,
    private val configurationController: ConfigurationController,
    private val statusBarModeRepository: StatusBarModeRepositoryStore,
) : IslandActions {
    private val _uiState = MutableStateFlow(IslandUiState())
    val uiState: StateFlow<IslandUiState> = _uiState.asStateFlow()

    /** Keyguard indications as lane state. Not in [uiState] events. */
    val keyguardIndications: StateFlow<Map<String, IslandEvent.KeyguardIndication>>
        get() = repository.indicationEvents

    private val autoDismissJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var notifAlertJob: Job? = null
    @Volatile private var transientPinJob: Job? = null
    @Volatile private var transientPinnedEventId: String? = null
    @Volatile private var lastOnKeyguard: Boolean? = null

    private val dismissedEventIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Keys of progress notifications already announced as an alert card, cleared on removal. */
    private val alertedProgressKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override var onFocusableRequested: ((Boolean) -> Unit)? = null

    var onCollapseRequested: (() -> Unit)? = null

    private var isInitialized = false

    private val _isCutoutDisplayEnabled = MutableStateFlow(false)
    val isCutoutDisplayEnabled: StateFlow<Boolean> = _isCutoutDisplayEnabled.asStateFlow()

    private val _isLandscape = MutableStateFlow(false)

    /** Hardware-derived placement hint computed from the display cutout geometry. */
    private val _cutoutPlacementHint = MutableStateFlow(CutoutPlacementHint.CENTER)
    val cutoutPlacementHint: StateFlow<CutoutPlacementHint> = _cutoutPlacementHint.asStateFlow()

    /** Tight physical cutout hole bounds in screen pixels (path outline or circle fallback). */
    private val _cutoutRectPx = MutableStateFlow<Rect?>(null)
    val cutoutRectPx: StateFlow<Rect?> = _cutoutRectPx.asStateFlow()

    @Volatile private var panelBlocking = false
    private val _isPanelExpanded = MutableStateFlow(false)

    val isPanelExpanded: StateFlow<Boolean> = _isPanelExpanded.asStateFlow()
    @get:JvmName("getIsEnabled")
    val isEnabled: StateFlow<Boolean> = settings.isEnabled

    val qsExpansion: StateFlow<Float> = shadeInteractor.qsExpansion
    
    /** Java-friendly listener for isEnabled changes. */
    fun addIsEnabledListener(listener: java.util.function.Consumer<Boolean>): Job {
        return isEnabled
            .onEach { listener.accept(it) }
            .launchIn(applicationScope)
    }

    /** Java-friendly listener for suppressedSlots changes. */
    fun addSuppressedSlotsListener(listener: java.util.function.Consumer<Set<String>>): Job {
        return suppressedSlots
            .onEach { listener.accept(it) }
            .launchIn(applicationScope)
    }

    /**
     * True if a heads-up for this status bar notification key would duplicate what is already shown
     * in the Dynamic Bar (pinned chip or notification alert).
     */
    fun shouldSuppressHeadsUpForMirroredNotification(sbnKey: String): Boolean {
        if (!settings.isEnabled.value) return false
        return sbnKey in _uiState.value.mirroredStatusBarNotificationKeys()
    }

    val suppressedSlots: Flow<Set<String>> = combine(settings.isEnabled, _uiState, _isPanelExpanded) { isEnabled, state, isPanelExpanded ->
        if (!isEnabled || (!state.shouldShow && !isPanelExpanded)) {
            return@combine emptySet<String>()
        }
        val suppressed = mutableSetOf<String>()
        state.events.forEach { event ->
            when (event) {
                is IslandEvent.AospChip -> {
                    when (event.active.key) {
                        "ScreenRecord" -> suppressed.add("screenrecord")
                        "CastToOtherDevice" -> suppressed.add("cast")
                    }
                }
                is IslandEvent.Vpn -> suppressed.add("vpn")
                is IslandEvent.Hotspot -> suppressed.add("hotspot")
                is IslandEvent.Bluetooth -> suppressed.add("bluetooth")
                is IslandEvent.RingerMode -> {
                    suppressed.add("mute")
                    suppressed.add("volume")
                }
                is IslandEvent.Alarm, is IslandEvent.Timer, is IslandEvent.Stopwatch -> {
                    suppressed.add("alarm_clock")
                    suppressed.add("clock")
                    suppressed.add("timer")
                }
                else -> {}
            }
        }
        suppressed
    }.distinctUntilChanged()

    fun isSlotSuppressed(slot: String): Boolean {
        if (!settings.isEnabled.value) return false
        val state = _uiState.value
        if (!state.shouldShow && !_isPanelExpanded.value) return false
        
        return when (slot) {
            "screenrecord" -> state.events.any { it is IslandEvent.AospChip && it.active.key == "ScreenRecord" }
            "vpn" -> state.events.any { it is IslandEvent.Vpn }
            "hotspot" -> state.events.any { it is IslandEvent.Hotspot }
            "cast" -> state.events.any { it is IslandEvent.AospChip && it.active.key == "CastToOtherDevice" }
            "bluetooth" -> state.events.any { it is IslandEvent.Bluetooth }
            "mute", "volume" -> state.events.any { it is IslandEvent.RingerMode }
            "alarm_clock", "clock", "timer" -> state.events.any { it is IslandEvent.Alarm || it is IslandEvent.Timer || it is IslandEvent.Stopwatch }
            else -> false
        }
    }

    val legacyShadeExpansion: StateFlow<Float> = shadeRepository.legacyShadeExpansion
    private val _isOnKeyguard = MutableStateFlow(false)
    val isOnKeyguard: StateFlow<Boolean> = _isOnKeyguard.asStateFlow()
    private val _isKeyguardFadingAway = MutableStateFlow(false)
    val isKeyguardFadingAway: StateFlow<Boolean> = _isKeyguardFadingAway.asStateFlow()
    private val _isBouncerShowing = MutableStateFlow(false)
    val isBouncerShowing: StateFlow<Boolean> = _isBouncerShowing.asStateFlow()
    private val _isDozing = MutableStateFlow(statusBarStateController.isDozing)
    val isDozing: StateFlow<Boolean> = _isDozing.asStateFlow()
    private val _dozeAmount = MutableStateFlow(0f)

    val dozeAmount: StateFlow<Float> = _dozeAmount.asStateFlow()
    @Volatile private var isDreaming = false

    private val statusBlocking: Boolean
        get() = _isDozing.value || isDreaming

    companion object {
        private const val TAG = "AxDynamicBarInteractor"
        private const val NOTIF_ALERT_DURATION_MS = 4500L

        private fun IslandEvent.Notification.isActiveCall(): Boolean {
            val extras = sbn.notification?.extras ?: return false
            return extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
                extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
                extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)
        }
    }

    fun init() {
        if (isInitialized) return
        isInitialized = true

        settings.init()

        _isLandscape.value =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration) {
                    _isLandscape.value =
                        newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                }
            }
        )

        // Cutout rendering needs the top status bar band: in landscape the cutout sits on a
        // side edge, and in fullscreen (immersive) the overlay would float over the app and
        // steal touches around the cutout. In both cases fall back to status bar rendering —
        // the chip then follows the status bar's own visibility (hidden in immersive, shown
        // with the transient bar).
        applicationScope.launch {
            combine(
                settings.showInCutout,
                _isLandscape,
                statusBarModeRepository.defaultDisplay.isInFullscreenMode,
            ) { showInCutout, landscape, fullscreen ->
                showInCutout && !landscape && !fullscreen
            }
                .distinctUntilChanged()
                .collect { active ->
                    val changed = _isCutoutDisplayEnabled.value != active
                    _isCutoutDisplayEnabled.value = active
                    // The expanded card is anchored to the rendering surface; collapse instead
                    // of re-anchoring mid-rotation.
                    if (changed && _isPanelExpanded.value) onCollapseRequested?.invoke()
                }
        }

        // Derive hardware cutout placement hint from the display cutout geometry.
        // LEFT  — cutout centerX < screenWidth / 3
        // RIGHT — cutout centerX > 2 * screenWidth / 3
        // CENTER — otherwise (includes no-cutout devices)
        applicationScope.launch {
            _isCutoutDisplayEnabled.collect { enabled ->
                if (enabled) {
                    refreshCutoutGeometry()
                } else {
                    _cutoutRectPx.value = null
                }
            }
        }

        // Re-derive cutout geometry whenever the display changes (rotation, fold/unfold).
        val displayManager = context.getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) {
                    if (_isCutoutDisplayEnabled.value) {
                        refreshCutoutGeometry()
                    }
                }
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
            },
            Handler(Looper.getMainLooper()),
        )

        repository.system.onChargingStarted = { scheduleAutoDismiss(it) }
        repository.system.onRingerChanged = { scheduleAutoDismiss(it) }
        repository.system.onClipboardCopied = { scheduleAutoDismiss(it) }

        repository.notification.activeMediaPackageProvider = { repository.media.activeMediaPackage }
        repository.notification.onAlarmEvent = {
            scheduleAutoDismiss(it, if (it.isRinging) 30_000L else 5_000L)
        }

        repository.biometric.onBiometricUnlock = { scheduleAutoDismiss(it) }

        applicationScope.launch {
            repository.notification.audioRecordingEvent.collect { event ->
                if (event?.state == RecordingState.SAVED) {
                    scheduleAutoDismiss(event, 5_000L)
                }
            }
        }

        applicationScope.launch {
            repository.notification.notificationFlow.collect { notification ->
                repository.notification.coalesceNotification(notification)
                showNotificationAlert(notification)
            }
        }

        applicationScope.launch {
            repository.notification.notificationRemovedFlow.collect { key ->
                alertedProgressKeys.remove(key)
                val alert = _uiState.value.notificationAlert ?: return@collect
                if (alert.sbn.key == key) dismissNotificationAlert()
            }
        }

        applicationScope.launch {
            combine(
                _uiState.map { state ->
                    state.shouldShow &&
                        state.events.any { it is IslandEvent.Media && it.isPlaying && it.duration > 0L }
                },
                _isPanelExpanded,
                qsExpansion.map { it > 0f },
            ) { mediaActive, panelExpanded, qsOpen ->
                mediaActive && !panelExpanded && !qsOpen
            }.distinctUntilChanged().collect { needsPolling ->
                repository.media.setProgressUpdatesEnabled(needsPolling)
            }
        }

        _isOnKeyguard.value = statusBarStateController.state == StatusBarState.KEYGUARD
        _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
        _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing

        keyguardStateController.addCallback(
            object : KeyguardStateController.Callback {
                override fun onKeyguardFadingAwayChanged() {
                    _isKeyguardFadingAway.value = keyguardStateController.isKeyguardFadingAway
                }

                override fun onPrimaryBouncerShowingChanged() {
                    _isBouncerShowing.value = keyguardStateController.isPrimaryBouncerShowing
                }
            }
        )

        statusBarStateController.addCallback(
            object : StatusBarStateController.StateListener {
                override fun onExpandedChanged(isExpanded: Boolean) {
                    onPanelExpandedChanged(isExpanded)
                }

                override fun onStateChanged(newState: Int) {
                    _isOnKeyguard.value = newState == StatusBarState.KEYGUARD
                    updateChipVisibility()
                }

                override fun onDozingChanged(dozing: Boolean) {
                    _isDozing.value = dozing
                    updateChipVisibility()
                }

                override fun onDozeAmountChanged(linear: Float, eased: Float) {
                    _dozeAmount.value = linear
                }

                override fun onDreamingChanged(dreaming: Boolean) {
                    isDreaming = dreaming
                    updateChipVisibility()
                }
            }
        )

        indicationController.addIndicationListener { type, text ->
            val indicationType = mapIndicationType(type) ?: return@addIndicationListener
            if (text != null && text.isNotEmpty()) {
                val event = IslandEvent.KeyguardIndication(
                    text = text.toString(),
                    indicationType = indicationType,
                )
                repository.updateIndicationEvent(event)
                scheduleAutoDismiss(event)
            } else {
                repository.clearIndicationEvent(indicationType)
            }
        }



        applicationScope.launch {
            settings.isEnabled.collect { enabled ->
                if (enabled) repository.startListening()
                else {
                    // Clear UI state FIRST so all synchronous queries
                    // (shouldSuppressHeadsUp, isSlotSuppressed) see empty state
                    // before any refresh callbacks or teardown flows fire.
                    _uiState.value = IslandUiState()
                    repository.stopListening()
                    autoDismissJobs.values.forEach { it.cancel() }
                    autoDismissJobs.clear()
                    dismissedEventIds.clear()
                    alertedProgressKeys.clear()
                    repository.clearAllIndicationEvents()
                }
            }
        }

        applicationScope.launch {
            settings.disabledEventTypes.collect {
                repository.refreshListeners()
            }
        }

        applicationScope.launch {
            settings.isHeadsUpEnabled.collect { enabled ->
                if (!enabled) dismissNotificationAlert()
            }
        }

        applicationScope.launch {
            combine(
                repository.events,
                settings.disabledEventTypes,
                _isOnKeyguard,
            ) { raw, _, kg ->
                raw.filter { settings.isEventEnabled(it) } to kg
            }.collect { (rawEvents, onKeyguard) ->
                if (!settings.isEnabled.value) return@collect
                val keyguardModeChanged = lastOnKeyguard?.let { it != onKeyguard } ?: false
                lastOnKeyguard = onKeyguard
                dismissedEventIds.removeAll { id -> rawEvents.none { it.id == id } }
                val events = rawEvents.filter { e ->
                    e.id !in dismissedEventIds &&

                        !(onKeyguard && e is IslandEvent.Notification) &&

                        !(onKeyguard && e is IslandEvent.Charging) &&

                        !(onKeyguard && e is IslandEvent.AppSwitch) &&

                        !(onKeyguard && e is IslandEvent.AospChip && e.active.key == "ScreenRecord") &&

                        e !is IslandEvent.KeyguardIndication
                }

                val current = _uiState.value

                val hasNewEvents = events.any { e -> current.events.none { it.id == e.id } }

                val userInitiatedRefresh =
                    events.any { e ->
                        e is IslandEvent.Torch &&
                            current.events.firstOrNull { it.id == e.id }?.let { it != e } == true
                    }

                val hasSignificantChange =
                    !hasNewEvents &&
                        events.any { e ->
                            val old =
                                current.events.firstOrNull { it.id == e.id } ?: return@any false
                            when {
                                e is IslandEvent.Media && old is IslandEvent.Media ->
                                    e.track != old.track || e.artist != old.artist
                                else -> false
                            }
                        }

                val newState =
                    when {
                        events.isEmpty() -> IslandState.HIDDEN
                        panelBlocking || statusBlocking -> IslandState.HIDDEN
                        else -> IslandState.CHIP
                    }

                val prevPinnedId =
                    current.events.getOrNull(current.pinnedEventIndex)?.id

                val shouldTransientPinNewEvent = hasNewEvents && !keyguardModeChanged

                val pinnedIndex =
                    when {
                        shouldTransientPinNewEvent -> {
                            val currentIds = current.events.map { it.id }.toSet()
                            events.indexOfFirst { it.id !in currentIds }.coerceAtLeast(0)
                        }
                        hasSignificantChange -> {
                            val idx =
                                events.indexOfFirst { e ->
                                    val old =
                                        current.events.firstOrNull { it.id == e.id }
                                            ?: return@indexOfFirst false
                                    when {
                                        e is IslandEvent.Media && old is IslandEvent.Media ->
                                            e.track != old.track || e.artist != old.artist
                                        else -> false
                                    }
                                }
                            if (idx >= 0) idx
                            else resolveByIdOrFallback(prevPinnedId, events, current)
                        }
                        userInitiatedRefresh -> 0
                        else -> resolveByIdOrFallback(prevPinnedId, events, current)
                    }
                val shouldReset = hasNewEvents || userInitiatedRefresh || hasSignificantChange

                _uiState.value =
                    IslandUiState(
                        events = events,
                        islandState = newState,
                        pinnedEventIndex = pinnedIndex,
                        manuallyHidden = if (shouldReset) false else current.manuallyHidden,
                        forceVisible = false,
                        notificationAlert = current.notificationAlert,
                    )

                if (shouldTransientPinNewEvent) {
                    val newEvent =
                        events.getOrNull(pinnedIndex)?.takeIf { e ->
                            current.events.none { it.id == e.id }
                        }
                    if (newEvent != null) {
                        scheduleTransientPinReset(newEvent.id)
                    }
                }
            }
        }
    }

    fun cycleNext() {
        val current = _uiState.value
        if (current.events.size <= 1) return
        val next = (current.pinnedEventIndex + 1) % current.events.size
        _uiState.value = current.copy(pinnedEventIndex = next)
    }

    fun cyclePrev() {
        val current = _uiState.value
        if (current.events.size <= 1) return
        val prev = (current.pinnedEventIndex - 1 + current.events.size) % current.events.size
        _uiState.value = current.copy(pinnedEventIndex = prev)
    }

    fun openSettings() {
        val intent = Intent("alpha.settings.DINAMIC_BAR_SETTINGS").apply {
            component = ComponentName(
                "com.android.settings",
                "com.alpha.settings.trampoline.DynamicBarSettingsActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        activityStarter.startActivity(intent, true /* dismissShade */)
    }

    fun pinEventAt(index: Int) {
        // A user pin must not be stolen by scheduleTransientPinReset mid-tap.
        transientPinJob?.cancel()
        transientPinJob = null
        transientPinnedEventId = null
        val current = _uiState.value
        if (index < 0 || index >= current.events.size) return
        _uiState.value = current.copy(pinnedEventIndex = index)
    }

    override fun dismissEvent(event: IslandEvent) {
        autoDismissJobs[event.id]?.cancel()
        autoDismissJobs.remove(event.id)
        if (event.behavior.suppressOnDismiss) {
            dismissedEventIds.add(event.id)
        }

        val current = _uiState.value
        val updatedEvents = current.events.filter { it.id != event.id }
        val newIndex =
            current.pinnedEventIndex.coerceAtMost((updatedEvents.size - 1).coerceAtLeast(0))

        _uiState.value =
            current.copy(
                events = updatedEvents,
                pinnedEventIndex = newIndex,
                islandState =
                    if (updatedEvents.isEmpty()) IslandState.HIDDEN else current.islandState,
            )

        when (event) {
            is IslandEvent.AudioRecording -> repository.notification.clearAudioRecording()
            is IslandEvent.Sports -> {
                repository.smartspace.clearSportsEvent(event.key)
                repository.notification.clearSportsEvent(event.key)
            }
            is IslandEvent.NowPlaying -> {}
            is IslandEvent.PromotedOngoing ->
                repository.notification.clearPromotedOngoing(event.sbn.key)
            is IslandEvent.Media -> repository.media.clear()
            is IslandEvent.Bluetooth -> repository.connectivity.clearBluetooth()
            is IslandEvent.Hotspot -> repository.connectivity.clearHotspot()
            is IslandEvent.Charging -> repository.system.clearCharging()
            is IslandEvent.Alarm -> repository.notification.clearAlarm()
            is IslandEvent.Call -> repository.notification.clearCall(event.sbn.key)
            is IslandEvent.Timer -> repository.notification.clearTimer()
            is IslandEvent.Stopwatch -> repository.notification.clearStopwatch()
            is IslandEvent.RingerMode -> repository.system.clearRinger()
            is IslandEvent.Vpn -> repository.connectivity.clearVpn()
            is IslandEvent.Clipboard -> repository.system.clearClipboard()
            is IslandEvent.Notification -> repository.notification.dismissNotification(event)
            is IslandEvent.AppSwitch -> repository.appTracking.clear()
            is IslandEvent.Torch -> {
                repository.torch.toggleTorch()
                repository.torch.clear()
            }
            is IslandEvent.BiometricUnlock -> repository.biometric.clear()
            is IslandEvent.KeyguardIndication -> repository.clearIndicationEvent(event.indicationType)
            is IslandEvent.AospChip -> {}
        }
    }

    fun getTopEvent(): IslandEvent? = _uiState.value.topEvent

    override fun collapseIsland() {
        onCollapseRequested?.invoke()
    }

    override fun onNotificationInteraction(eventId: String) {
        autoDismissJobs[eventId]?.cancel()
        autoDismissJobs.remove(eventId)
    }

    override fun onNotificationInteractionEnd(eventId: String) {
        val event = _uiState.value.events.find { it.id == eventId } ?: return
        scheduleAutoDismiss(event)
    }

    override fun onNotificationAlertInteractionStart() {
        notifAlertJob?.cancel()
        notifAlertJob = null
    }

    override fun onNotificationAlertInteractionEnd() {
        val current = _uiState.value
        val alert = current.notificationAlert ?: return
        if (alert.isActiveCall()) return
        notifAlertJob = applicationScope.launch {
            delay(NOTIF_ALERT_DURATION_MS)
            dismissNotificationAlert()
        }
    }

    fun dismissNotificationAlert() {
        notifAlertJob?.cancel()
        notifAlertJob = null
        val current = _uiState.value
        if (current.notificationAlert != null) {
            _uiState.value = current.copy(notificationAlert = null)
        }
    }

    private fun shouldSuppressForDndOrRinger(notification: IslandEvent.Notification): Boolean {
        if (notification.isActiveCall()) return false
        if (!settings.isHeadsUpEnabled.value) return true
        val category = notification.sbn.notification?.category
        if (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_ALARM) return false
        val zenMode = zenModeController.zen
        if (zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS ||
            zenMode == Global.ZEN_MODE_ALARMS) return true
        val ringerMode = audioManager.ringerMode
        return ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    private fun showNotificationAlert(
        notification: IslandEvent.Notification,
    ) {
        if (panelBlocking || statusBlocking || _isOnKeyguard.value) return
        if (shouldSuppressForDndOrRinger(notification)) return
        val current = _uiState.value
        val existingAlert = current.notificationAlert
        if (existingAlert != null &&
            existingAlert.isActiveCall() &&
            !notification.isActiveCall()
        ) return

        val hasProgress = notification.progress >= 0 || notification.isProgressIndeterminate
        val isSameKey = existingAlert != null && existingAlert.sbn.key == notification.sbn.key

        if (isSameKey && hasProgress) {
            // Refresh the card's contents as the transfer advances, but leave the running
            // dismissal timer alone so a steady stream of progress updates can't pin the card.
            _uiState.value = current.copy(notificationAlert = notification)
            return
        }

        // A transfer is announced once. Later updates arriving after the card has been dismissed
        // must not pop it back up — the progress keeps showing on the chip.
        if (hasProgress && !alertedProgressKeys.add(notification.sbn.key)) return

        notifAlertJob?.cancel()
        _uiState.value = current.copy(notificationAlert = notification)

        val duration =
            if (!notification.isActiveCall()) NOTIF_ALERT_DURATION_MS else null
        if (duration != null) {
            notifAlertJob = applicationScope.launch {
                delay(duration)
                dismissNotificationAlert()
            }
        }
    }

    override fun togglePlayPause() = repository.media.togglePlayPause()

    override fun skipNext() = repository.media.skipNext()

    override fun skipPrev() = repository.media.skipPrev()

    override fun sendCustomAction(action: String) = repository.media.sendCustomAction(action)

    override fun openMediaOutputSwitcher() = repository.media.openMediaOutputSwitcher()

    override fun disconnectBluetooth(address: String) = repository.connectivity.disconnectBluetooth(address)

    override fun openUrl(url: String) = repository.system.openUrl(url)

    override fun openMediaApp() = repository.media.openMediaApp()

    override fun seekTo(position: Long) = repository.media.seekTo(position)

    override fun setRingerMode(mode: Int) = repository.system.setRingerMode(mode)

    override fun toggleTorch() = repository.torch.toggleTorch()

    /**
     * A tap that leaves the keyguard has to be *classified*, or it counts against us.
     *
     * `BrightLineFalsingManager` records any gesture nobody asked about as false, with a 0.7–0.8
     * penalty — and a clean tap draws the higher one. Two of those push the running belief past
     * 0.9, and `CentralSurfacesImpl`'s belief listener answers by calling
     * `StatusBarKeyguardViewManager.reset(isFalsingReset = true)`, which hides the alternate
     * bouncer and fires `notifyDismissCancelled()`. The pending intent is dropped with the
     * dismissal, so the card looks like it did nothing.
     *
     * Asking here is what registers the gesture as a tap. It also does the job the API is named
     * for: a genuine pocket touch still gets refused.
     */
    private fun tapPassedFalsing(): Boolean = !falsingManager.isFalseTap(FalsingManager.LOW_PENALTY)

    /**
     * Battery usage, not the battery page: `ACTION_POWER_USAGE_SUMMARY` lands on Settings' battery
     * screen, one level above the stats this card's button names. Dismisses the keyguard first —
     * this is a real Settings activity, not an in-place toggle, so it cannot render behind the lock.
     */
    override fun openBatteryStats() {
        if (!tapPassedFalsing()) return
        val intent = Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$PowerUsageAdvancedActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        activityStarter.startActivity(intent, true /* dismissShade */)
    }

    override fun launchNotificationDismissingKeyguard(event: IslandEvent.Notification) {
        val intent = event.sbn.notification?.contentIntent ?: return
        if (!tapPassedFalsing()) return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    override fun launchDismissingKeyguard(intent: PendingIntent) {
        if (!tapPassedFalsing()) return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    override fun setTorchLevel(level: Int) = repository.torch.setLevel(level)

    override fun setTorchLevelTemporary(level: Int) = repository.torch.setLevelTemporary(level)

    override fun copyToClipboard(text: String) = repository.system.copyToClipboard(text)

    override fun copyUriToClipboard(uri: Uri) = repository.system.copyUriToClipboard(uri)

    override fun removeClipboardItem(id: Long) = repository.system.removeClipboardItem(id)

    override fun stopScreenRecording() = screenRecordChipInteractor.stopRecording()

    override fun switchToApp(taskId: Int) = repository.appTracking.switchToApp(taskId)

    fun onPanelExpandedChanged(expanded: Boolean) {
        panelBlocking = expanded
        _isPanelExpanded.value = expanded
        if (expanded) dismissNotificationAlert()
        updateChipVisibility()
    }

    private fun updateChipVisibility() {
        val current = _uiState.value
        val shouldHide = panelBlocking || statusBlocking
        if (shouldHide && current.islandState == IslandState.CHIP) {
            _uiState.value = current.copy(islandState = IslandState.HIDDEN)
        } else if (!shouldHide && current.events.isNotEmpty() && current.islandState == IslandState.HIDDEN && !current.manuallyHidden) {
            _uiState.value = current.copy(islandState = IslandState.CHIP)
        }
    }

    private fun scheduleAutoDismiss(event: IslandEvent, delayOverride: Long? = null) {
        val ms = delayOverride ?: event.behavior.autoDismissMs ?: return
        val eventId = event.id
        autoDismissJobs[eventId]?.cancel()
        autoDismissJobs[eventId] =
            applicationScope.launch {
                delay(ms)
                val current = _uiState.value.events.find { it.id == eventId } ?: event
                dismissEvent(current)
            }
    }

    private fun resolveByIdOrFallback(
        pinnedId: String?,
        events: List<IslandEvent>,
        current: IslandUiState,
    ): Int {
        if (pinnedId != null) {
            val idx = events.indexOfFirst { it.id == pinnedId }
            if (idx >= 0) return idx
        }
        return current.pinnedEventIndex.coerceAtMost(
            (events.size - 1).coerceAtLeast(0)
        )
    }

    private fun scheduleTransientPinReset(eventId: String) {
        transientPinJob?.cancel()
        transientPinnedEventId = eventId
        transientPinJob =
            applicationScope.launch {
                delay(NOTIF_ALERT_DURATION_MS)
                val current = _uiState.value
                val pinnedId = current.events.getOrNull(current.pinnedEventIndex)?.id
                if (pinnedId == transientPinnedEventId) {
                    _uiState.value = current.copy(pinnedEventIndex = 0)
                }
            }
    }

    private fun refreshCutoutGeometry() {
        _cutoutPlacementHint.value = deriveCutoutPlacementHint()
        val wm = context.getSystemService(WindowManager::class.java) ?: run {
            _cutoutRectPx.value = null
            return
        }
        val displayCutout = wm.currentWindowMetrics.windowInsets.displayCutout ?: run {
            _cutoutRectPx.value = null
            return
        }
        _cutoutRectPx.value = physicalCutoutHoleBounds(displayCutout)
    }

    fun getCutoutRect(): Rect? {
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val displayCutout = wm.currentWindowMetrics.windowInsets.displayCutout ?: return null
        return physicalCutoutHoleBounds(displayCutout)
    }

    /**
     * Same outline source as legacy Cutout Progress Ring (`CutoutRingView`): prefer
     * [DisplayCutout.getCutoutPath] bounds (display coordinates), otherwise approximate the first
     * [DisplayCutout.getBoundingRects] entry as a circle with radius min(w,h)/2 (CPR fallback).
     *
     * [CutoutChip] pads this rect by **2dp on all sides** (including top; symmetric vs notch).
     */
    private fun physicalCutoutHoleBounds(displayCutout: DisplayCutout): Rect? {
        val outline = Path()
        val nativePath = displayCutout.cutoutPath
        val rects = displayCutout.boundingRects
        when {
            nativePath != null && !nativePath.isEmpty -> outline.set(nativePath)
            rects.isNotEmpty() -> {
                val r = rects[0]
                outline.addCircle(
                    r.exactCenterX(),
                    r.exactCenterY(),
                    min(r.width(), r.height()).toFloat() / 2f,
                    Path.Direction.CW,
                )
            }
            else -> return null
        }
        val rectF = RectF()
        outline.computeBounds(rectF, /* exact */ true)
        if (rectF.width() <= 0f || rectF.height() <= 0f) return null
        val rect = Rect()
        rectF.roundOut(rect)
        return rect
    }

    /**
     * Derives the cutout placement hint from the physical display cutout geometry.
     * LEFT  — cutout centerX < screenWidth / 3
     * RIGHT — cutout centerX > 2 * screenWidth / 3
     * CENTER — otherwise, or when no cutout is present
     */
    private fun deriveCutoutPlacementHint(): CutoutPlacementHint {
        val wm = context.getSystemService(WindowManager::class.java)
            ?: return CutoutPlacementHint.CENTER
        val displayCutout = wm.currentWindowMetrics.windowInsets.displayCutout
            ?: return CutoutPlacementHint.CENTER
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val rect = physicalCutoutHoleBounds(displayCutout)
            ?: return CutoutPlacementHint.CENTER
        val screenWidth = screenWidthPx.toFloat()
        val centerX = rect.exactCenterX()
        return when {
            centerX < screenWidth / 3f -> CutoutPlacementHint.LEFT
            centerX > screenWidth * 2f / 3f -> CutoutPlacementHint.RIGHT
            else -> CutoutPlacementHint.CENTER
        }
    }

    private fun mapIndicationType(type: Int): IslandEvent.KeyguardIndication.IndicationType? =
        when (type) {
            KeyguardIndicationController.AX_TYPE_BIOMETRIC ->
                IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC
            KeyguardIndicationController.AX_TYPE_TRANSIENT ->
                IslandEvent.KeyguardIndication.IndicationType.TRANSIENT
            KeyguardIndicationController.AX_TYPE_TRUST ->
                IslandEvent.KeyguardIndication.IndicationType.TRUST
            KeyguardIndicationController.AX_TYPE_DISCLOSURE ->
                IslandEvent.KeyguardIndication.IndicationType.DISCLOSURE
            KeyguardIndicationController.AX_TYPE_OWNER_INFO ->
                IslandEvent.KeyguardIndication.IndicationType.OWNER_INFO
            KeyguardIndicationController.AX_TYPE_ALIGNMENT ->
                IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT
            KeyguardIndicationController.AX_TYPE_PERSISTENT_UNLOCK ->
                IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK
            else -> null
        }
}
