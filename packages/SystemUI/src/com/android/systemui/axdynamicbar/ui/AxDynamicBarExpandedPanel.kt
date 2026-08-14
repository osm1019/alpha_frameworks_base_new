package com.android.systemui.axdynamicbar.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.compose.theme.PlatformTheme
import com.android.systemui.axdynamicbar.model.CutoutPlacementHint
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.CutoutCenterContentAreaWidth
import com.android.systemui.axdynamicbar.shared.CutoutCenterIconAreaWidth
import com.android.systemui.axdynamicbar.shared.CutoutPadBottom
import com.android.systemui.axdynamicbar.shared.CutoutPadSide
import com.android.systemui.axdynamicbar.shared.CutoutPadTop
import com.android.systemui.axdynamicbar.shared.ExpandedMaxWidth
import com.android.systemui.axdynamicbar.shared.StatusBarPillWidth
import com.android.systemui.axdynamicbar.ui.compose.ExpandedIslandContent
import com.android.systemui.axdynamicbar.ui.compose.CutoutChip
import com.android.systemui.axdynamicbar.ui.compose.NotificationAlertCard
import com.android.systemui.axdynamicbar.ui.compose.islandSwipeCapture
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import android.os.Handler
import android.os.Looper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.hardware.display.DisplayManager
import android.view.WindowInsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val EXIT_ANIM_DURATION = 300

// Pill-only cutout overlay — extra space beyond token body so stack badge / offsets are not WM-clipped.
private const val CUTOUT_WM_HORIZONTAL_GUARD_DP = 72f
private const val CUTOUT_WM_VERTICAL_BADGE_BLEED_DP = 20f

