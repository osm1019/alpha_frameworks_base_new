@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.axdynamicbar.ui.compose

import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlin.math.abs
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.alpha.theme.AlphaMetrics
import com.android.systemui.alpha.theme.AlphaOpacity
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.axdynamicbar.ui.KeyguardLaneContent
import com.android.systemui.axdynamicbar.ui.KeyguardLaneInputs
import com.android.systemui.axdynamicbar.ui.KeyguardLaneOccupant
import com.android.systemui.axdynamicbar.ui.laneCapacity
import com.android.systemui.axdynamicbar.ui.laneContentKey
import com.android.systemui.axdynamicbar.ui.resolveKeyguardLane
import com.android.systemui.media.ax.ui.model.AxLockscreenMediaStyle
import com.android.systemui.res.R
import kotlinx.coroutines.delay
import android.content.Context
import android.graphics.drawable.Drawable
import java.util.Calendar

/** Fallback when a default is needed outside composition; the lane reads the affordance dimen. */
private val ChipShape = ShapeChip
/** Album art inside the 48dp media chip. */
private val MediaChipIconSize = 32.dp
private val ActionSize = SpacePanel
private val ActionIconSize = SizeBadge
/** Transport hit targets scaled for media's affordance-matched height. */
private val MediaActionSize = 28.dp
private val MediaActionIconSize = 16.dp

@Composable
private fun rememberLaneHeight(): Dp =
    dimensionResource(R.dimen.keyguard_affordance_fixed_height)

/**
 * Cap on the track/artist lane while expanded so art + text + transport fit the chip's max
 * width (260dp). Text ellipsizes inside this; collapse hides the lane entirely.
 * Timing reuses [CutoutCenterCollapsePolicy.CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS] (5s).
 */
private val MediaTextExpandedMaxWidth = 90.dp

/** Matches the media chip's cap, so a solo lane is the same width whatever is holding it. */
private val BatteryPillMaxWidth = 260.dp

