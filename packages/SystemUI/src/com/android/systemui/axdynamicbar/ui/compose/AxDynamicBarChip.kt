package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.abs
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.alpha.theme.AlphaColors
import android.graphics.drawable.Drawable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.stringResource
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.AlphaIconBg
import com.android.systemui.axdynamicbar.shared.AlphaSecondary
import com.android.systemui.axdynamicbar.shared.AlphaTertiary
import com.android.systemui.axdynamicbar.shared.IslandStackCountBadge
import com.android.systemui.axdynamicbar.shared.PillPrimary
import com.android.systemui.axdynamicbar.shared.PillTextMaxWidth
import com.android.systemui.axdynamicbar.shared.ShapeXl
import com.android.systemui.axdynamicbar.shared.ShapeXs
import com.android.systemui.axdynamicbar.shared.SizeBadge
import com.android.systemui.axdynamicbar.shared.SpaceMd
import com.android.systemui.axdynamicbar.shared.SpaceSm
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.StatusBarPillWidth
import com.android.systemui.axdynamicbar.shared.StatusBarPillWidthWithBadge
import com.android.systemui.axdynamicbar.shared.TsBadge
import com.android.systemui.axdynamicbar.shared.chipTintAccentFor
import com.android.systemui.axdynamicbar.shared.chipProgressFor
import com.android.systemui.axdynamicbar.shared.iconKeyFor
import com.android.systemui.axdynamicbar.shared.dbStatusBarChipChrome
import com.android.systemui.axdynamicbar.shared.textKeyFor
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.axdynamicbar.ui.AxDynamicBarChipViewModel
import com.android.systemui.res.R

