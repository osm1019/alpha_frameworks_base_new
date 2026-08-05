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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.axdynamicbar.ui.KeyguardBatteryInfo
import com.android.systemui.media.ax.ui.compose.MediaChrome
import com.android.systemui.res.R
import kotlin.math.abs
import kotlinx.coroutines.delay
import android.content.Context
import android.graphics.drawable.Drawable
import java.util.Calendar

/** Default keyguard island height for non-media events. */
private val ChipHeight = 36.dp
private val ChipShape = ShapeChip
private val ChipIconSize = ChipHeight - SpaceLg
/** Album art inside the taller media chip (matches ~48dp affordance row). */
private val MediaChipIconSize = 32.dp
private val ActionSize = SpacePanel
private val ActionIconSize = SizeBadge
/** Transport hit targets scaled for media's affordance-matched height. */
private val MediaActionSize = 28.dp
private val MediaActionIconSize = 16.dp
private val BatteryIconSize = ChipHeight - SpaceXxl
private val CountBadgeHeight = ChipHeight / 2

/**
 * Cap on the track/artist lane while expanded so art + text + transport + badge fit the
 * chip's max width (260dp). Text ellipsizes inside this; collapse hides the lane entirely.
 * Timing reuses [CutoutCenterCollapsePolicy.CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS] (5s).
 */
private val MediaTextExpandedMaxWidth = 90.dp

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
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val isKeyguardEnabled by viewModel.isKeyguardEnabled.collectAsStateWithLifecycle()
    val keyguardBatteryChipMode by viewModel.keyguardBatteryChipMode.collectAsStateWithLifecycle()
    val batteryInfo by viewModel.keyguardBatteryInfo.collectAsStateWithLifecycle()
    val isKeyguardExpanded by viewModel.isKeyguardExpanded.collectAsStateWithLifecycle()
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val batteryString by viewModel.batteryString.collectAsStateWithLifecycle()

    val motionScheme = MaterialTheme.motionScheme

    Box(modifier = modifier) {

        val expandedVisibleState = remember { MutableTransitionState(false) }
        expandedVisibleState.targetState = isKeyguardExpanded && state != null
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
            state?.let {
                KeyguardExpandedContent(
                    event = it.event,
                    allEvents = it.allEvents,
                    interactor = viewModel.interactor,
                    onCollapse = { viewModel.keyguardExpansion.collapse() },
                    hapticsViewModelFactory = viewModel.interactor.sliderHapticsViewModelFactory,
                )
            }
        }

        AnimatedVisibility(
            visible = isOnKeyguard && isEnabled && isKeyguardEnabled && !isKeyguardExpanded,
            enter = fadeIn(tween(durationMillis = 200, delayMillis = 300)) +
                scaleIn(
                    initialScale = 0.9f,
                    animationSpec = tween(durationMillis = 200, delayMillis = 300),
                ),
            exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.9f, animationSpec = motionScheme.fastSpatialSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .pointerInput(viewModel) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Initial)
                        val startX = down.position.x
                        var dragging = false
                        var totalDx = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) {
                                if (dragging) {
                                    change.consume()
                                    if (totalDx > 0) viewModel.cyclePrev()
                                    else viewModel.cycleNext()
                                }
                                break
                            }
                            val dx = change.position.x - startX
                            if (!dragging && abs(dx) > touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                totalDx = dx
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            val chipState = state
            if (chipState != null) {
                val displayEvent = chipState.notificationAlert ?: chipState.event

                AnimatedContent(
                    targetState = displayEvent,
                    transitionSpec = {
                        (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(
                            initialScale = 0.95f,
                            animationSpec = motionScheme.defaultSpatialSpec(),
                        )) togetherWith (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
                            targetScale = 0.95f,
                            animationSpec = motionScheme.fastSpatialSpec(),
                        )) using SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                    },
                    contentKey = { it::class.simpleName },
                    label = "keyguard_chip_event",
                ) { event ->
                    val rawAccent = chipAccentColorFor(event)
                    val accent by animateColorAsState(
                        rawAccent,
                        MaterialTheme.motionScheme.fastEffectsSpec(),
                        label = "kg_accent",
                    )
                    val contentColor by animateColorAsState(
                        chipContentColorOn(rawAccent),
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
                        accent = accent,
                        contentColor = contentColor,
                        progress = progress,
                        eventCount = chipState.eventCount,
                        viewModel = viewModel,
                        batteryString = batteryString,
                    )
                }
            } else {
                KeyguardBatteryChip(
                    batteryInfo,
                    keyguardBatteryChipMode,
                    batteryString,
                    modifier,
                )
            }
        }
    }
}