@Composable
private fun rememberChargingParts(batteryString: String): List<String> {
    return remember(batteryString) {
        batteryString.split("\n", limit = 3).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

@Composable
fun AxDynamicBarKeyguardChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val laneInputs by viewModel.keyguardLaneInputs.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isKeyguardEnabled by viewModel.isKeyguardEnabled.collectAsStateWithLifecycle()
    val isKeyguardExpanded by viewModel.isKeyguardExpanded.collectAsStateWithLifecycle()
    val isEventExpanded by viewModel.isEventExpanded.collectAsStateWithLifecycle()
    val heldEvent by viewModel.keyguardExpansion.heldEvent.collectAsStateWithLifecycle()
    val lockscreenMediaStyle by viewModel.lockscreenMediaStyle.collectAsStateWithLifecycle()
    val batteryString by viewModel.batteryString.collectAsStateWithLifecycle()
    val isBatteryExpanded by
        viewModel.keyguardExpansion.isBatteryExpanded.collectAsStateWithLifecycle()
    val chargingEvent by viewModel.chargingEvent.collectAsStateWithLifecycle()
    val batteryInfo by viewModel.keyguardBatteryInfo.collectAsStateWithLifecycle()

    val motionScheme = MaterialTheme.motionScheme
    val blurred = rememberChipBlurEnabled()

    Box(modifier = modifier) {

        val expandedVisibleState = remember { MutableTransitionState(false) }
        expandedVisibleState.targetState = isEventExpanded && heldEvent != null
        LaunchedEffect(expandedVisibleState.isIdle, expandedVisibleState.currentState) {
            if (expandedVisibleState.isIdle && !expandedVisibleState.currentState) {
                viewModel.keyguardExpansion.notifyCollapseSettled()
            }
        }
        AnimatedVisibility(
            visibleState = expandedVisibleState,
            enter = fadeIn(motionScheme.defaultEffectsSpec()),
            exit = fadeOut(tween(durationMillis = 250)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
        ) {
            heldEvent?.let { event ->
                KeyguardExpandedContent(
                    event = event,
                    allEvents = state?.allEvents ?: emptyList(),
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.keyguardExpansion.collapse() },
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                    lockscreenMediaStyle = lockscreenMediaStyle,
                )
            }
        }

        val batteryVisibleState = remember { MutableTransitionState(false) }
        batteryVisibleState.targetState = isBatteryExpanded && chargingEvent != null
        LaunchedEffect(batteryVisibleState.isIdle, batteryVisibleState.currentState) {
            if (batteryVisibleState.isIdle && !batteryVisibleState.currentState) {
                viewModel.keyguardExpansion.notifyCollapseSettled()
            }
        }
        AnimatedVisibility(
            visibleState = batteryVisibleState,
            enter = fadeIn(motionScheme.defaultEffectsSpec()),
            exit = fadeOut(tween(durationMillis = 250)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
        ) {
            chargingEvent?.let {
                KeyguardBatteryPanel(
                    event = it,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.keyguardExpansion.collapse() },
                    onDismiss = { viewModel.dismissBattery() },
                )
            }
        }

        val laneVisible =
            isOnKeyguard && isEnabled && isKeyguardEnabled && !isKeyguardExpanded &&
                laneInputs.hasContent
        AnimatedVisibility(
            visible = laneVisible,
            enter = fadeIn(tween(durationMillis = 200, delayMillis = 300)) +
                scaleIn(
                    initialScale = 0.9f,
                    animationSpec = tween(durationMillis = 200, delayMillis = 300),
                ),
            exit = fadeOut(motionScheme.fastEffectsSpec()) +
                scaleOut(targetScale = 0.9f, animationSpec = motionScheme.fastSpatialSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            KeyguardChipLane(
                inputs = laneInputs,
                viewModel = viewModel,
                batteryString = batteryString,
                mediaStyle = lockscreenMediaStyle,
                blurred = blurred,
            )
        }
    }
}

@Composable
private fun KeyguardChipLane(
    inputs: KeyguardLaneInputs,
    viewModel: AxDynamicBarChipViewModel,
    batteryString: String,
    mediaStyle: AxLockscreenMediaStyle,
    blurred: Boolean,
) {
    val height = rememberLaneHeight()
    val motionScheme = MaterialTheme.motionScheme
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val laneWidth = maxWidth
        val capacity = laneCapacity(laneWidth, height)
        val content = resolveKeyguardLane(inputs, capacity)
        AnimatedContent(
            targetState = content,
            transitionSpec = {
                (fadeIn(motionScheme.defaultEffectsSpec()) +
                    scaleIn(initialScale = 0.95f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                    (fadeOut(motionScheme.fastEffectsSpec()) +
                        scaleOut(targetScale = 0.95f, animationSpec = motionScheme.fastSpatialSpec())) using
                    SizeTransform(
                        clip = false,
                        sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() },
                    )
            },
            contentKey = { laneContentKey(it) },
            label = "keyguard_lane",
        ) { lane ->
            when (lane) {
                KeyguardLaneContent.Empty -> Spacer(Modifier.size(0.dp))
                is KeyguardLaneContent.Indication ->
                    KeyguardIndicationPill(lane.indication, height, blurred, laneWidth)
                is KeyguardLaneContent.Occupants ->
                    if (lane.items.size == 1) {
                        KeyguardSoloOccupant(
                            occupant = lane.items[0],
                            height = height,
                            blurred = blurred,
                            viewModel = viewModel,
                            batteryString = batteryString,
                            mediaStyle = mediaStyle,
                        )
                    } else {
                        KeyguardChipRow(
                            items = lane.items,
                            height = height,
                            blurred = blurred,
                            viewModel = viewModel,
                        )
                    }
            }
        }
    }
}

@Composable
private fun KeyguardSoloOccupant(
    occupant: KeyguardLaneOccupant,
    height: Dp,
    blurred: Boolean,
    viewModel: AxDynamicBarChipViewModel,
    batteryString: String,
    mediaStyle: AxLockscreenMediaStyle,
) {
    when (occupant) {
        is KeyguardLaneOccupant.Battery ->
            KeyguardBatteryChip(
                occupant.info,
                batteryString,
                height,
                blurred,
                onClick = { viewModel.keyguardExpansion.expandBattery() },
            )
        is KeyguardLaneOccupant.Event -> {
            val event = occupant.event
            val rawAccent = chipAccentColorFor(event)
            val accent by animateColorAsState(
                rawAccent,
                MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "kg_accent",
            )
            val chrome = dbLockscreenPillChrome(
                rawAccent,
                untinted = event is IslandEvent.Media,
                blurred = blurred,
            )
            val contentColor by animateColorAsState(
                chrome.content,
                MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "kg_content",
            )
            val rawProgress = chipProgressFor(event)
            val progressTarget = rawProgress ?: 0f
            val progressAnim = remember { Animatable(progressTarget) }
            LaunchedEffect(progressTarget) {
                if (abs(progressTarget - progressAnim.value) > 0.05f) {
                    progressAnim.animateTo(progressTarget, tween(300, easing = FastOutSlowInEasing))
                } else {
                    progressAnim.snapTo(progressTarget)
                }
            }
            val progress = if (rawProgress != null) progressAnim.value else null
            KeyguardChipBody(
                event = event,
                eventIndex = occupant.eventIndex,
                accent = accent,
                contentColor = contentColor,
                progress = progress,
                height = height,
                blurred = blurred,
                viewModel = viewModel,
                batteryString = batteryString,
                mediaStyle = mediaStyle,
            )
        }
    }
}

@Composable
private fun KeyguardChipRow(
    items: List<KeyguardLaneOccupant>,
    height: Dp,
    blurred: Boolean,
    viewModel: AxDynamicBarChipViewModel,
) {
    val motionScheme = MaterialTheme.motionScheme
    Row(
        modifier = Modifier.animateContentSize(motionScheme.defaultSpatialSpec()),
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            when (item) {
                is KeyguardLaneOccupant.Battery ->
                    KeyguardBatteryCircle(
                        item.info,
                        height,
                        blurred,
                        onClick = { viewModel.keyguardExpansion.expandBattery() },
                    )
                is KeyguardLaneOccupant.Event ->
                    KeyguardEventChip(
                        event = item.event,
                        size = height,
                        blurred = blurred,
                        onClick = { onLaneEventClick(viewModel, item.event, item.eventIndex) },
                    )
            }
        }
    }
}

/**
 * The lane as a line of text.
 *
 * This is the only place these messages appear — the chip suppresses AOSP's indication area
 * — and some of them are not ours to abbreviate: the enterprise disclosure and the owner's
 * lock screen message are whole sentences. So it takes the full lane rather than a chip's
 * width, and marquees what still will not fit.
 */
@Composable
private fun KeyguardIndicationPill(
    indication: IslandEvent.KeyguardIndication,
    height: Dp,
    blurred: Boolean,
    laneWidth: Dp,
) {
    val accent = chipAccentColorFor(indication)
    val chrome = dbLockscreenPillChrome(accent, untinted = false, blurred = blurred)
    Box(contentAlignment = Alignment.Center) {
        if (blurred) {
            ChipGlassBackdrop(corner = height / 2, modifier = Modifier.matchParentSize())
        }
        Row(
            modifier = Modifier
                .height(height)
                .widthIn(min = height, max = laneWidth)
                .clip(ChipShape)
                .background(chrome.body)
                .border(AlphaColors.DbLockscreenPill.rimWidth, chrome.border, ChipShape)
                .padding(horizontal = SpaceMd)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                indication.text,
                style = PillPrimary,
                color = chrome.content,
                maxLines = 1,
                // Clip, not ellipsis: marquee measures unbounded and an ellipsis would win.
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(),
            )
        }
    }
}

