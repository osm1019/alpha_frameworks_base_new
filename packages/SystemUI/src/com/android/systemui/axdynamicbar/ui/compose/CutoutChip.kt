/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.Rect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.axdynamicbar.model.CutoutPlacementHint
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AlphaSecondary
import com.android.systemui.axdynamicbar.shared.AlphaTertiary
import com.android.systemui.axdynamicbar.shared.IslandStackCountBadge
import com.android.systemui.axdynamicbar.shared.PillPrimary
import com.android.systemui.axdynamicbar.shared.SizeBadge
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.TsBadge
import com.android.systemui.axdynamicbar.shared.CutoutCenterCollapsePolicy
import com.android.systemui.axdynamicbar.shared.CutoutCenterContentAreaWidth
import com.android.systemui.axdynamicbar.shared.CutoutCenterIconAreaWidth
import com.android.systemui.axdynamicbar.shared.CutoutPadBottom
import com.android.systemui.axdynamicbar.shared.CutoutPadSide
import com.android.systemui.axdynamicbar.shared.CutoutPadTop
import com.android.systemui.axdynamicbar.shared.StatusBarContentWidth
import com.android.systemui.media.ax.ui.compose.MediaChrome
import com.android.systemui.axdynamicbar.shared.StatusBarIconWidth
import com.android.systemui.axdynamicbar.shared.StatusBarPillWidth
import com.android.systemui.axdynamicbar.shared.chipTintAccentFor
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.islandGlassChrome
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.android.systemui.axdynamicbar.shared.displayLevel
import com.android.systemui.axdynamicbar.shared.displayMode
import com.android.systemui.axdynamicbar.shared.displayTimeRemaining
import com.android.systemui.axdynamicbar.shared.iconKeyFor
import com.android.systemui.axdynamicbar.shared.textKeyFor
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipState
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import kotlin.math.abs

// Center lane widths:
// Left lane must fit the largest icon used in chip mode (media icon = 18dp)
// plus row horizontal paddings (4dp + 4dp) to avoid clipping.
private val CenterLeftLaneWidth = CutoutCenterIconAreaWidth
private val CenterRightLaneWidth = CutoutCenterContentAreaWidth

// Content padding inside the pill (between pill edge and content).
private val ContentPad = 8.dp

// Touchable-region slack beyond the reported pill/ring bounds. Horizontal covers the stack
// badge protrusion (12dp offset + 14dp badge outside the pill edge) plus tap comfort; bottom
// covers the badge's 4dp downward offset. Must stay within the WM window slack
// (CUTOUT_WM_HORIZONTAL_GUARD_DP / CUTOUT_WM_VERTICAL_BADGE_BLEED_DP) or touches get clipped
// at the window edge instead of the region edge.
private val TouchRegionPadHorizontal = 28.dp
private val TouchRegionPadTop = 4.dp
private val TouchRegionPadBottom = 12.dp

private fun reportTouchBounds(
    viewModel: AxDynamicBarChipViewModel,
    density: androidx.compose.ui.unit.Density,
    coords: androidx.compose.ui.layout.LayoutCoordinates,
) {
    val b = coords.boundsInWindow()
    if (b.width <= 0f || b.height <= 0f) return
    with(density) {
        viewModel.updatePillTouchBounds(
            Rect(
                (b.left - TouchRegionPadHorizontal.toPx()).toInt().coerceAtLeast(0),
                (b.top - TouchRegionPadTop.toPx()).toInt().coerceAtLeast(0),
                (b.right + TouchRegionPadHorizontal.toPx()).toInt(),
                (b.bottom + TouchRegionPadBottom.toPx()).toInt(),
            )
        )
    }
}

private fun collapseIdentityKey(event: IslandEvent): String = when (event) {
    is IslandEvent.Timer -> "timer:${event.isPaused}"
    is IslandEvent.Stopwatch -> "sw:${event.isRunning}"
    is IslandEvent.AudioRecording -> "audio:${event.state}"
    is IslandEvent.AospChip -> "aosp:${event.active.key}"
    is IslandEvent.Notification ->
        if (event.callStartTimeMs > 0L) "call:${event.appName}" else event.id
    else -> ""
}

@Composable
private fun chargingRightLaneText(event: IslandEvent.Charging): String {
    val right = event.displayTimeRemaining() ?: event.displayMode()
    return "${event.displayLevel()} • $right"
}