private val ChipShape = ShapeXl
private val ChipHeight = 24.dp
/** ShapeXl's 32dp, capped at half the height — the blur drawable takes a radius, not a shape. */
private val ChipCornerRadius = ChipHeight / 2

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AxDynamicBarChip(
    viewModel: AxDynamicBarChipViewModel,
    modifier: Modifier = Modifier,
    ignoreKeyguard: Boolean = false,
) {
    val state by viewModel.chipState.collectAsStateWithLifecycle()
    val isOnKeyguard by viewModel.isOnKeyguard.collectAsStateWithLifecycle()
    val keyguardCarrier by viewModel.keyguardCarrierText.collectAsStateWithLifecycle()

    val carrierName = if (isOnKeyguard && ignoreKeyguard) keyguardCarrier.takeIf { it.isNotBlank() } else null
    val screenWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }

    val touchSlop = LocalViewConfiguration.current.touchSlop

    val motionScheme = MaterialTheme.motionScheme

    AnimatedVisibility(
        visible = state != null && (ignoreKeyguard || !isOnKeyguard),
        enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.8f, animationSpec = motionScheme.defaultSpatialSpec()),
        exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.8f, animationSpec = motionScheme.fastSpatialSpec()),
        modifier = modifier
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                val centerX = (bounds.left + bounds.right) / 2f
                if (screenWidthPx > 0f) {
                    viewModel.updateChipCenterX(centerX / screenWidthPx)
                }
            },
    ) {
        state?.let { chipState ->
            val displayEvent = chipState.notificationAlert ?: chipState.event
            val isAlert = chipState.notificationAlert != null

            AnimatedContent(
                targetState = ChipDisplay(displayEvent, isAlert),
                transitionSpec = {
                    (fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(initialScale = 0.92f, animationSpec = motionScheme.defaultSpatialSpec())) togetherWith
                        (fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(targetScale = 0.92f, animationSpec = motionScheme.fastSpatialSpec())) using
                        SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> motionScheme.defaultSpatialSpec() })
                },
                contentKey = { if (it.isAlert) "alert" else it.event::class.simpleName },
                label = "chip_event",
            ) { display ->
                val rawAccent = chipTintAccentFor(display.event)
                val accent by animateColorAsState(rawAccent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "accent")
                val blurred = rememberChipBlurEnabled()
                val rawChrome = dbStatusBarChipChrome(rawAccent, blurred)
                val bodyColor by animateColorAsState(
                    rawChrome.body, MaterialTheme.motionScheme.fastEffectsSpec(), label = "glass_body",
                )
                val borderColor by animateColorAsState(
                    rawChrome.border, MaterialTheme.motionScheme.fastEffectsSpec(), label = "glass_border",
                )
                val contentColor by animateColorAsState(
                    rawChrome.content, MaterialTheme.motionScheme.fastEffectsSpec(), label = "content",
                )
                val rawProgress = chipProgressFor(display.event)
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

                val chipWidth =
                    if (chipState.eventCount > 1) StatusBarPillWidthWithBadge else StatusBarPillWidth

                Box(
                    modifier = Modifier.fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    // Behind the body, matched to it: the tint above is what carries the colour,
                    // this only destroys the detail underneath so the tint has something to sit on.
                    if (blurred) {
                        ChipGlassBackdrop(
                            corner = ChipCornerRadius,
                            modifier = Modifier.height(ChipHeight).width(chipWidth),
                        )
                    }
                    Row(
                        modifier =
                            Modifier.height(ChipHeight)
                                .width(chipWidth)
                                .islandGestures(
                                    chipState = chipState,
                                    viewModel = viewModel,
                                    touchSlop = touchSlop,
                                    expandableShape = ChipShape,
                                )
                                .clip(ChipShape)
                                .background(bodyColor)
                                .border(AlphaColors.DbStatusBarChip.rimWidth, borderColor, ChipShape)
                                .then(
                                    if (progress != null) {
                                        val chip = AlphaColors.DbStatusBarChip
                                        val trackColor =
                                            lerp(accent, contentColor, chip.progressTrackBlend)
                                        val fillColor =
                                            lerp(accent, contentColor, chip.progressFillBlend)
                                        val barHeight = chip.progressHeight
                                        Modifier.drawWithContent {
                                            drawContent()
                                            val barH = barHeight.toPx()
                                            val y = size.height - barH
                                            drawRect(
                                                trackColor,
                                                topLeft = Offset(0f, y),
                                                size = Size(size.width, barH),
                                            )
                                            drawRect(
                                                fillColor,
                                                topLeft = Offset(0f, y),
                                                size = Size(size.width * progress, barH),
                                            )
                                        }
                                    } else Modifier
                                )
                                .padding(horizontal = SpaceMd),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (carrierName != null) {
                            Text(
                                text = carrierName,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaSecondary),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 56.dp),
                            )
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = AlphaTertiary),
                            )
                        }
                        if (display.isAlert && display.event is IslandEvent.Notification) {
                            AnimatedContent(
                                targetState = display.event,
                                transitionSpec = {
                                    (fadeIn(motionScheme.defaultEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec()))
                                        .using(sizeTransform = null)
                                },
                                contentKey = {
                                    (it as? IslandEvent.Notification)?.sbn?.key
                                },
                                label = "alert_content",
                            ) { event ->
                                val notif = event as IslandEvent.Notification
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    notif.appIcon?.let { icon ->
                                        Image(
                                            bitmap = icon.toScaledBitmap(16.dp),
                                            contentDescription = null,
                                            modifier =
                                                Modifier.size(16.dp)
                                                    .clip(ShapeXs),
                                            contentScale = ContentScale.Crop,
                                        )
                                        Spacer(Modifier.width(SpaceXs))
                                    }
                                    Text(
                                        text = notif.appName ?: "",
                                        style = PillPrimary,
                                        color = contentColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 100.dp).basicMarquee(iterations = 1),
                                    )
                                }
                            }
                        } else if (display.event is IslandEvent.Sports && display.event.team2Name.isNotEmpty()) {
                            val sport = display.event as IslandEvent.Sports
                            StatusBarSportsTeamBadge(sport.team1Name, sport.team1Icon, contentColor)
                            Spacer(Modifier.width(SpaceXs))
                            Text(
                                if (sport.score1.isNotEmpty()) "${sport.score1} - ${sport.score2}"
                                else stringResource(R.string.ax_dynamic_bar_sports_vs),
                                style = PillPrimary,
                                color = contentColor,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(SpaceXs))
                            StatusBarSportsTeamBadge(sport.team2Name, sport.team2Icon, contentColor)
                        } else {
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
                                label = "chip_icon",
                            ) { event ->
                                PillEventIcon(event, tint = contentColor)
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
                                label = "chip_text",
                                modifier = Modifier.weight(1f, fill = false),
                            ) { event ->
                                PillEventText(
                                    event,
                                    Modifier.widthIn(max = PillTextMaxWidth),
                                    overrideColor = contentColor,
                                )
                            }
                            if (chipState.eventCount > 1) {
                                Spacer(Modifier.width(SpaceXs))
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .height(SizeBadge)
                                        .widthIn(min = SizeBadge)
                                        .background(
                                            lerp(
                                                accent,
                                                contentColor,
                                                AlphaColors.DbStatusBarChip.badgeBodyBlend,
                                            ),
                                            RoundedCornerShape(SizeBadge / 2),
                                        )
                                        .padding(horizontal = 3.dp),
                                ) {
                                    Text(
                                        text = "${chipState.eventCount}",
                                        style = TsBadge,
                                        color = contentColor,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBarSportsTeamBadge(name: String, icon: Drawable?, contentColor: Color) {
    val badgeSize = 16.dp
    if (icon != null) {
        Image(
            bitmap = icon.toScaledBitmap(badgeSize),
            contentDescription = name,
            modifier = Modifier.size(badgeSize).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier.size(badgeSize).clip(CircleShape)
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

private data class ChipDisplay(val event: IslandEvent, val isAlert: Boolean)