private fun onLaneEventClick(
    viewModel: AxDynamicBarChipViewModel,
    event: IslandEvent,
    eventIndex: Int,
) {
    when (event) {
        is IslandEvent.Notification -> viewModel.launchNotificationFromKeyguard(event)
        is IslandEvent.AppSwitch,
        is IslandEvent.KeyguardIndication -> { }
        else -> viewModel.keyguardExpansion.expandPinned(eventIndex)
    }
}

@Composable
private fun KeyguardChipBody(
    event: IslandEvent,
    eventIndex: Int,
    accent: Color,
    contentColor: Color,
    progress: Float?,
    height: Dp,
    blurred: Boolean,
    viewModel: AxDynamicBarChipViewModel,
    batteryString: String = "",
    mediaStyle: AxLockscreenMediaStyle = AxLockscreenMediaStyle.DEFAULT,
) {
    val context = LocalContext.current
    val motionScheme = MaterialTheme.motionScheme

    val isMedia = event is IslandEvent.Media
    val dynamicHeight = height

    // Glass shell for every event: media = neutral glass; others keep event hue as a tint
    // (charging green, timer orange, …) instead of solid full-fill. Style only recolors
    // media buttons + progress.
    val chrome = dbLockscreenPillChrome(accent, untinted = isMedia, blurred = blurred)
    val bodyColor = chrome.body
    val neutralChrome = mediaStyle != AxLockscreenMediaStyle.WAVEFORM
    val progressTrack =
        when {
            !isMedia -> lerp(accent, contentColor, 0.2f)
            neutralChrome -> AlphaColors.DbLockscreenPill.progressTrack
            else -> AlphaColors.DbLockscreenPill.waveformProgressTrack
        }
    val progressFill =
        when {
            !isMedia -> lerp(accent, contentColor, 0.6f)
            neutralChrome -> AlphaColors.DbLockscreenPill.progressFill
            else -> accent
        }
    val progressBarH = if (isMedia) AlphaColors.DbLockscreenPill.progressHeight else SizeStrokeWidth

    // Media needs room for art + text + 3 transport buttons.
    val chipMaxWidth = if (isMedia) 280.dp else 260.dp

    Box(contentAlignment = Alignment.Center) {
        if (blurred) {
            ChipGlassBackdrop(corner = dynamicHeight / 2, modifier = Modifier.matchParentSize())
        }
        Row(
            modifier = Modifier
                .height(dynamicHeight)
                .widthIn(min = dynamicHeight, max = chipMaxWidth)
                .clip(ChipShape)
                .background(bodyColor)
                .border(AlphaColors.DbLockscreenPill.rimWidth, chrome.border, ChipShape)
                .animateContentSize(motionScheme.defaultSpatialSpec())
                .then(
                    if (progress != null) {
                        Modifier.drawWithContent {
                            drawContent()
                            val barH = progressBarH.toPx()
                            val y = size.height - barH
                            drawRect(progressTrack, Offset(0f, y), Size(size.width, barH))
                            drawRect(
                                progressFill,
                                Offset(0f, y),
                                Size(size.width * progress, barH),
                            )
                        }
                    } else Modifier
                )
                .clickable { onLaneEventClick(viewModel, event, eventIndex) }
                .padding(start = SpaceSm, end = SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (event is IslandEvent.Media) {
                KeyguardMediaChipContent(
                    event = event,
                    accent = accent,
                    viewModel = viewModel,
                    mediaStyle = mediaStyle,
                )
            } else if (event is IslandEvent.Sports && event.team2Name.isNotEmpty()) {
                SportsChipTeamBadge(event.team1Name, event.team1Icon, contentColor, height - SpaceLg)
                Spacer(Modifier.width(SpaceXs))
                Text(
                    if (event.score1.isNotEmpty()) "${event.score1} - ${event.score2}"
                        else stringResource(R.string.ax_dynamic_bar_sports_vs),
                    style = PillPrimary,
                    color = contentColor,
                    maxLines = 1,
                )
                Spacer(Modifier.width(SpaceXs))
                SportsChipTeamBadge(event.team2Name, event.team2Icon, contentColor, height - SpaceLg)
            } else {
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { iconKeyFor(it) },
                    label = "kg_chip_icon",
                ) { ev ->
                    PillEventIcon(ev, tint = contentColor)
                }
                Spacer(Modifier.width(SpaceXs))
                AnimatedContent(
                    targetState = event,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                            (fadeOut(motionScheme.fastEffectsSpec()) +
                                scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { textKeyFor(it) },
                    label = "kg_chip_text",
                    modifier = Modifier.weight(1f, fill = false),
                ) { ev ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KeyguardPrimaryText(ev, contentColor, Modifier.weight(1f, fill = false), batteryString)
                        val secondary = secondaryTextFor(ev)
                        if (secondary != null) {
                            Text(
                                " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaTertiary),
                            )
                            Text(
                                secondary,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 80.dp),
                            )
                        }
                    }
                }
            }

            if (event !is IslandEvent.Media) {
                val actionsCtx = LocalContext.current
                val actions = actionsFor(event, actionsCtx)
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.width(SpaceXs))
                    actions.forEach { action ->
                        Spacer(Modifier.width(SpaceXxs))
                        ActionButton(
                            icon = action.icon,
                            color = contentColor,
                            bgColor = lerp(accent, contentColor, AlphaSubtle),
                            onClick = { action.perform(viewModel, event, context) },
                            size = ActionSize,
                            iconSize = ActionIconSize,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lockscreen media capsule content: spinning art, marquee meta, floating transport.
 *
 * Geometry is shared across styles. Only button fills + icon tints follow [mediaStyle]:
 * - **Waveform** (and Glass until its pass): today's look — accent play, tonal skip fills.
 * - **Minimal**: transparent button backgrounds, bare white icons.
 *
 * **Collapsed (after 5s idle):** text lane removed; art + prev/play/next remain.
 * Re-expands on track/artist change only (not play/pause).
 */
@Composable
private fun RowScope.KeyguardMediaChipContent(
    event: IslandEvent.Media,
    accent: Color,
    viewModel: AxDynamicBarChipViewModel,
    mediaStyle: AxLockscreenMediaStyle = AxLockscreenMediaStyle.DEFAULT,
) {
    val motionScheme = MaterialTheme.motionScheme
    val minimal = mediaStyle == AxLockscreenMediaStyle.MINIMAL
    val glass = mediaStyle == AxLockscreenMediaStyle.GLASS
    val skipBg =
        if (minimal || glass) Color.Transparent else lerp(AlphaColors.DbLockscreenPill.body, accent, AlphaOpacity.buttonPlateBlendAmount)
    // Waveform's skip plate is a blend of the body and the accent, not the accent — colour the
    // glyph against the plate it actually sits on or it goes light-on-light in day mode.
    val skipIcon =
        if (minimal || glass) AlphaColors.DbLockscreenPill.skipGlyph else chipContentColorOn(skipBg)
    val playBg = if (minimal) Color.Transparent else accent
    // Filled accent behind the glyph -> near-white by rule, matching the expand card's play.
    val playIcon = if (minimal) AlphaColors.DbLockscreenPill.skipGlyph else AlphaColors.DbLockscreenPill.playGlyph
    // Same hairline the expand card's play button carries — one control, two surfaces.
    val playBorder =
        if (glass) {
            BorderStroke(
                AlphaMetrics.chipRimWidth,
                AlphaColors.DbLockscreenPill.artRim,
            )
        } else {
            null
        }

    // Idle collapse — same delay as cutout center. Key is track|artist only (not isPlaying).
    val contentKey = remember(event.track, event.artist) { "${event.track}|${event.artist}" }
    var isTextCollapsed by remember { mutableStateOf(false) }
    LaunchedEffect(contentKey) {
        isTextCollapsed = false
        delay(CutoutCenterCollapsePolicy.CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS)
        isTextCollapsed = true
    }

    // Collapse spring shared with cutout right-lane width animation.
    val textCollapseSpec = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    AnimatedContent(
        targetState = event.albumArt,
        transitionSpec = {
            (fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                (fadeOut(motionScheme.fastEffectsSpec()) +
                    scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                SizeTransform(clip = false)
        },
        contentKey = { it?.hashCode() ?: 0 },
        label = "kg_media_icon",
    ) { art ->
        if (art != null) {
            val rotation: Float
            if (event.isPlaying) {
                val transition = rememberInfiniteTransition(label = "kg_media_art_roll")
                val animatedRotation by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(8000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "kg_media_art_rotation",
                )
                rotation = animatedRotation
            } else {
                rotation = 0f
            }

            Image(
                bitmap = art.toScaledBitmap(MediaChipIconSize),
                contentDescription = null,
                modifier = Modifier
                    .size(MediaChipIconSize)
                    .clip(CircleShape)
                    .graphicsLayer { rotationZ = rotation },
                contentScale = ContentScale.Crop,
            )
        } else {
            PillEventIcon(event, tint = AlphaColors.DbLockscreenPill.text)
        }
    }

    // Expanded text: hard-capped width + ellipsis (never pushes transport off-chip).
    // No weight here — a weighted empty AnimatedVisibility would keep the chip at max width
    // when collapsed. Parent Row already has animateContentSize for the width change.
    AnimatedVisibility(
        visible = !isTextCollapsed,
        enter = expandHorizontally(
            expandFrom = Alignment.Start,
            animationSpec = textCollapseSpec,
        ) + fadeIn(motionScheme.fastEffectsSpec()),
        exit = shrinkHorizontally(
            shrinkTowards = Alignment.Start,
            animationSpec = textCollapseSpec,
        ) + fadeOut(motionScheme.fastEffectsSpec()),
    ) {
        Row(
            modifier = Modifier
                .padding(start = SpaceXs)
                .width(MediaTextExpandedMaxWidth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = event,
                transitionSpec = {
                    (fadeIn(motionScheme.defaultEffectsSpec()) +
                        scaleIn(initialScale = 0.85f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                        (fadeOut(motionScheme.fastEffectsSpec()) +
                            scaleOut(targetScale = 0.85f, animationSpec = motionScheme.fastSpatialSpec())) using
                        SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() },
                        )
                },
                contentKey = { "${it.track}|${it.artist}" },
                label = "kg_media_text",
                modifier = Modifier.fillMaxWidth(),
            ) { ev ->
                if (ev.artist.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
                            style = PillPrimary,
                            color = AlphaColors.DbLockscreenPill.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = 1),
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = AlphaColors.DbLockscreenPill.textHint,
                        )
                        Text(
                            ev.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = AlphaColors.DbLockscreenPill.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 48.dp),
                        )
                    }
                } else {
                    Text(
                        ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
                        style = PillPrimary,
                        color = AlphaColors.DbLockscreenPill.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = 1),
                    )
                }
            }
        }
    }

    // Transport is fixed-size and always after text — never clipped by long titles.
    Spacer(Modifier.width(SpaceXs))
    ActionButton(
        icon = ActionIcon.SKIP_PREV,
        color = skipIcon,
        bgColor = skipBg,
        onClick = { viewModel.skipPrev() },
        size = MediaActionSize,
        iconSize = MediaActionIconSize,
    )
    Spacer(Modifier.width(SpaceXxs))
    Surface(
        onClick = { viewModel.togglePlayPause() },
        modifier = Modifier.size(MediaActionSize),
        shape = CircleShape,
        color = playBg,
        border = playBorder,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(MediaActionSize)) {
            Icon(
                if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (event.isPlaying) R.string.ax_dynamic_bar_pause
                    else R.string.ax_dynamic_bar_play,
                ),
                tint = playIcon,
                modifier = Modifier.size(MediaActionIconSize),
            )
        }
    }
    Spacer(Modifier.width(SpaceXxs))
    ActionButton(
        icon = ActionIcon.SKIP_NEXT,
        color = skipIcon,
        bgColor = skipBg,
        onClick = { viewModel.skipNext() },
        size = MediaActionSize,
        iconSize = MediaActionIconSize,
    )
}