/**
 * Cutout-mode pill that embraces the display cutout.
 *
 * The pill background is sized to the cutout rect + padding, hugging the cutout.
 * Content is placed outside the cutout zone with a safe margin.
 *
 * Padding: **4dp top, 3dp bottom/sides** around the physical cutout rect. The asymmetric
 * top compensates for the system offset; same idea as CPR's compact island pads — when the
 * hole touches `y = 0`, the top pad draws slightly above the logical top for a symmetric
 * pill rather than a thinner band on the notch edge.
 *
 * Layout:
 *   LEFT   — pill starts at cutoutLeft - PAD, extends right; content right of cutout
 *   RIGHT  — pill ends at cutoutRight + PAD, extends left; content left of cutout
 *   CENTER — single continuous pill; icon + count left, text right, both avoiding cutout
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CutoutChip(
    modifier: Modifier = Modifier,
    viewModel: AxDynamicBarChipViewModel,
    cutoutRectPx: Rect,
    placementHint: CutoutPlacementHint,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val keyguardCarrier by viewModel.keyguardCarrierText.collectAsStateWithLifecycle()
    val carrierName = if (isOnKeyguard) keyguardCarrier.takeIf { it.isNotBlank() } else null
    val collapseToRing by viewModel.collapseToRing.collectAsStateWithLifecycle()
    val ringGap by viewModel.ringGap.collectAsStateWithLifecycle()
    val ringScaleX by viewModel.ringScaleX.collectAsStateWithLifecycle()
    val ringScaleY by viewModel.ringScaleY.collectAsStateWithLifecycle()
    val ringOffsetXDp by viewModel.ringOffsetXDp.collectAsStateWithLifecycle()
    val ringOffsetYDp by viewModel.ringOffsetYDp.collectAsStateWithLifecycle()
    val ringOpacity by viewModel.ringOpacity.collectAsStateWithLifecycle()
    val ringStrokeDp by viewModel.ringStrokeDp.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val motionScheme = MaterialTheme.motionScheme

    // Padding around the cutout rect to form the pill boundary.
    val padTopDp = CutoutPadTop
    val padBotDp = CutoutPadBottom
    val padSideDp = CutoutPadSide

    // Raw pill geometry from the cutout rect + padding.
    val cutoutTopDp = with(density) { cutoutRectPx.top.toDp() }
    val cutoutBotDp = with(density) { cutoutRectPx.bottom.toDp() }
    val cutoutLeftDp = with(density) { cutoutRectPx.left.toDp() }
    val cutoutRightDp = with(density) { cutoutRectPx.right.toDp() }
    val rawCutoutWidthDp = cutoutRightDp - cutoutLeftDp
    val rawCutoutHeightDp = cutoutBotDp - cutoutTopDp

    // Apply user scale settings to the cutout zone the pill avoids.
    // scaleX/Y compensate for bad cutout paths — if the reported rect is too narrow/short,
    // the user can scale it up so the pill correctly avoids the physical hole.
    val cutoutWidthDp = rawCutoutWidthDp * ringScaleX
    val cutoutHeightDp = rawCutoutHeightDp * ringScaleY

    // Effective cutout center (raw center + user offset).
    val rawCutoutCenterXDp = cutoutLeftDp + rawCutoutWidthDp / 2
    val rawCutoutCenterYDp = cutoutTopDp + rawCutoutHeightDp / 2
    val effectiveCenterXDp = rawCutoutCenterXDp + ringOffsetXDp.dp
    val effectiveCenterYDp = rawCutoutCenterYDp + ringOffsetYDp.dp

    // Effective cutout bounds (scaled around center + offset applied).
    val effectiveCutoutLeftDp = effectiveCenterXDp - cutoutWidthDp / 2
    val effectiveCutoutRightDp = effectiveCenterXDp + cutoutWidthDp / 2
    val effectiveCutoutTopDp = effectiveCenterYDp - cutoutHeightDp / 2
    val effectiveCutoutBotDp = effectiveCenterYDp + cutoutHeightDp / 2

    // Pill top/height from effective bounds.
    val pillTopDp = (effectiveCutoutTopDp - padTopDp).coerceAtLeast(0.dp)
    val pillHeightDp = (effectiveCutoutBotDp + padBotDp) - pillTopDp

    // Effective rect in px — passed to AnimatedCutoutRing so ring and pill share the same center.
    val effectiveCutoutRectPx = with(density) {
        Rect(
            effectiveCutoutLeftDp.toPx().toInt(),
            effectiveCutoutTopDp.toPx().toInt(),
            effectiveCutoutRightDp.toPx().toInt(),
            effectiveCutoutBotDp.toPx().toInt(),
        )
    }

    // Fully rounded pill shape.
    val pillShape = RoundedCornerShape(pillHeightDp / 2)

    // Empty the touchable region as soon as nothing is rendered, so the fading/lingering
    // overlay window stops intercepting touches immediately.
    LaunchedEffect(state == null) {
        if (state == null) viewModel.updatePillTouchBounds(null)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.updatePillTouchBounds(null) }
    }

    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(motionScheme.defaultEffectsSpec()) +
            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec()),
        exit = fadeOut(motionScheme.fastEffectsSpec()) +
            scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec()),
        modifier = modifier,
    ) {
        state?.let { chipState ->
            val displayEvent = chipState.notificationAlert ?: chipState.event
            val isAlert = chipState.notificationAlert != null

            AnimatedContent(
                targetState = ChipDisplayCutout(displayEvent, isAlert),
                transitionSpec = {
                    (fadeIn(motionScheme.defaultEffectsSpec()) +
                        scaleIn(initialScale = 0.92f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                        (fadeOut(motionScheme.fastEffectsSpec()) +
                            scaleOut(targetScale = 0.92f, animationSpec = motionScheme.fastSpatialSpec())) using
                        SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                },
                contentKey = { if (it.isAlert) "alert" else it.event::class.simpleName },
                label = "cutout_chip_event",
            ) { display ->
                val rawAccent = chipTintAccentFor(display.event)
                val motionScheme = MaterialTheme.motionScheme
                val accent by animateColorAsState(rawAccent, motionScheme.fastEffectsSpec(), label = "accent")
                val rawChrome = islandGlassChrome(rawAccent, neutralBody = false)
                val bodyColor by animateColorAsState(
                    rawChrome.body, motionScheme.fastEffectsSpec(), label = "glass_body",
                )
                val borderColor by animateColorAsState(
                    rawChrome.border, motionScheme.fastEffectsSpec(), label = "glass_border",
                )
                val contentColor by animateColorAsState(
                    rawChrome.content, motionScheme.fastEffectsSpec(), label = "content",
                )
                val rawProgress = chipProgressFor(display.event)
                val progressTarget = rawProgress ?: 0f
                val progressAnim = remember { Animatable(progressTarget) }
                LaunchedEffect(progressTarget) {
                    if (abs(progressTarget - progressAnim.value) > 0.05f)
                        progressAnim.animateTo(progressTarget, tween(300, easing = FastOutSlowInEasing))
                    else progressAnim.snapTo(progressTarget)
                }
                val progress = if (rawProgress != null) progressAnim.value else null

                val chargingEvent = display.event as? IslandEvent.Charging
                val shouldPulse = chargingEvent != null && chargingEvent.isCharging && chargingEvent.level < 100
                val pulseAnim = if (shouldPulse) {
                    val infiniteTransition = rememberInfiniteTransition(label = "charging_pulse")
                    infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.55f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse"
                    ).value
                } else 1f
                // Pulse the event tint, not the body alpha: the chip sits over status bar content,
                // so fading it out would show icons through. Breathes between tinted and neutral.
                val pulsedBody = lerp(MediaChrome.GlassBody, bodyColor, pulseAnim)
                val pulsedAccent = accent.copy(alpha = accent.alpha * pulseAnim)

                when (placementHint) {
                    CutoutPlacementHint.LEFT -> CutoutPillLeft(
                        accent = pulsedAccent, bodyColor = pulsedBody, borderColor = borderColor,
                        contentColor = contentColor, progress = progress,
                        pillShape = pillShape, pillHeightDp = pillHeightDp,
                        pillTopDp = pillTopDp,
                        cutoutLeftDp = effectiveCutoutLeftDp, cutoutRightDp = effectiveCutoutRightDp,
                        cutoutWidthDp = cutoutWidthDp, padSideDp = padSideDp,
                        chipState = chipState, display = display, carrierName = carrierName,
                        viewModel = viewModel, touchSlop = touchSlop, screenWidthPx = screenWidthPx,
                        collapseToRing = collapseToRing, cutoutRectPx = effectiveCutoutRectPx,
                        ringGap = ringGap, ringScaleX = ringScaleX, ringScaleY = ringScaleY,
                        ringOffsetXDp = 0f, ringOffsetYDp = 0f, ringOpacity = ringOpacity,
                        ringStrokeDp = ringStrokeDp,
                    )
                    CutoutPlacementHint.RIGHT -> CutoutPillRight(
                        accent = pulsedAccent, bodyColor = pulsedBody, borderColor = borderColor,
                        contentColor = contentColor, progress = progress,
                        pillShape = pillShape, pillHeightDp = pillHeightDp,
                        pillTopDp = pillTopDp,
                        cutoutLeftDp = effectiveCutoutLeftDp, cutoutRightDp = effectiveCutoutRightDp,
                        cutoutWidthDp = cutoutWidthDp, padSideDp = padSideDp,
                        chipState = chipState, display = display, carrierName = carrierName,
                        viewModel = viewModel, touchSlop = touchSlop, screenWidthPx = screenWidthPx,
                        collapseToRing = collapseToRing, cutoutRectPx = effectiveCutoutRectPx,
                        ringGap = ringGap, ringScaleX = ringScaleX, ringScaleY = ringScaleY,
                        ringOffsetXDp = 0f, ringOffsetYDp = 0f, ringOpacity = ringOpacity,
                        ringStrokeDp = ringStrokeDp,
                    )
                    CutoutPlacementHint.CENTER -> CutoutPillCenter(
                        accent = pulsedAccent, bodyColor = pulsedBody, borderColor = borderColor,
                        contentColor = contentColor, progress = progress,
                        pillShape = pillShape, pillHeightDp = pillHeightDp, pillTopDp = pillTopDp,
                        cutoutLeftDp = effectiveCutoutLeftDp, cutoutRightDp = effectiveCutoutRightDp,
                        cutoutWidthDp = cutoutWidthDp, padSideDp = padSideDp,
                        chipState = chipState, display = display, carrierName = carrierName,
                        viewModel = viewModel, touchSlop = touchSlop, screenWidthPx = screenWidthPx,
                        collapseToRing = collapseToRing, cutoutRectPx = effectiveCutoutRectPx,
                        ringGap = ringGap, ringScaleX = ringScaleX, ringScaleY = ringScaleY,
                        ringOffsetXDp = 0f, ringOffsetYDp = 0f, ringOpacity = ringOpacity,
                        ringStrokeDp = ringStrokeDp,
                    )
                }
            }
        }
    }
}

// ── LEFT cutout ───────────────────────────────────────────────────────────────
// Single pill background wrapping around the cutout (cutout is a "hole").
// Content (icon + text) placed to the right of the cutout.

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CutoutPillLeft(
    accent: Color,
    bodyColor: Color,
    borderColor: Color,
    contentColor: Color,
    progress: Float?,
    pillShape: RoundedCornerShape,
    pillHeightDp: Dp,
    pillTopDp: Dp,
    cutoutLeftDp: Dp,
    cutoutRightDp: Dp,
    cutoutWidthDp: Dp,
    padSideDp: Dp,
    chipState: AxDynamicBarChipState,
    display: ChipDisplayCutout,
    carrierName: String?,
    viewModel: AxDynamicBarChipViewModel,
    touchSlop: Float,
    screenWidthPx: Float,
    collapseToRing: Boolean = false,
    cutoutRectPx: Rect? = null,
    ringGap: Float = 1.155f,
    ringScaleX: Float = 1f,
    ringScaleY: Float = 1f,
    ringOffsetXDp: Float = 0f,
    ringOffsetYDp: Float = 0f,
    ringOpacity: Int = 90,
    ringStrokeDp: Float = 3f,
) {
    val cutoutZone = cutoutWidthDp + padSideDp * 2
    val basePillWidth = cutoutZone + StatusBarPillWidth

    val motionScheme = MaterialTheme.motionScheme
    val density = LocalDensity.current

    // Ring collapse: same idle timer as CENTER, only when collapseToRing is enabled.
    var isCollapsedToRing by remember { mutableStateOf(false) }
    val event = display.event
    val contentKey = remember(event) { textKeyFor(event) }
    val collapseKey = remember(event) { collapseIdentityKey(event) }
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()

    LaunchedEffect(contentKey, collapseKey, collapseToRing) {
        isCollapsedToRing = false
        if (collapseToRing && event.behavior.autoDismissMs == null) {
            delay(CutoutCenterCollapsePolicy.CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS)
            isCollapsedToRing = true
        }
    }
    LaunchedEffect(isExpanded) {
        if (isExpanded && collapseToRing) isCollapsedToRing = true
    }

    Box(
        modifier = Modifier
            .requiredWidth(LocalConfiguration.current.screenWidthDp.dp)
            .padding(top = pillTopDp),
        contentAlignment = Alignment.TopStart,
    ) {
        // Ring mode: show ring centered on cutout instead of pill
        val showRing = collapseToRing && isCollapsedToRing && cutoutRectPx != null
        AnimatedContent(
            targetState = showRing,
            transitionSpec = {
                (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec()))
            },
            contentAlignment = Alignment.TopStart,
            label = "cutout_ring_transition_left"
        ) { isRing ->
            if (isRing && cutoutRectPx != null) {
                Box(
                    modifier = Modifier
                        .islandGestures(viewModel = viewModel, touchSlop = touchSlop)
                        .onGloballyPositioned { reportTouchBounds(viewModel, density, it) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Dynamic bar"
                            onClick(label = "Expand") { viewModel.togglePanel(); true }
                        },
                ) {
                    AnimatedCutoutRing(
                        event = event,
                        cutoutRectPx = cutoutRectPx,
                        accent = accent,
                        progress = progress,
                        ringGap = ringGap, ringScaleX = 1f, ringScaleY = 1f,
                        ringOffsetXDp = 0f, ringOffsetYDp = 0f,
                        ringOpacity = ringOpacity, ringStrokeDp = ringStrokeDp,
                    )
                }
        } else {
            Box(modifier = Modifier.wrapContentSize()) {
                Row(
                    modifier = Modifier
                        .height(pillHeightDp)
                        .width(basePillWidth)
                        .islandGestures(viewModel = viewModel, touchSlop = touchSlop)
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInWindow()
                            val centerX = (bounds.left + bounds.right) / 2f
                            if (screenWidthPx > 0f) viewModel.updateChipCenterX(centerX / screenWidthPx)
                            reportTouchBounds(viewModel, density, coords)
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (chipState.eventCount > 1) "Dynamic bar, ${chipState.eventCount} notifications" else "Dynamic bar"
                            onClick(label = "Expand") { viewModel.togglePanel(); true }
                        }
                        .clip(pillShape)
                        .background(bodyColor)
                        .border(1.dp, borderColor, pillShape)
                        .progressOverlay(progress = progress, accent = accent, contentColor = contentColor)
                        .padding(start = cutoutZone, end = ContentPad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CutoutChipContent(
                        chipState = chipState, display = display,
                        contentColor = contentColor, carrierName = carrierName,
                        maxTextWidth = StatusBarPillWidth,
                    )
                }
                // Badge on the RIGHT side for LEFT cutout (outside the pill)
                if (chipState.eventCount > 1) {
                    IslandStackCountBadge(
                        count = chipState.eventCount,
                        backgroundColor = accent,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 12.dp, y = 4.dp)
                    )
                }
            }
        }
        }
    }
}

// ── RIGHT cutout ──────────────────────────────────────────────────────────────
// Single pill background wrapping around the cutout (cutout is a "hole").
// Content (icon + text) placed to the left of the cutout.

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CutoutPillRight(
    accent: Color,
    bodyColor: Color,
    borderColor: Color,
    contentColor: Color,
    progress: Float?,
    pillShape: RoundedCornerShape,
    pillHeightDp: Dp,
    pillTopDp: Dp,
    cutoutLeftDp: Dp,
    cutoutRightDp: Dp,
    cutoutWidthDp: Dp,
    padSideDp: Dp,
    chipState: AxDynamicBarChipState,
    display: ChipDisplayCutout,
    carrierName: String?,
    viewModel: AxDynamicBarChipViewModel,
    touchSlop: Float,
    screenWidthPx: Float,
    collapseToRing: Boolean = false,
    cutoutRectPx: Rect? = null,
    ringGap: Float = 1.155f,
    ringScaleX: Float = 1f,
    ringScaleY: Float = 1f,
    ringOffsetXDp: Float = 0f,
    ringOffsetYDp: Float = 0f,
    ringOpacity: Int = 90,
    ringStrokeDp: Float = 3f,
) {
    val motionScheme = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val cutoutZone = cutoutWidthDp + padSideDp * 2
    val basePillWidth = cutoutZone + StatusBarPillWidth

    // Ring collapse
    var isCollapsedToRing by remember { mutableStateOf(false) }
    val event = display.event
    val contentKey = remember(event) { textKeyFor(event) }
    val collapseKey = remember(event) { collapseIdentityKey(event) }
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()

    LaunchedEffect(contentKey, collapseKey, collapseToRing) {
        isCollapsedToRing = false
        if (collapseToRing && event.behavior.autoDismissMs == null) {
            delay(CutoutCenterCollapsePolicy.CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS)
            isCollapsedToRing = true
        }
    }
    LaunchedEffect(isExpanded) {
        if (isExpanded && collapseToRing) isCollapsedToRing = true
    }

    Box(
        modifier = Modifier
            .requiredWidth(LocalConfiguration.current.screenWidthDp.dp)
            .padding(top = pillTopDp),
        contentAlignment = Alignment.TopEnd,
    ) {
        // Ring mode
        val showRing = collapseToRing && isCollapsedToRing && cutoutRectPx != null
        AnimatedContent(
            targetState = showRing,
            transitionSpec = {
                (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec()))
            },
            contentAlignment = Alignment.TopEnd,
            label = "cutout_ring_transition_right"
        ) { isRing ->
            if (isRing && cutoutRectPx != null) {
                Box(
                    modifier = Modifier
                        .islandGestures(viewModel = viewModel, touchSlop = touchSlop)
                        .onGloballyPositioned { reportTouchBounds(viewModel, density, it) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Dynamic bar"
                            onClick(label = "Expand") { viewModel.togglePanel(); true }
                        },
                ) {
                    AnimatedCutoutRing(
                        event = event,
                        cutoutRectPx = cutoutRectPx,
                        accent = accent,
                        progress = progress,
                        ringGap = ringGap, ringScaleX = 1f, ringScaleY = 1f,
                        ringOffsetXDp = 0f, ringOffsetYDp = 0f,
                        ringOpacity = ringOpacity, ringStrokeDp = ringStrokeDp,
                    )
                }
        } else {
            Box(modifier = Modifier.wrapContentSize()) {
                Row(
                    modifier = Modifier
                        .height(pillHeightDp)
                        .width(basePillWidth)
                        .islandGestures(viewModel = viewModel, touchSlop = touchSlop)
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInWindow()
                            val centerX = (bounds.left + bounds.right) / 2f
                            if (screenWidthPx > 0f) viewModel.updateChipCenterX(centerX / screenWidthPx)
                            reportTouchBounds(viewModel, density, coords)
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (chipState.eventCount > 1) "Dynamic bar, ${chipState.eventCount} notifications" else "Dynamic bar"
                            onClick(label = "Expand") { viewModel.togglePanel(); true }
                        }
                        .clip(pillShape)
                        .background(bodyColor)
                        .border(1.dp, borderColor, pillShape)
                        .progressOverlay(progress = progress, accent = accent, contentColor = contentColor)
                        .padding(start = ContentPad, end = cutoutZone),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CutoutChipContent(
                        chipState = chipState, display = display,
                        contentColor = contentColor, carrierName = carrierName,
                        maxTextWidth = StatusBarPillWidth,
                    )
                }
                // Badge on the LEFT side for RIGHT cutout (outside the pill)
                if (chipState.eventCount > 1) {
                    IslandStackCountBadge(
                        count = chipState.eventCount,
                        backgroundColor = accent,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-12).dp, y = 4.dp)
                    )
                }
            }
        }
        }
    }
}

// ── CENTER cutout ─────────────────────────────────────────────────────────────
// Single continuous pill background wrapping around the cutout.
// Icon + count on the left side, text on the right side, cutout in center.

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CutoutPillCenter(
    accent: Color,
    bodyColor: Color,
    borderColor: Color,
    contentColor: Color,
    progress: Float?,
    pillShape: RoundedCornerShape,
    pillHeightDp: Dp,
    pillTopDp: Dp,
    cutoutLeftDp: Dp,
    cutoutRightDp: Dp,
    cutoutWidthDp: Dp,
    padSideDp: Dp,
    chipState: AxDynamicBarChipState,
    display: ChipDisplayCutout,
    carrierName: String?,
    viewModel: AxDynamicBarChipViewModel,
    touchSlop: Float,
    screenWidthPx: Float,
    collapseToRing: Boolean = false,
    cutoutRectPx: Rect? = null,
    ringGap: Float = 1.155f,
    ringScaleX: Float = 1f,
    ringScaleY: Float = 1f,
    ringOffsetXDp: Float = 0f,
    ringOffsetYDp: Float = 0f,
    ringOpacity: Int = 90,
    ringStrokeDp: Float = 3f,
) {
    val motionScheme = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val gapWidth = cutoutWidthDp + padSideDp * 2

    // Track measured side widths for the correction offset.
    val leftWidthPx = remember { mutableIntStateOf(0) }
    val rightWidthPx = remember { mutableIntStateOf(0) }

    var isRightCollapsed by remember { mutableStateOf(false) }
    val event = display.event
    val isExpanded by viewModel.isExpanded.collectAsStateWithLifecycle()

    val contentKey = remember(event) { textKeyFor(event) }
    val collapseKey = remember(event) { collapseIdentityKey(event) }

    LaunchedEffect(contentKey, collapseKey) {
        isRightCollapsed = false
        if (event.behavior.autoDismissMs == null) {
            delay(CutoutCenterCollapsePolicy.CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS)
            isRightCollapsed = true
        }
    }

    // Force-collapse the right lane when the stack opens; preserve collapse state on close.
    LaunchedEffect(isExpanded) {
        if (isExpanded) isRightCollapsed = true
    }

    // Ring mode: when collapseToRing is enabled and the right lane has collapsed,
    // replace the entire pill with an AnimatedCutoutRing and hide the badge.
    val showRing = collapseToRing && isRightCollapsed && cutoutRectPx != null

    Box(
        modifier = Modifier
            .requiredWidth(LocalConfiguration.current.screenWidthDp.dp)
            .padding(top = pillTopDp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedContent(
            targetState = showRing,
            transitionSpec = {
                (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec()))
            },
            contentAlignment = Alignment.TopCenter,
            label = "cutout_ring_transition_center"
        ) { isRing ->
            if (isRing && cutoutRectPx != null) {
                Box(
                    modifier = Modifier
                        .islandGestures(viewModel = viewModel, touchSlop = touchSlop)
                        .onGloballyPositioned { reportTouchBounds(viewModel, density, it) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Dynamic bar"
                            onClick(label = "Expand") { viewModel.togglePanel(); true }
                        },
                ) {
                    AnimatedCutoutRing(
                        event = event,
                        cutoutRectPx = cutoutRectPx,
                        accent = accent,
                        progress = progress,
                        ringGap = ringGap, ringScaleX = 1f, ringScaleY = 1f,
                        ringOffsetXDp = 0f, ringOffsetYDp = 0f,
                        ringOpacity = ringOpacity, ringStrokeDp = ringStrokeDp,
                    )
                }
        } else {
            val collapseTargetWidth = if (isRightCollapsed) 0.dp else CenterRightLaneWidth
            val animatedRightLaneWidth by animateDpAsState(
                targetValue = collapseTargetWidth,
                animationSpec =
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "cutout_center_right_lane_width",
            )
            val lanePad = if (animatedRightLaneWidth > 6.dp) 4.dp else 0.dp

            Box(modifier = Modifier.wrapContentSize()) {
                Row(
                    modifier = Modifier
                        .height(pillHeightDp)
                        // Correction: shift pill so cutout gap stays aligned with physical cutout.
                        // When right side is wider, pill must shift left by half the difference, and vice-versa.
                        .offset { IntOffset((rightWidthPx.intValue - leftWidthPx.intValue) / 2, 0) }
                        .islandGestures(viewModel = viewModel, touchSlop = touchSlop)
                        .onGloballyPositioned { coords ->
                            val bounds = coords.boundsInWindow()
                            val centerX = (bounds.left + bounds.right) / 2f
                            if (screenWidthPx > 0f) viewModel.updateChipCenterX(centerX / screenWidthPx)
                            reportTouchBounds(viewModel, density, coords)
                        }
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (chipState.eventCount > 1) "Dynamic bar, ${chipState.eventCount} notifications" else "Dynamic bar"
                            onClick(label = "Expand") { viewModel.togglePanel(); true }
                        }
                        .clip(pillShape)
                        .background(bodyColor)
                        .border(1.dp, borderColor, pillShape)
                        .progressOverlay(progress = progress, accent = accent, contentColor = contentColor),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // LEFT side: icon (+ carrier or battery %)
                    Row(
                        modifier = Modifier
                            .width(CenterLeftLaneWidth)
                            .onSizeChanged { leftWidthPx.intValue = it.width }
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val event = display.event
                        if (event !is IslandEvent.Charging && carrierName != null) {
                            Text(
                                text = carrierName,
                                style = PillPrimary,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 48.dp),
                            )
                            Spacer(Modifier.width(SpaceXs))
                        }
                        AnimatedContent(
                            targetState = event,
                            transitionSpec = { (fadeIn() togetherWith fadeOut()).using(sizeTransform = null) },
                            contentKey = { iconKeyFor(it) },
                            label = "cutout_center_icon",
                        ) { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PillEventIcon(e, tint = contentColor)
                            }
                        }
                    }

                    // CENTER gap — cutout zone (no content)
                    Spacer(Modifier.width(gapWidth))

                    // RIGHT side: text only
                    Row(
                        modifier = Modifier
                            .width(animatedRightLaneWidth)
                            .onSizeChanged { rightWidthPx.intValue = it.width }
                            .padding(horizontal = lanePad),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val showCenterTextLane = animatedRightLaneWidth >= 36.dp // hide text before pinch hits 0dp
                        if (showCenterTextLane) {
                            AnimatedContent(
                                targetState = display.event,
                                transitionSpec = { (fadeIn() togetherWith fadeOut()).using(sizeTransform = null) },
                                contentKey = { textKeyFor(it) },
                                label = "cutout_center_text",
                            ) { event ->
                                if (event is IslandEvent.Charging) {
                                    val text = chargingRightLaneText(event)
                                    Text(
                                        text,
                                        style = PillPrimary,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier.widthIn(max = CenterRightLaneWidth - 8.dp).basicMarquee(iterations = 1)
                                    )
                                } else {
                                    PillEventText(
                                        event,
                                        modifier = Modifier.widthIn(max = CenterRightLaneWidth - 8.dp),
                                        overrideColor = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
                // Badge on the RIGHT side for CENTER cutout (outside the pill)
                // Include the same correction offset as the pill Row so badge follows pill edge.
                if (chipState.eventCount > 1) {
                    IslandStackCountBadge(
                        count = chipState.eventCount,
                        backgroundColor = accent,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset {
                                val correctionPx = (rightWidthPx.intValue - leftWidthPx.intValue) / 2
                                IntOffset(12.dp.roundToPx() + correctionPx, 4.dp.roundToPx())
                            }
                    )
                }
            }
        }
        }
    }
}

// ── Shared content row (LEFT / RIGHT) ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun androidx.compose.foundation.layout.RowScope.CutoutChipContent(
    chipState: AxDynamicBarChipState,
    display: ChipDisplayCutout,
    contentColor: Color,
    carrierName: String?,
    maxTextWidth: Dp,
) {
    val motionScheme = MaterialTheme.motionScheme

    if (carrierName != null) {
        Text(
            text = carrierName,
            style = PillPrimary,
            color = contentColor.copy(alpha = AlphaSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 56.dp),
        )
        Text(" · ", style = PillPrimary, color = contentColor.copy(alpha = AlphaTertiary))
    }

    AnimatedContent(
        targetState = display.event,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
        },
        contentKey = { iconKeyFor(it) },
        label = "cutout_icon",
    ) { event ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillEventIcon(event, tint = contentColor)
        }
    }

    Spacer(Modifier.width(SpaceXs))

    AnimatedContent(
        targetState = display.event,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
        },
        contentKey = { textKeyFor(it) },
        label = "cutout_text",
        modifier = Modifier.weight(1f, fill = false),
    ) { event ->
        if (event is IslandEvent.Charging) {
            val text = chargingRightLaneText(event)
            Text(
                text,
                style = PillPrimary,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.widthIn(max = maxTextWidth).basicMarquee(iterations = 1),
            )
        } else {
            PillEventText(event, modifier = Modifier.widthIn(max = maxTextWidth), overrideColor = contentColor)
        }
    }
}

// ── Progress overlay ──────────────────────────────────────────────────────────

private fun Modifier.progressOverlay(progress: Float?, accent: Color, contentColor: Color): Modifier =
    if (progress != null) {
        val trackColor = lerp(accent, contentColor, 0.2f)
        val fillColor = lerp(accent, contentColor, 0.6f)
        drawWithContent {
            drawContent()
            val barH = 2.dp.toPx()
            val y = size.height - barH
            drawRect(trackColor, Offset(0f, y), Size(size.width, barH))
            drawRect(fillColor, Offset(0f, y), Size(size.width * progress, barH))
        }
    } else this


private data class ChipDisplayCutout(val event: IslandEvent, val isAlert: Boolean)