@SysUISingleton
class AxDynamicBarExpandedPanel
@Inject
constructor(
    @Application private val context: Context,
    private val windowManager: WindowManager,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainHandler: Handler,
    private val viewModel: AxDynamicBarChipViewModel,
) {
    private var overlayView: ComposeView? = null
    private var panelLifecycleOwner: PanelLifecycleOwner? = null
    private var hideOverlayJob: Job? = null

    private val _statusBarHeightPx = MutableStateFlow(0)
    private val statusBarHeightPx: StateFlow<Int> = _statusBarHeightPx.asStateFlow()

    // Pill-only mode: restrict the window's touchable area to the Compose-reported pill/ring
    // bounds so the badge/guard slack in the WM window stays visual-only — touches beside the
    // pill reach the app / status bar below instead of dying in this window (unconsumed events
    // are never re-dispatched to other windows). Expanded/alert modes keep the full frame
    // (scrim taps, card buttons). An empty region while bounds are unknown steals nothing;
    // the pill re-reports on its first layout pass.
    private val internalInsetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { info ->
            // Deliberately independent of isCutoutDisplayEnabled: during the 400ms teardown
            // grace after cutout mode flips off (rotation to landscape, entering fullscreen)
            // the lingering window must keep the carved region, not revert to full frame.
            val pillOnly =
                !viewModel.isExpanded.value &&
                    viewModel.interactor.uiState.value.notificationAlert == null
            if (pillOnly) {
                info.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION
                )
                val bounds = viewModel.pillTouchBoundsPx.value
                if (bounds != null) {
                    info.touchableRegion.set(bounds)
                } else {
                    info.touchableRegion.setEmpty()
                }
            } else {
                info.setTouchableInsets(
                    ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME
                )
            }
        }

    fun init() {
        viewModel.interactor.onCollapseRequested = { viewModel.collapsePanel() }
        viewModel.interactor.onFocusableRequested = { focusable -> setOverlayFocusable(focusable) }

        refreshStatusBarHeight()
        val displayManager = context.getSystemService(DisplayManager::class.java)
        displayManager?.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayChanged(displayId: Int) { refreshStatusBarHeight() }
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
            },
            mainHandler,
        )

        val needsOverlay =
            combine(
                viewModel.isExpanded,
                viewModel.interactor.uiState.map { it.notificationAlert },
                viewModel.isOnKeyguard,
                viewModel.chipState,
                viewModel.interactor.isCutoutDisplayEnabled,
            ) { expanded, alert, onKeyguard, chipState, cutoutMode ->
                (!onKeyguard && (expanded || alert != null)) ||
                    (!onKeyguard && cutoutMode && chipState != null)
            }

        needsOverlay
            .onEach { needed ->
                if (needed) {
                    hideOverlayJob?.cancel()
                    hideOverlayJob = null
                    showOverlay()
                } else {

                    hideOverlayJob?.cancel()
                    hideOverlayJob =
                        applicationScope.launch {
                            delay(400)
                            hideOverlay()
                        }
                }
            }
            .launchIn(applicationScope)

        combine(
            viewModel.isExpanded,
            viewModel.interactor.uiState.map { it.notificationAlert },
            viewModel.interactor.cutoutRectPx,
            viewModel.cutoutPlacementHint,
        ) { expanded, alert, rect, hint ->
            updateOverlayWindow(expanded, alert != null, rect, hint)
        }.launchIn(applicationScope)

        // The touchable region is read during view traversals; schedule one when the reported
        // pill/ring bounds change (collapse animation, ring swap, event change).
        viewModel.pillTouchBoundsPx
            .onEach { ensureMainThread { overlayView?.requestLayout() } }
            .launchIn(applicationScope)

        // Re-apply window geometry when ring offset/scale settings change (user compensation).
        combine(
            viewModel.ringScaleX,
            viewModel.ringScaleY,
            viewModel.ringOffsetXDp,
            viewModel.ringOffsetYDp,
        ) { _, _, _, _ ->
            updateOverlayWindow(
                expanded = viewModel.isExpanded.value,
                hasAlert = viewModel.interactor.uiState.value.notificationAlert != null,
                cutoutRect = viewModel.interactor.cutoutRectPx.value,
                hint = viewModel.cutoutPlacementHint.value,
            )
        }.launchIn(applicationScope)
    }

    private fun refreshStatusBarHeight() {
        _statusBarHeightPx.value = windowManager.currentWindowMetrics
            .windowInsets
            .getInsets(WindowInsets.Type.statusBars())
            .top
    }

    private fun ensureMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    private fun showOverlay() {
        ensureMainThread { showOverlayInternal() }
    }

    private fun showOverlayInternal() {
        if (overlayView != null) return

        val lifecycleOwner = PanelLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        refreshStatusBarHeight()

        val view =
            ComposeView(context).apply {
                setContent { PlatformTheme { OverlayContent(viewModel, statusBarHeightPx) } }
            }

        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        view.setViewTreeOnBackPressedDispatcherOwner(lifecycleOwner)
        panelLifecycleOwner = lifecycleOwner

        val isCurrentlyExpanded = viewModel.isExpanded.value
        // Do not use FLAG_LAYOUT_INSET_DECOR: it shifts the window's content frame below the
        // status bar. The cutout-mode pill must share the same vertical band as the cutout/CPR
        // overlay (physical top), not the inset-decor content area below the bar.
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            if (isCurrentlyExpanded) 0
            else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                if (isCurrentlyExpanded) WindowManager.LayoutParams.MATCH_PARENT
                else WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                flags,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.title = "AxDynamicBarExpanded"

        // Apply mode geometry before the window is added: in pill-only mode the window must
        // never exist at its full-width default, or the first frame is a touch-stealing strip
        // across the top of the screen.
        applyWindowGeometry(
            params,
            expanded = isCurrentlyExpanded,
            hasAlert = viewModel.interactor.uiState.value.notificationAlert != null,
            cutoutRect = viewModel.interactor.cutoutRectPx.value,
            hint = viewModel.cutoutPlacementHint.value,
        )

        windowManager.addView(view, params)
        view.viewTreeObserver.addOnComputeInternalInsetsListener(internalInsetsListener)
        overlayView = view
    }

    private fun hideOverlay() {
        ensureMainThread { hideOverlayInternal() }
    }

    private fun hideOverlayInternal() {
        shrinkRunnable?.let { mainHandler.removeCallbacks(it) }
        shrinkRunnable = null
        overlayView?.let { view ->
            view.viewTreeObserver.removeOnComputeInternalInsetsListener(internalInsetsListener)
            panelLifecycleOwner?.apply {
                handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            windowManager.removeViewImmediate(view)
        }
        overlayView = null
        panelLifecycleOwner = null
    }

    private var shrinkRunnable: Runnable? = null

    private fun updateOverlayWindow(
        expanded: Boolean,
        hasAlert: Boolean,
        cutoutRect: android.graphics.Rect?,
        hint: com.android.systemui.axdynamicbar.model.CutoutPlacementHint,
    ) = ensureMainThread {
        val view = overlayView ?: return@ensureMainThread
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return@ensureMainThread
        if (applyWindowGeometry(params, expanded, hasAlert, cutoutRect, hint)) {
            windowManager.updateViewLayout(view, params)
        }
    }

    /**
     * Mutates [params] for the current mode. Returns false when neither branch applies
     * (pill-only mode without a cutout rect) so callers skip the WM relayout.
     */
    private fun applyWindowGeometry(
        params: WindowManager.LayoutParams,
        expanded: Boolean,
        hasAlert: Boolean,
        cutoutRect: android.graphics.Rect?,
        hint: com.android.systemui.axdynamicbar.model.CutoutPlacementHint,
    ): Boolean {
        val density = context.resources.displayMetrics.density
        val padSidePx = (CutoutPadSide.value * density).roundToInt()
        val padBotPx = (CutoutPadBottom.value * density).roundToInt()
        val padTopPx = (CutoutPadTop.value * density).roundToInt()
        // Conservative guard: ~12dp badge offset + double-digit count + font scale / density drift.
        val cutoutWmHorizontalGuardPx =
            (CUTOUT_WM_HORIZONTAL_GUARD_DP * density).roundToInt().coerceAtLeast(40)

        if (expanded || hasAlert) {
            params.flags = if (expanded) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            } else {
                // Notification alert: keep NOT_FOCUSABLE so we don't steal focus from the app
                // (which would dismiss the keyboard and block IME re-appearance).
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (expanded) {
                params.setBlurBehindRadius(20)
            } else {
                params.setBlurBehindRadius(0)
            }
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = if (expanded) WindowManager.LayoutParams.MATCH_PARENT
                            else WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
            params.x = 0
            params.y = 0
        } else if (cutoutRect != null) {
            // Pill-only mode: shrink window to the chip’s token-sized bounds + badge overflow.
            params.flags = (params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                (WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND).inv()
            params.dimAmount = 0f
            params.setBlurBehindRadius(0)

            // Apply user geometry settings to derive the effective cutout rect.
            // Mirrors the same logic in CutoutChip so WM window and Compose layout agree.
            val scaleX = viewModel.ringScaleX.value
            val scaleY = viewModel.ringScaleY.value
            val offsetXPx = viewModel.ringOffsetXDp.value * density
            val offsetYPx = viewModel.ringOffsetYDp.value * density
            val effectiveW = (cutoutRect.width() * scaleX).toInt()
            val effectiveH = (cutoutRect.height() * scaleY).toInt()
            val centerX = cutoutRect.centerX() + offsetXPx
            val centerY = cutoutRect.centerY() + offsetYPx
            val effectiveRect = android.graphics.Rect(
                (centerX - effectiveW / 2f).toInt(),
                (centerY - effectiveH / 2f).toInt(),
                (centerX + effectiveW / 2f).toInt(),
                (centerY + effectiveH / 2f).toInt(),
            )

            val cutoutGapPx = effectiveRect.width() + (padSidePx * 2)
            val pillTopPx = (effectiveRect.top - padTopPx).coerceAtLeast(0)
            val pillHeightPx = (effectiveRect.bottom + padBotPx) - pillTopPx
            val badgeOverflowY =
                (CUTOUT_WM_VERTICAL_BADGE_BLEED_DP * density).roundToInt().coerceAtLeast(8)

            // Ceil composite body width so fractional Dp→px never underestimates vs Compose layout.
            val centerBodyPx =
                ceil(
                        CutoutCenterIconAreaWidth.value * density + cutoutGapPx.toDouble() +
                            CutoutCenterContentAreaWidth.value * density,
                    )
                    .toInt()
            val lrBodyPx =
                ceil(cutoutGapPx.toDouble() + StatusBarPillWidth.value * density.toDouble()).toInt()

            params.gravity = Gravity.TOP or Gravity.START
            // Window starts at physical top; Compose applies pillTopDp padding inside CutoutChip.
            params.height = pillTopPx + pillHeightPx + badgeOverflowY
            params.y = 0

            when (hint) {
                com.android.systemui.axdynamicbar.model.CutoutPlacementHint.LEFT -> {
                    params.width = lrBodyPx + cutoutWmHorizontalGuardPx
                    params.x = effectiveRect.left - padSidePx
                }

                com.android.systemui.axdynamicbar.model.CutoutPlacementHint.RIGHT -> {
                    // Badge sits on the left of the pill; reserve space so it isn’t clipped.
                    params.width = lrBodyPx + cutoutWmHorizontalGuardPx
                    params.x = (effectiveRect.right + padSidePx) - lrBodyPx - cutoutWmHorizontalGuardPx
                }

                com.android.systemui.axdynamicbar.model.CutoutPlacementHint.CENTER -> {
                    // Center the WM window on the cutout so content aligned with TopCenter + fillMaxWidth
                    // stays anchored (asymmetric params.x broke horizontal alignment when collapsing).
                    params.width = centerBodyPx + cutoutWmHorizontalGuardPx
                    params.x = effectiveRect.centerX() - params.width / 2
                }
            }
        } else {
            return false
        }

        return true
    }

    private fun setOverlayFocusable(focusable: Boolean) = ensureMainThread {
        val view = overlayView ?: return@ensureMainThread
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return@ensureMainThread
        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager.updateViewLayout(view, params)
    }
}

@Composable
private fun OverlayContent(viewModel: AxDynamicBarChipViewModel, statusBarHeightPx: StateFlow<Int>) {
    val statusBarHeight by statusBarHeightPx.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val topPad = with(density) { statusBarHeight.toDp() } + 8.dp
    val chipState by viewModel.chipState.collectAsStateWithLifecycle()
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()
    val uiState by viewModel.interactor.uiState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val notifAlert = uiState.notificationAlert
    val compactNotifs by viewModel.interactor.settings.compactNotifications.collectAsStateWithLifecycle()
    val isCutoutMode by viewModel.interactor.isCutoutDisplayEnabled.collectAsStateWithLifecycle()
    val placementHint by viewModel.cutoutPlacementHint.collectAsStateWithLifecycle()

    val cutoutRectPx by viewModel.cutoutRectPx.collectAsStateWithLifecycle()
    val showAllEvents by viewModel.showAllEvents.collectAsStateWithLifecycle()

    val lastAlert = remember { mutableStateOf<IslandEvent.Notification?>(null) }
    if (notifAlert != null) lastAlert.value = notifAlert

    val expandedVisible = remember { MutableTransitionState(false) }
    val showNotif = !isExpanded && notifAlert != null
    val notifVisible = remember { MutableTransitionState(false) }

    LaunchedEffect(isExpanded) {
        if (isExpanded && isCutoutMode) {
            // Wait one frame for the window to resize from pill bounds to MATCH_PARENT
            // before starting the enter animation, preventing clipping.
            kotlinx.coroutines.delay(32)
        }
        expandedVisible.targetState = isExpanded
    }

    LaunchedEffect(showNotif) {
        notifVisible.targetState = showNotif
    }

    LaunchedEffect(chipState) {
        if (chipState?.allEvents.isNullOrEmpty() && isExpanded) {
            viewModel.collapsePanel()
        }
    }

    val origin = if (isCutoutMode && cutoutRectPx != null) {
        // Animation originates from the cutout position
        val screenWidthPx = with(density) {
            LocalConfiguration.current.screenWidthDp.dp.toPx()
        }
        val cutoutCenterX = cutoutRectPx!!.centerX().toFloat()
        TransformOrigin(
            (cutoutCenterX / screenWidthPx).coerceIn(0f, 1f),
            0f,
        )
    } else {
        TransformOrigin(0.5f, 0f)
    }

    // In cutout mode the expanded card aligns to the cutout side so it doesn't overlap the notch.
    // LEFT cutout  → card anchored to the right (TopEnd)
    // RIGHT cutout → card anchored to the left (TopStart)
    // CENTER       → centered as normal
    val expandedAlignment = if (isCutoutMode) when (placementHint) {
        CutoutPlacementHint.LEFT -> Alignment.TopEnd
        CutoutPlacementHint.RIGHT -> Alignment.TopStart
        CutoutPlacementHint.CENTER -> Alignment.TopCenter
    } else Alignment.TopCenter

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top),
    ) {
        AnimatedVisibility(
            visibleState = expandedVisible,
            enter = fadeIn(tween(250)) + scaleIn(
                animationSpec = tween(350),
                initialScale = 0.4f,
                transformOrigin = origin,
            ),
            // Both channels run the same length so the panel is still on screen for the whole
            // shrink. Fading out in 200ms while the scale ran 250 meant the last fifth of the
            // motion happened at zero opacity, and the standard curve spends most of its distance
            // up front — so the panel lurched inward and then blinked, rather than leaving.
            exit = fadeOut(tween(EXIT_ANIM_DURATION, easing = FastOutLinearInEasing)) + scaleOut(
                animationSpec = tween(EXIT_ANIM_DURATION, easing = FastOutLinearInEasing),
                targetScale = 0.4f,
                transformOrigin = origin,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Final)
                            val downPos = down.position
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    val dx = change.position.x - downPos.x
                                    val dy = change.position.y - downPos.y
                                    val isSwipeUp = dy < -slop * 3 && abs(dy) > abs(dx)
                                    val isTap = dx * dx + dy * dy <= slop * slop
                                    if (isSwipeUp || isTap) {
                                        viewModel.collapsePanel()
                                    }
                                    break
                                }
                            }
                        }
                    }
                    .padding(top = topPad),
                contentAlignment = expandedAlignment,
            ) {
                chipState?.let { state ->
                    ExpandedIslandContent(
                        events = if (showAllEvents) state.allEvents else listOf(state.event),
                        interactor = viewModel.interactor,
                        onCollapse = { viewModel.collapsePanel() },
                        pinnedEventId = if (showAllEvents) null else state.event.id,
                        stackSize = state.allEvents.size,
                        onExpandAll = if (!showAllEvents && state.allEvents.size > 1) {
                            { viewModel.expandAll() }
                        } else null,
                        hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                    )
                }
            }
        }

        val alertOrigin = TransformOrigin(0.5f, 0f)

        if (isCutoutMode) {
            val rect = cutoutRectPx
            if (rect != null) {
                CutoutChip(
                    viewModel = viewModel,
                    cutoutRectPx = rect,
                    placementHint = placementHint,
                )
            }
        }

        if (!isCutoutMode && isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .islandSwipeCapture(viewModel = viewModel),
            )
        }

        AnimatedVisibility(
            visibleState = notifVisible,
            enter = fadeIn(tween(300)) + scaleIn(
                animationSpec = tween(300),
                initialScale = 0.4f,
                transformOrigin = alertOrigin,
            ),
            exit = fadeOut(tween(250)) + scaleOut(
                animationSpec = tween(250),
                targetScale = 0.4f,
                transformOrigin = alertOrigin,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = topPad),
                contentAlignment = Alignment.TopCenter,
            ) {
                val alert = lastAlert.value
                if (alert != null) {
                    NotificationAlertCard(
                        notification = alert,
                        interactor = viewModel.interactor,
                        onDismiss = { viewModel.interactor.dismissNotificationAlert() },
                        initiallyCompact = compactNotifs,
                        modifier =
                            Modifier.widthIn(max = ExpandedMaxWidth)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

private class PanelLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val backDispatcher = OnBackPressedDispatcher()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = backDispatcher

    init {
        savedStateRegistryController.performRestore(null)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