@Composable
private fun KeyguardBatteryChip(
    info: KeyguardBatteryInfo,
    batteryString: String,
    height: Dp,
    blurred: Boolean,
    onClick: () -> Unit,
) {
    val accent = when {
        info.isCharging -> BatteryChargingColor
        info.isPowerSave -> BatteryPowerSaveColor
        else -> BatteryNeutralColor
    }
    val chrome = dbLockscreenPillChrome(accent, untinted = false, blurred = blurred)
    val contentColor = chrome.content

    val parts = rememberChargingParts(batteryString)
    val isMultiLine = info.isCharging && parts.size >= 2
    // Not derived from the lane height like the circle's: this glyph shares its row with the
    // charging string, and at 32dp it ate the twelve points the tail of that string needs.
    val iconSize = SizeIconSm

    Box(
        modifier = Modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (blurred) {
            ChipGlassBackdrop(corner = height / 2, modifier = Modifier.matchParentSize())
        }
        Row(
            modifier = Modifier
                .height(height)
                .clip(ChipShape)
                .background(chrome.body)
                .border(AlphaColors.DbLockscreenPill.rimWidth, chrome.border, ChipShape)
                .widthIn(min = height, max = BatteryPillMaxWidth)
                .padding(horizontal = SpaceMd)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            
            if (info.isCharging) {
                ChargingBoltIcon(info.level, contentColor, iconSize)
            } else {
                AnimatedBatteryFillIcon(info.level, contentColor, iconSize)
            }
            Spacer(Modifier.width(SpaceXs))
            if (info.isCharging) {
                if (isMultiLine) {
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            parts[0],
                            style = PillPrimary,
                            color = contentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            parts[1],
                            style = PillPrimary.copy(fontSize = 10.sp),
                            color = contentColor.copy(alpha = AlphaSecondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(
                        if (parts.isNotEmpty()) parts[0] else batteryString,
                        style = PillPrimary,
                        color = contentColor.copy(alpha = AlphaSecondary),
                        maxLines = 1,
                        // Ellipsis, not Clip+marquee: the marquee stops after its iterations and
                        // parks on a hard cut, which reads as a rendering bug rather than "more".
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                return
            }
            Text(
                "${info.level}%",
                style = PillPrimary,
                color = contentColor,
                maxLines = 1,
            )

            val secondaryLabel = when {
                info.isCharging && info.isWireless -> stringResource(R.string.ax_dynamic_bar_wireless)
                info.isCharging -> stringResource(R.string.ax_dynamic_bar_charging)
                info.isPowerSave -> stringResource(R.string.ax_dynamic_bar_saver)
                else -> null
            }
            if (secondaryLabel != null) {
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTertiary),
                )
                Text(
                    secondaryLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaSecondary),
                    maxLines = 1,
                )
            }

            val timeRemaining = info.timeRemaining
            if (timeRemaining != null) {
                Text(
                    " · ",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaTertiary),
                )
                Text(
                    timeRemaining,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = AlphaSecondary),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun KeyguardBatteryCircle(
    info: KeyguardBatteryInfo,
    size: Dp,
    blurred: Boolean,
    onClick: () -> Unit,
) {
    // Neutral plate, unlike the text pill: the ring is the level and it has to keep its band
    // colour, which a body lerped 62% toward the same accent would swallow.
    val chrome = dbLockscreenPillChrome(Color.Unspecified, untinted = true, blurred = blurred)
    val levelColor = batteryLevelColor(info.level)
    val iconSize = size - SpaceLg
    val levelTarget = (info.level / 100f).coerceIn(0f, 1f)
    val levelAnim = remember { Animatable(levelTarget) }
    LaunchedEffect(levelTarget) {
        if (abs(levelTarget - levelAnim.value) > 0.05f) {
            levelAnim.animateTo(levelTarget, tween(300, easing = FastOutSlowInEasing))
        } else {
            levelAnim.snapTo(levelTarget)
        }
    }
    Box(
        modifier = Modifier.size(size).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (blurred) {
            ChipGlassBackdrop(corner = size / 2, modifier = Modifier.matchParentSize())
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(chrome.body)
                .border(AlphaColors.DbLockscreenPill.rimWidth, chrome.border, CircleShape)
        )
        if (info.isCharging) {
            ChargingBoltIcon(info.level, chrome.content, iconSize)
        } else {
            AnimatedBatteryFillIcon(info.level, chrome.content, iconSize)
        }
        KeyguardChipProgressRing(
            progress = levelAnim.value,
            fill = progressColorOn(levelColor, chrome.body),
            track = chrome.content.copy(alpha = ProgressTrackAlpha),
            modifier = Modifier.size(size),
        )
    }
}

@Composable
private fun ChargingBoltIcon(level: Int, color: Color, iconSize: Dp, modifier: Modifier = Modifier) {
    val boltPath = remember { Path() }
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        boltPath.rewind()
        boltPath.moveTo(w * 0.55f, 0f)
        boltPath.lineTo(w * 0.20f, h * 0.63f)
        boltPath.lineTo(w * 0.45f, h * 0.63f)
        boltPath.lineTo(w * 0.45f, h)
        boltPath.lineTo(w * 0.80f, h * 0.47f)
        boltPath.lineTo(w * 0.55f, h * 0.47f)
        boltPath.close()

        // Draw the empty battery capacity
        drawPath(boltPath, color.copy(alpha = AlphaSecondary))

        // Draw the remaining battery capacity
        clipRect(
            top = h * (1f - (level / 100f).coerceIn(0f, 1f))
        ) {
            drawPath(boltPath, color)
        }
    }
}

@Composable
private fun AnimatedBatteryFillIcon(level: Int, color: Color, iconSize: Dp) {
    val fillFraction by animateFloatAsState(
        targetValue = (level / 100f).coerceIn(0f, 1f),
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "kg_battery_fill",
    )
    // Horizontal battery: rotated 90° clockwise, scaled to text height.
    val iconH = 10.dp
    val iconW = 18.dp
    Canvas(modifier = Modifier.size(width = iconW, height = iconH)) {
        val w = size.width
        val h = size.height
        val tipW = w * 0.08f
        val tipH = h * 0.4f
        val bodyW = w - tipW - 1f
        val bodyH = h
        val cornerR = h * 0.2f

        // Tip (right side, centered vertically)
        drawRoundRect(
            color.copy(alpha = AlphaTertiary),
            topLeft = Offset(bodyW, (h - tipH) / 2f),
            size = Size(tipW, tipH),
            cornerRadius = CornerRadius(tipW / 2f, tipW / 2f),
        )

        // Body outline
        drawRoundRect(
            color.copy(alpha = AlphaTertiary),
            topLeft = Offset(0f, 0f),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(cornerR, cornerR),
        )

        // Fill (left to right)
        val fillW = bodyW * fillFraction
        if (fillW > 0f) {
            drawRoundRect(
                color,
                topLeft = Offset(0f, 0f),
                size = Size(fillW, bodyH),
                cornerRadius = CornerRadius(cornerR, cornerR),
            )
        }
    }
}

@Composable
private fun KeyguardPrimaryText(event: IslandEvent, color: Color, modifier: Modifier, batteryString: String = "") {
    when (event) {
        is IslandEvent.AudioRecording -> when (event.state) {
            RecordingState.RECORDING -> ElapsedTimeText(
                event.startTimeMs, color, modifier, event.pausedDurationMs,
            )
            RecordingState.PAUSED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_paused), color, modifier)
            RecordingState.SAVED -> MarqueeText(stringResource(R.string.ax_dynamic_bar_saved), color, modifier)
        }
        is IslandEvent.Media -> MarqueeText(event.track, color, modifier)
        is IslandEvent.Timer -> {
            if (event.endTimeMs > 0L) CountdownText(event, color, modifier)
            else MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_timer) }, color, modifier)
        }
        is IslandEvent.Stopwatch -> StopwatchTimeText(event, color, modifier)
        is IslandEvent.Notification -> MarqueeText(event.title ?: event.appName, color, modifier)
        is IslandEvent.Charging -> {
            val parts = rememberChargingParts(batteryString)
            if (parts.size >= 2) {
                Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
                    Text(
                        parts[0],
                        style = PillPrimary,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        parts[1],
                        style = PillPrimary.copy(fontSize = 10.sp),
                        color = color.copy(alpha = AlphaSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                MarqueeText(if (parts.isNotEmpty()) parts[0] else "${event.level}%", color, modifier)
            }
        }
        is IslandEvent.Bluetooth -> MarqueeText(event.deviceName.take(12), color, modifier)
        is IslandEvent.Hotspot -> {
            val hotspotLabel = stringResource(R.string.ax_dynamic_bar_hotspot)
            MarqueeText(
                if (event.numDevices > 0) "$hotspotLabel · ${event.numDevices}" else hotspotLabel,
                color, modifier,
            )
        }
        is IslandEvent.Alarm -> MarqueeText(event.label.ifEmpty { stringResource(R.string.ax_dynamic_bar_alarm) }, color, modifier)
        is IslandEvent.Call -> {
                if (event.callStartTimeMs > 0) CallTimerText(event, modifier, color)
                else MarqueeText(event.callType ?: stringResource(R.string.ax_dynamic_bar_call), color, modifier)
        }
        is IslandEvent.Torch -> MarqueeText(
            if (event.supportsLevel) "${(event.level.toFloat() / event.maxLevel * 100).toInt()}%"
            else stringResource(R.string.ax_dynamic_bar_flashlight),
            color, modifier,
        )
        is IslandEvent.RingerMode -> MarqueeText(event.label, color, modifier)
        is IslandEvent.Vpn -> MarqueeText(stringResource(R.string.ax_dynamic_bar_vpn_active), color, modifier)
        is IslandEvent.Clipboard -> MarqueeText(
            event.preview.ifEmpty { stringResource(R.string.ax_dynamic_bar_copied) }, color, modifier,
        )
        is IslandEvent.BiometricUnlock -> MarqueeText(stringResource(R.string.ax_dynamic_bar_unlocked), color, modifier)
        is IslandEvent.AppSwitch -> MarqueeText(stringResource(R.string.ax_dynamic_bar_recents), color, modifier)
        is IslandEvent.PromotedOngoing -> MarqueeText(
            event.shortText.ifEmpty { event.title.ifEmpty { event.appName } }, color, modifier,
        )
        is IslandEvent.Sports -> MarqueeText(
            when {
                event.score1.isNotEmpty() -> "${event.score1}-${event.score2}"
                event.team1Name.isNotEmpty() -> event.team1Name
                else -> stringResource(R.string.ax_dynamic_bar_sports_live_event)
            },
            color, modifier,
        )
        is IslandEvent.NowPlaying -> MarqueeText(
            "${event.songTitle} · ${event.artist}".trimEnd(' ', '·', ' '), color, modifier,
        )
        is IslandEvent.KeyguardIndication -> MarqueeText(event.text, color, modifier)
        is IslandEvent.AospChip -> {
            val text = (event.active.content as? OngoingActivityChipModel.Content.Text)?.text
            if (!text.isNullOrEmpty()) MarqueeText(text, color, modifier)
        }
    }
}