@Composable
private fun KeyguardChipBody(
    event: IslandEvent,
    accent: Color,
    contentColor: Color,
    progress: Float?,
    eventCount: Int,
    viewModel: AxDynamicBarChipViewModel,
    batteryString: String = "",
) {
    val context = LocalContext.current
    val motionScheme = MaterialTheme.motionScheme

    val parts = rememberChargingParts(batteryString)
    val isMultiLineCharging = event is IslandEvent.Charging && parts.size >= 2
    val isMedia = event is IslandEvent.Media
    // Match keyguard shortcut diameter for media so the bottom row reads as one band.
    val mediaChipHeight = dimensionResource(R.dimen.keyguard_affordance_fixed_height)
    val dynamicHeight = when {
        isMedia -> mediaChipHeight
        isMultiLineCharging -> 48.dp
        else -> ChipHeight
    }

    // Media: dark glass + accent play/progress. Other events keep full-fill accent body.
    val bodyColor = if (isMedia) MediaChrome.GlassBody else accent
    val onBody = if (isMedia) MediaChrome.OnGlass else contentColor
    val progressTrack =
        if (isMedia) MediaChrome.ProgressTrack else lerp(accent, contentColor, 0.2f)
    val progressFill =
        if (isMedia) accent else lerp(accent, contentColor, 0.6f)
    val progressBarH = if (isMedia) MediaChrome.ProgressHeight else SizeStrokeWidth

    // Media needs room for art + text + 3 transport buttons + optional stack badge.
    val chipMaxWidth = if (isMedia) 280.dp else 260.dp

    Box(contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .height(dynamicHeight)
                .widthIn(min = 48.dp, max = chipMaxWidth)
                .clip(ChipShape)
                .background(bodyColor)
                .then(
                    if (isMedia) {
                        Modifier.border(1.dp, MediaChrome.GlassBorder, ChipShape)
                    } else {
                        Modifier
                    },
                )
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
                .clickable {
                    when (event) {
                        is IslandEvent.Notification ->
                            viewModel.launchNotificationFromKeyguard(event)

                        is IslandEvent.KeyguardIndication,
                        is IslandEvent.AppSwitch -> { }
                        else -> viewModel.keyguardExpansion.toggle()
                    }
                }
                .padding(start = SpaceSm, end = SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (event is IslandEvent.Media) {
                KeyguardMediaChipContent(
                    event = event,
                    accent = accent,
                    viewModel = viewModel,
                )
            } else if (event is IslandEvent.Sports && event.team2Name.isNotEmpty()) {
                SportsChipTeamBadge(event.team1Name, event.team1Icon, contentColor)
                Spacer(Modifier.width(SpaceXs))
                Text(
                    if (event.score1.isNotEmpty()) "${event.score1} - ${event.score2}"
                        else stringResource(R.string.ax_dynamic_bar_sports_vs),
                    style = PillPrimary,
                    color = contentColor,
                    maxLines = 1,
                )
                Spacer(Modifier.width(SpaceXs))
                SportsChipTeamBadge(event.team2Name, event.team2Icon, contentColor)
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

            if (eventCount > 1) {
                Spacer(Modifier.width(SpaceXs))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(CountBadgeHeight)
                        .widthIn(min = CountBadgeHeight)
                        .background(
                            if (isMedia) MediaChrome.SkipNeutral
                            else lerp(accent, contentColor, AlphaDisabled),
                            ShapeChip,
                        )
                        .padding(horizontal = SpaceXxs),
                ) {
                    Text(
                        "$eventCount",
                        style = TsBadge,
                        color = onBody,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Lockscreen media capsule content (Phase 1): spinning art, marquee meta, floating
 * transport. Play is filled with art-derived [accent]; skip buttons stay tonal on glass.
 *
 * **Expanded:** original flexible layout — text is [weight] + [widthIn] + ellipsis so it
 * never pushes transport/badge out of the chip (max 260dp).
 *
 * **Collapsed (after 5s idle):** text lane removed (width-only); art + prev/play/next remain.
 * Re-expands on track/artist change only (not play/pause). Height stays fixed on parent.
 */
@Composable
private fun RowScope.KeyguardMediaChipContent(
    event: IslandEvent.Media,
    accent: Color,
    viewModel: AxDynamicBarChipViewModel,
) {
    val motionScheme = MaterialTheme.motionScheme
    // Play is solid art accent. Skip buttons use an opaque blend toward the same accent
    // (not a low-alpha overlay — that vanished on the dark glass and looked unthemed).
    val onAccent = chipContentColorOn(accent)
    val skipBg = MediaChrome.skipBackground(accent)

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
            PillEventIcon(event, tint = MediaChrome.OnGlass)
        }
    }

    // Expanded text: hard-capped width + ellipsis (never pushes transport/badge off-chip).
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
                            color = MediaChrome.OnGlass,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .basicMarquee(iterations = 1),
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MediaChrome.OnGlassHint,
                        )
                        Text(
                            ev.artist,
                            style = MaterialTheme.typography.labelSmall,
                            color = MediaChrome.OnGlassSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 48.dp),
                        )
                    }
                } else {
                    Text(
                        ev.track.ifEmpty { stringResource(R.string.ax_dynamic_bar_music) },
                        style = PillPrimary,
                        color = MediaChrome.OnGlass,
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
        color = onAccent,
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
        color = accent,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(MediaActionSize)) {
            Icon(
                if (event.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (event.isPlaying) R.string.ax_dynamic_bar_pause
                    else R.string.ax_dynamic_bar_play,
                ),
                tint = onAccent,
                modifier = Modifier.size(MediaActionIconSize),
            )
        }
    }
    Spacer(Modifier.width(SpaceXxs))
    ActionButton(
        icon = ActionIcon.SKIP_NEXT,
        color = onAccent,
        bgColor = skipBg,
        onClick = { viewModel.skipNext() },
        size = MediaActionSize,
        iconSize = MediaActionIconSize,
    )
}