@Composable
private fun secondaryTextFor(event: IslandEvent): String? = when (event) {
    is IslandEvent.Media -> event.artist.takeIf { it.isNotBlank() }
    is IslandEvent.AudioRecording -> event.appName.takeIf { it.isNotBlank() }
    is IslandEvent.Timer -> event.label.takeIf { it.isNotBlank() }
    is IslandEvent.Stopwatch -> event.label.takeIf { it.isNotBlank() }
    is IslandEvent.Charging -> event.timeRemaining
    is IslandEvent.Bluetooth -> if (event.batteryLevel >= 0) "${event.batteryLevel}%" else null
    is IslandEvent.Hotspot -> if (event.numDevices > 0) stringResource(R.string.ax_dynamic_bar_hotspot_devices, event.numDevices) else null
    is IslandEvent.Alarm -> {
        if (event.triggerTimeMs > 0) {
            val cal = Calendar.getInstance().apply { timeInMillis = event.triggerTimeMs }
            "%d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        } else null
    }
    is IslandEvent.Call -> event.callerName
    is IslandEvent.Vpn -> stringResource(R.string.ax_dynamic_bar_active)
    is IslandEvent.BiometricUnlock -> event.sourceName
    is IslandEvent.AppSwitch -> null
    is IslandEvent.Notification -> event.text?.take(30)
    is IslandEvent.PromotedOngoing -> event.text.takeIf { it.isNotBlank() }?.take(20)
    is IslandEvent.Sports -> when {
        event.team1Name.isNotEmpty() || event.team2Name.isNotEmpty() ->
            "${event.team1Name} ${stringResource(R.string.ax_dynamic_bar_sports_vs)} ${event.team2Name}"
        event.score1.isNotEmpty() -> "${event.score1}-${event.score2}"
        else -> stringResource(R.string.ax_dynamic_bar_sports_live_event)
    }
    is IslandEvent.KeyguardIndication -> when (event.indicationType) {
        IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC -> stringResource(R.string.ax_dynamic_bar_biometric)
        IslandEvent.KeyguardIndication.IndicationType.TRUST -> stringResource(R.string.ax_dynamic_bar_trust)
        IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT -> stringResource(R.string.ax_dynamic_bar_alignment)
        else -> null
    }
    else -> null
}


@Composable
private fun CallTimerText(event: IslandEvent.Call, modifier: Modifier, overrideColor: Color? = null) {
    val isActive = event.callType == "Phone:active"
    if (isActive) {
        var elapsedMs by remember(event.callStartTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.callStartTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.callStartTimeMs) {
            while (true) {
                delay(1000)
                elapsedMs = (System.currentTimeMillis() - event.callStartTimeMs).coerceAtLeast(0L)
            }
        }
        val color = overrideColor ?: GreenAccent
        Text(formatElapsedTime(elapsedMs), color = color, style = PillMono, modifier = modifier)
    } else {
        MarqueeText(stringResource(R.string.ax_dynamic_bar_incoming_call), overrideColor ?: BlueAccent, modifier)
    }
}

@Composable
private fun SportsChipTeamBadge(name: String, icon: Drawable?, contentColor: Color, iconSize: Dp) {
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(iconSize),
            contentDescription = name,
            modifier = Modifier.size(iconSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(iconSize).clip(CircleShape)
                .background(contentColor.copy(alpha = AlphaIconBg)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                style = TsBadge,
                color = contentColor,
            )
        }
    }
}

private enum class ActionIcon { PLAY, PAUSE, STOP, SKIP_PREV, SKIP_NEXT }

private data class ChipAction(
    val icon: ActionIcon,
    val perform: (AxDynamicBarChipViewModel, IslandEvent, Context) -> Unit,
)

private fun actionsFor(event: IslandEvent, context: Context): List<ChipAction> = when (event) {
    is IslandEvent.Media -> listOf(
        ChipAction(ActionIcon.SKIP_PREV) { vm, _, _ -> vm.skipPrev() },
        ChipAction(if (event.isPlaying) ActionIcon.PAUSE else ActionIcon.PLAY) { vm, _, _ ->
            vm.togglePlayPause()
        },
        ChipAction(ActionIcon.SKIP_NEXT) { vm, _, _ -> vm.skipNext() },
    )
    is IslandEvent.AudioRecording -> {
        val classified = event.actions.map { it to it.action.classify(context, it.action.actionIntent?.creatorPackage ?: context.packageName) }
        val pauseResume = classified.firstOrNull { (_, k) -> k == NotificationActionType.PAUSE || k == NotificationActionType.RESUME }?.first
        val stop = classified.firstOrNull { (_, k) -> k == NotificationActionType.STOP || k == NotificationActionType.DELETE }?.first
        listOfNotNull(
            pauseResume?.let { action ->
                ChipAction(
                    if (event.state == RecordingState.RECORDING) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
            stop?.let { action ->
                ChipAction(ActionIcon.STOP) { _, _, ctx ->
                    action.action.actionIntent?.sendWithBal(ctx)
                }
            },
        )
    }
    is IslandEvent.Timer -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isPaused) ActionIcon.PLAY else ActionIcon.PAUSE,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Stopwatch -> {
        val toggleAction = event.actions.firstOrNull()
        listOfNotNull(
            toggleAction?.let { action ->
                ChipAction(
                    if (event.isRunning) ActionIcon.PAUSE else ActionIcon.PLAY,
                ) { _, _, ctx -> action.action.actionIntent?.sendWithBal(ctx) }
            },
        )
    }
    is IslandEvent.Torch -> listOf(
        ChipAction(ActionIcon.STOP) { vm, _, _ -> vm.toggleTorch() },
    )
    else -> emptyList()
}

@Composable
private fun ActionButton(
    icon: ActionIcon,
    color: Color,
    bgColor: Color,
    onClick: () -> Unit,
    size: Dp = ActionSize,
    iconSize: Dp = ActionIconSize,
) {
    val imageVector = when (icon) {
        ActionIcon.PLAY -> Icons.Filled.PlayArrow
        ActionIcon.PAUSE -> Icons.Filled.Pause
        ActionIcon.STOP -> Icons.Filled.Stop
        ActionIcon.SKIP_PREV -> Icons.Filled.SkipPrevious
        ActionIcon.SKIP_NEXT -> Icons.Filled.SkipNext
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = bgColor,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            Icon(
                imageVector,
                contentDescription = icon.name,
                tint = color,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun MarqueeText(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = color,
        style = PillPrimary,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = modifier.widthIn(max = 120.dp).basicMarquee(iterations = 1),
    )
}

@Composable
private fun ElapsedTimeText(
    startTimeMs: Long,
    color: Color,
    modifier: Modifier,
    pausedDurationMs: Long = 0L,
) {
    var elapsedMs by remember(startTimeMs, pausedDurationMs) {
        mutableLongStateOf(
            (System.currentTimeMillis() - startTimeMs - pausedDurationMs).coerceAtLeast(0L)
        )
    }
    LaunchedEffect(startTimeMs, pausedDurationMs) {
        while (true) {
            delay(1000)
            elapsedMs = (System.currentTimeMillis() - startTimeMs - pausedDurationMs)
                .coerceAtLeast(0L)
        }
    }
    Text(formatElapsedTime(elapsedMs), color = color, style = PillMono, modifier = modifier)
}

@Composable
private fun CountdownText(event: IslandEvent.Timer, color: Color, modifier: Modifier) {
    if (event.isPaused) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
    } else {
        var remainingMs by remember(event.endTimeMs) {
            mutableLongStateOf((event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        LaunchedEffect(event.endTimeMs) {
            while (remainingMs > 0L) {
                delay(500)
                remainingMs = (event.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
            }
        }
        Text(formatCountdownLong(remainingMs), color = color, style = PillMono, modifier = modifier)
    }
}

@Composable
private fun StopwatchTimeText(event: IslandEvent.Stopwatch, color: Color, modifier: Modifier) {
    if (!event.isRunning) {
        Text(stringResource(R.string.ax_dynamic_bar_paused), color = color, style = PillMono, modifier = modifier)
    } else {
        var elapsedMs by remember(event.startTimeMs) {
            mutableLongStateOf((System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L))
        }
        LaunchedEffect(event.startTimeMs) {
            while (true) {
                delay(200)
                elapsedMs = (System.currentTimeMillis() - event.startTimeMs).coerceAtLeast(0L)
            }
        }
        Text(formatStopwatch(elapsedMs), color = color, style = PillMono, modifier = modifier)
    }
}