@Composable
private fun KeyguardBatteryChip(
    info: KeyguardBatteryInfo,
    keyguardBatteryChipMode: Int,
    batteryString: String,
    modifier: Modifier,
) {
    if (keyguardBatteryChipMode <= 0) return

    if (keyguardBatteryChipMode == 1 && !info.isCharging) return

    val accent = when {
        info.isCharging -> BatteryChargingColor
        info.isPowerSave -> BatteryPowerSaveColor
        else -> BatteryNeutralColor
    }
    val contentColor = chipContentColorOn(accent)

    val parts = rememberChargingParts(batteryString)
    val isMultiLine = info.isCharging && parts.size >= 2
    val dynamicHeight = if (isMultiLine) 48.dp else ChipHeight

    Box(contentAlignment = Alignment.Center) {
        Row(
            modifier = modifier
                .height(dynamicHeight)
                .clip(ChipShape)
                .background(accent)
                .widthIn(min = 48.dp, max = 260.dp)
                .padding(horizontal = SpaceMd)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            
            if (info.isCharging) {
                AnimatedChargingBoltIcon(info.level, contentColor, BatteryIconSize)
            } else {
                AnimatedBatteryFillIcon(info.level, contentColor, BatteryIconSize)
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
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(),
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
private fun AnimatedChargingBoltIcon(level: Int, color: Color, iconSize: Dp = BatteryIconSize) {
    if (level == 100) {
        ChargingBoltIcon(level, color, iconSize)
        return
    }
    val transition = rememberInfiniteTransition(label = "kg_bolt")
    val glow by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "kg_bolt_glow",
    )
    ChargingBoltIcon(level, color, iconSize, Modifier.graphicsLayer {
        this.alpha = glow
    })
}

@Composable
private fun ChargingBoltIcon(level: Int, color: Color, iconSize: Dp = BatteryIconSize, modifier: Modifier = Modifier) {
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
private fun AnimatedBatteryFillIcon(level: Int, color: Color, iconSize: Dp = BatteryIconSize) {
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
private fun SportsChipTeamBadge(name: String, icon: Drawable?, contentColor: Color) {
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(ChipIconSize),
            contentDescription = name,
            modifier = Modifier.size(ChipIconSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(ChipIconSize).clip(CircleShape)
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
