/*
 * Copyright 2025-2026 AxionOS
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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.android.systemui.qs.ax.ui.compose

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import java.util.function.Consumer
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import com.android.compose.animation.Expandable as ExpandableContainer
import com.android.compose.animation.rememberExpandableController
import com.android.systemui.alpha.style.qs.QSTileStyleWrapper
import com.android.systemui.alpha.style.qs.rememberQsTileStyleRenderer
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.shared.model.asImageBitmap
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.media.ax.ui.compose.MediaChrome
import com.android.systemui.media.ax.ui.compose.rememberSquiggleAnimationEnabled
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.WaveformSeekBar
import com.android.systemui.media.remedia.domain.model.MediaActionModel
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.media.remedia.ui.compose.Media
import com.android.systemui.media.remedia.ui.compose.MediaPresentationStyle
import com.android.systemui.media.remedia.ui.compose.MediaUiBehavior
import com.android.systemui.media.remedia.ui.viewmodel.MediaCarouselVisibility
import com.android.systemui.media.remedia.ui.viewmodel.MediaViewModel
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.ui.gesture.mediaOverscrollToDismiss
import com.android.systemui.qs.ax.ui.model.AxMediaSurface
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R
import kotlin.math.max

@Composable
fun AxMediaPanel(
    viewModel: AxMediaViewModel,
    span: AxQsSpan,
    mediaViewModelFactory: MediaViewModel.Factory? = null,
    modifier: Modifier = Modifier,
    showPlaceholder: Boolean = false,
    interactive: Boolean = true,
    allowGuts: Boolean = false,
    surface: AxMediaSurface = AxMediaSurface.CONTROL,
) {
    val sessions = viewModel.visibleSessions(surface)
    val lastMediaPackage by viewModel.lastMediaPackage.collectAsStateWithLifecycle()
    if (!interactive) {
        if (sessions.isEmpty()) {
            if (!showPlaceholder) return
            LaunchedEffect(Unit) { viewModel.synchronizeSession(null) }
            AxMediaCard(
                viewModel = viewModel,
                session = null,
                lastMediaPackage = lastMediaPackage,
                span = span,
                interactive = interactive,
                allowGuts = false,
                surface = surface,
                modifier = modifier,
            )
            return
        }
        val selectedKey = viewModel.currentSession?.key
        val session = sessions.firstOrNull { it.key == selectedKey } ?: sessions.first()
        LaunchedEffect(session.key) { viewModel.synchronizeSession(session.key) }
        AxMediaCard(
            viewModel = viewModel,
            session = session,
            lastMediaPackage = lastMediaPackage,
            span = span,
            interactive = interactive,
            allowGuts = allowGuts,
            surface = surface,
            modifier = modifier,
        )
        return
    }

    val factory = checkNotNull(mediaViewModelFactory)
    val currentSession = viewModel.currentSession
    LaunchedEffect(currentSession?.key) { viewModel.synchronizeSession(currentSession?.key) }
    if (sessions.isEmpty() && showPlaceholder) {
        AxMediaCard(
            viewModel = viewModel,
            session = null,
            lastMediaPackage = lastMediaPackage,
            span = span,
            interactive = interactive,
            allowGuts = false,
            surface = surface,
            modifier = modifier,
        )
        return
    }

    val shape =
        if (surface == AxMediaSurface.LOCKSCREEN) {
            RoundedCornerShape(viewModel.lockscreenMediaStyle.effective.cornerRadius())
        } else {
            axQsControlShape(AxQsControl.MEDIA, span)
        }
    val gesturesEnabled = !viewModel.hasVisibleGuts()
    val carouselScrollingEnabled =
        gesturesEnabled && (surface != AxMediaSurface.LOCKSCREEN || sessions.size > 1)
    var isFalseTouchDetected by remember(surface) { mutableStateOf(false) }
    // "Pin media player" keeps inactive (resumable) cards around off the lockscreen, and those are
    // only reachable if the carousel itself stays visible without an active card.
    val keepsResumableCards =
        viewModel.isMediaResumptionEnabled &&
            surface != AxMediaSurface.LOCKSCREEN &&
            sessions.isNotEmpty()
    val behavior =
        remember(viewModel, surface, carouselScrollingEnabled, keepsResumableCards) {
            MediaUiBehavior(
                isCarouselDismissible = false,
                isCarouselScrollingEnabled = carouselScrollingEnabled,
                isSettingsRevealEnabled = false,
                carouselVisibility =
                    if (keepsResumableCards) {
                        MediaCarouselVisibility.WhenNotEmpty
                    } else {
                        MediaCarouselVisibility.WhenAnyCardIsActive
                    },
                isCarouselScrollFalseTouch =
                    if (surface == AxMediaSurface.LOCKSCREEN) {
                        null
                    } else {
                        {
                            viewModel.isSwipeFalseTouch().also { isFalseTouchDetected = it }
                        }
                },
            )
        }
    val onDismissed = remember(viewModel, surface) { { viewModel.dismissBySwipe(surface) } }
    val mediaModifier =
        if (surface == AxMediaSurface.SEPARATE_QQS) {
            modifier
                .fillMaxSize()
                .clip(shape)
                .mediaOverscrollToDismiss(
                    enabled = gesturesEnabled,
                    dismissAllowed = !isFalseTouchDetected,
                    onDismissed = onDismissed,
                )
        } else {
            modifier.fillMaxSize()
        }
    Media(
        viewModelFactory = factory,
        presentationStyle = MediaPresentationStyle.Default,
        behavior = behavior,
        onDismissed = onDismissed,
        modifier = mediaModifier,
        cardFilter = { viewModel.isSessionVisible(it.key, surface) },
        carouselShape = shape,
        cardContent = { card, cardModifier ->
            viewModel.sessionForKey(card.key)?.let { session ->
                AxMediaCard(
                    viewModel = viewModel,
                    session = session,
                    lastMediaPackage = lastMediaPackage,
                    span = span,
                    interactive = interactive,
                    allowGuts = allowGuts,
                    surface = surface,
                    modifier = cardModifier.fillMaxSize(),
                )
            }
        },
        pagerIndicator = { pagerState ->
            PagerDots(
                pagerState = pagerState,
                activeColor = Color.White,
                nonActiveColor = Color.White.copy(alpha = 0.42f),
                dotSize = 3.dp,
                spaceSize = 3.dp,
                modifier =
                    if (span.rows == 1) {
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                    } else {
                        Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
                    },
            )
        },
    )
}

@Composable
private fun AxMediaCard(
    viewModel: AxMediaViewModel,
    session: MediaSessionModel?,
    lastMediaPackage: String?,
    span: AxQsSpan,
    interactive: Boolean,
    allowGuts: Boolean,
    surface: AxMediaSurface,
    modifier: Modifier = Modifier,
) {
    val title = session?.title ?: stringResource(R.string.ax_qs_media_not_playing)
    val subtitle = session?.subtitle.orEmpty()
    val isLockscreen = surface == AxMediaSurface.LOCKSCREEN
    // Each lockscreen style owns its silhouette — Minimal is a pill, not a rounded rectangle.
    val lockscreenCorner = viewModel.lockscreenMediaStyle.effective.cornerRadius()
    val shape =
        if (isLockscreen) {
            RoundedCornerShape(lockscreenCorner)
        } else {
            axQsControlShape(AxQsControl.MEDIA, span)
        }
    val layout =
        when {
            isLockscreen -> AxMediaLayout.Lockscreen
            span.rows == 1 -> AxMediaLayout.OneRow
            span.columns >= 3 -> AxMediaLayout.Expanded
            else -> AxMediaLayout.Compact
        }
    val gutsVisible = allowGuts && session?.let(viewModel::isGutsVisible) == true
    // Lockscreen styles draw their own art; only QS washes it full-bleed behind the content.
    val artwork = session?.background?.takeIf { !isLockscreen && span.columns > 1 }
    val tileBackground = AxTileDefaults.backgroundColor()
    val tileForeground = MaterialTheme.colorScheme.onSurface
    val colorScheme = session?.colorScheme
    val mediaBackground = colorScheme?.background ?: MaterialTheme.colorScheme.onSurface
    val background by
        animateColorAsState(
            targetValue =
                when {
                    artwork != null && !gutsVisible -> Color.Transparent
                    // Lockscreen glass is drawn by [LockscreenGlassBackdrop] (open tint + blur);
                    // keep the expandable colour transparent so it does not paint a denser slab.
                    isLockscreen && session != null -> Color.Transparent
                    session != null -> mediaBackground
                    else -> tileBackground
                },
            label = "AxMediaBackground",
        )
    val primary by
        animateColorAsState(
            targetValue =
                if (session != null) {
                    colorScheme?.primary ?: MaterialTheme.colorScheme.primaryFixed
                } else {
                    MaterialTheme.colorScheme.primary
                },
            label = "AxMediaPrimary",
        )
    val onPrimary by
        animateColorAsState(
            targetValue =
                if (session != null) {
                    colorScheme?.onPrimary ?: MaterialTheme.colorScheme.onPrimaryFixed
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
            label = "AxMediaOnPrimary",
        )
    val foreground by
        animateColorAsState(
            targetValue =
                if (session != null) {
                    if (isLockscreen) MediaChrome.OnGlass else Color.White
                } else {
                    tileForeground
                },
            label = "AxMediaForeground",
        )
    val animatedMediaBackground by
        animateColorAsState(targetValue = mediaBackground, label = "AxMediaArtworkOverlay")
    val colors =
        AxMediaColors(
            primary = primary,
            onPrimary = onPrimary,
            background = animatedMediaBackground,
            foreground = foreground,
        )
    val clickLabel =
        if (session != null) {
            stringResource(
                R.string.controls_media_playing_item_description,
                session.title,
                session.subtitle,
                session.appName,
            )
        } else if (lastMediaPackage != null) {
            stringResource(R.string.ax_qs_media_open_last_app)
        } else {
            null
        }
    val cardModifier =
        if (isLockscreen && session != null) {
            // Depth first (shadow needs unclipped space), then clip + luminous rim. The frosted
            // fill itself is [LockscreenGlassBackdrop] inside the expandable, not a solid
            // Compose background — that is what made the first M1 cut read as a black slab.
            modifier
                .fillMaxSize()
                .shadow(
                    elevation = MediaChrome.LockscreenGlassElevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.30f),
                    spotColor = Color.Black.copy(alpha = 0.42f),
                )
                .clip(shape)
                .border(
                    MediaChrome.LockscreenGlassBorderWidth,
                    MediaChrome.LockscreenGlassBorder,
                    shape,
                )
        } else {
            modifier.fillMaxSize().clip(shape)
        }
    ExpandableContainer(
        // Empty cards paint their own styled fill; keep expandable transparent so the style
        // wrapper is the sole chrome. Session cards keep media/artwork colors for the morph.
        // Lockscreen sessions stay transparent: glass is the dedicated backdrop below.
        controller =
            rememberExpandableController(
                color = {
                    when {
                        session == null || isLockscreen -> Color.Transparent
                        else -> background
                    }
                },
                shape = shape,
            ),
        modifier = cardModifier,
        onClick = null,
        onClickLabel = null,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Box(Modifier.fillMaxSize()) {
            if (isLockscreen && session != null) {
                LockscreenGlassBackdrop(
                    corner = lockscreenCorner,
                    artwork =
                        session.background.takeIf { viewModel.isLockscreenMediaArtEnabled },
                )
            }
            // (1) Empty / "not playing" card body — inactive small-tile UI Styles.
            if (session == null) {
                MediaStyledSurface(
                    shape = shape,
                    active = false,
                    color = tileBackground,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().combinedClickable(
                    enabled = interactive && (session != null || lastMediaPackage != null),
                    onClick = {
                        if (!gutsVisible) {
                            if (session != null) {
                                viewModel.openSession(session, expandable)
                            } else {
                                viewModel.openLastMediaApp(expandable)
                            }
                        }
                    },
                    onClickLabel = clickLabel,
                    onLongClick =
                        if (allowGuts) {
                            {
                                if (gutsVisible) {
                                    viewModel.closeGuts()
                                } else if (session != null) {
                                    viewModel.showGuts(session)
                                }
                            }
                        } else {
                            null
                        },
                )
            )
            AnimatedContent(
                targetState = gutsVisible,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "AxMediaGuts",
                modifier = Modifier.fillMaxSize(),
            ) { showGuts ->
                if (showGuts && session != null) {
                    MediaGuts(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                        compact = span.columns == 1,
                        surface = surface,
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        if (session != null && artwork != null) {
                            MediaArtwork(artwork = artwork, overlayColor = colors.background)
                        }
                        AnimatedContent(
                            targetState = layout,
                            transitionSpec = {
                                ((fadeIn(tween(180)) +
                                        scaleIn(tween(220), initialScale = 0.96f)) togetherWith
                                        (fadeOut(tween(120)) +
                                            scaleOut(tween(160), targetScale = 1.04f)))
                                    .using(sizeTransform = null)
                            },
                            contentAlignment = Alignment.Center,
                            contentKey = { it },
                            label = "AxMediaLayout",
                            modifier = Modifier.fillMaxSize(),
                        ) { mediaLayout ->
                            when (mediaLayout) {
                                AxMediaLayout.OneRow ->
                                    OneRowMediaContent(
                                        session = session,
                                        title = title,
                                        subtitle = subtitle,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                        maxActions = mediaActionLimit(span.columns),
                                    )
                                AxMediaLayout.Compact ->
                                    CompactMediaContent(
                                        session = session,
                                        title = title,
                                        subtitle = subtitle,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                    )
                                AxMediaLayout.Expanded ->
                                    ExpandedMediaContent(
                                        session = session,
                                        title = title,
                                        subtitle = subtitle,
                                        viewModel = viewModel,
                                        span = span,
                                        colors = colors,
                                        interactive = interactive,
                                    )
                                AxMediaLayout.Lockscreen ->
                                    LockscreenMediaContent(
                                        session = session,
                                        title = title,
                                        subtitle = subtitle,
                                        viewModel = viewModel,
                                        colors = colors,
                                        interactive = interactive,
                                    )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaArtwork(artwork: IconModel?, overlayColor: Color) {
    // Radial mask that keeps QS text legible over the full-bleed art.
    val washAlpha = 0.65f
    Crossfade(targetState = artwork, label = "AxMediaArtwork", modifier = Modifier.fillMaxSize()) {
        currentArtwork ->
        val modifier =
            Modifier.fillMaxSize().drawWithContent {
                drawContent()
                drawRect(
                    brush =
                        Brush.radialGradient(
                            0f to overlayColor.copy(alpha = washAlpha),
                            1f to overlayColor.copy(alpha = (washAlpha + 0.1f).coerceAtMost(0.95f)),
                            center = center,
                            radius = max(size.width, size.height) / 2f,
                        )
                )
            }
        when (currentArtwork) {
            null -> Unit
            is IconModel.Loaded -> {
                val bitmap = remember(currentArtwork) { currentArtwork.asImageBitmap() }
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier,
                )
            }
            is IconModel.Resource ->
                Icon(icon = currentArtwork, tint = Color.Unspecified, modifier = modifier)
        }
    }
}

@Composable
private fun MediaGuts(
    session: MediaSessionModel,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    compact: Boolean,
    surface: AxMediaSurface,
) {
    val message =
        if (session.canBeHidden) {
            stringResource(R.string.controls_media_close_session, session.appName)
        } else {
            stringResource(R.string.controls_media_active_session)
        }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        IconButton(onClick = viewModel::openSettings, modifier = Modifier.align(Alignment.TopEnd)) {
            MaterialIcon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.controls_media_settings_button),
                tint = colors.foreground,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
        ) {
            Text(
                text = message,
                color = colors.foreground,
                style = MaterialTheme.typography.labelMedium,
                maxLines = if (compact) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
            )
            if (compact) {
                if (session.canBeHidden) {
                    Button(onClick = { viewModel.dismissFromSurface(session, surface) }) {
                        Text(stringResource(R.string.controls_media_dismiss_button))
                    }
                }
                OutlinedButton(onClick = viewModel::cancelGuts) {
                    Text(stringResource(android.R.string.cancel))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.canBeHidden) {
                        Button(onClick = { viewModel.dismissFromSurface(session, surface) }) {
                            Text(stringResource(R.string.controls_media_dismiss_button))
                        }
                    }
                    OutlinedButton(onClick = viewModel::cancelGuts) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun OneRowMediaContent(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    maxActions: Int,
) {
    val metadata =
        if (subtitle.isEmpty()) {
            title
        } else {
            stringResource(R.string.ax_qs_media_compact_metadata, title, subtitle)
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontalPadding = mediaHorizontalPadding(maxWidth)
        val actionSize = mediaCompactActionSize(maxWidth, maxHeight)
        val actionLimit = if (maxWidth < 132.dp) 1 else maxActions
        if (maxHeight < 56.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 4.dp),
            ) {
                AnimatedMediaText(
                    text = metadata,
                    color = colors.foreground,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                MediaControls(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    maxActions = actionLimit,
                    actionSize = actionSize,
                )
            }
        } else {
            val dense = maxHeight < 68.dp
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 4.dp),
                modifier =
                    Modifier.fillMaxSize()
                        .padding(
                            horizontal = horizontalPadding,
                            vertical = if (dense) 2.dp else 6.dp,
                        ),
            ) {
                AnimatedMediaText(
                    text = metadata,
                    color = colors.foreground,
                    style =
                        if (dense) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                    textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    MediaControls(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                        interactive = interactive,
                        maxActions = actionLimit,
                        actionSize = actionSize,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMediaContent(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        val horizontalPadding = mediaHorizontalPadding(maxWidth)
        val actionSize = mediaCompactActionSize(maxWidth, maxHeight)
        val compactOutput = actionSize < 32.dp
        val contentHeight = maxHeight.coerceAtMost(CompactMediaMaxHeight)
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .height(contentHeight)
                    .padding(top = 8.dp, bottom = if (compactOutput) 4.dp else 8.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
                val outputMaxWidth = maxWidth * 0.32f
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    ) {
                        MediaAppIcon(
                            session = session,
                            size =
                                if (compactOutput) {
                                    16.dp
                                } else {
                                    dimensionResource(R.dimen.qs_media_app_icon_size)
                                },
                            tint = colors.primary,
                        )
                    }
                    MediaOutputChip(
                        session = session,
                        viewModel = viewModel,
                        colors = colors,
                        interactive = interactive,
                        showLabel = false,
                        compact = compactOutput,
                        modifier = Modifier.widthIn(max = outputMaxWidth),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                    AnimatedMediaText(
                        text = title,
                        color = colors.foreground,
                        style =
                            if (compactOutput) {
                                MaterialTheme.typography.labelMedium
                            } else {
                                MaterialTheme.typography.labelLarge
                            },
                        textAlign = if (session == null) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (subtitle.isNotEmpty() && availableHeight >= 112.dp) {
                        AnimatedMediaText(
                            text = subtitle,
                            color = colors.foreground.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                MediaControls(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    maxActions = mediaActionLimit(AxQsSpan.MediaDefault.columns),
                    actionSize = actionSize,
                    spreadCoreActions = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpandedMediaContent(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    span: AxQsSpan,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    val showOutputText = span.columns > 2
    val outputLabel =
        session?.outputDevice?.name?.takeUnless { it.isBlank() || it == "null" }
            ?: stringResource(R.string.ax_dynamic_bar_media_output)
    val playPauseCornerRadius by
        animateDpAsState(
            targetValue = if (session?.state == MediaSessionState.Playing) 16.dp else 48.dp,
            label = "AxExpandedMediaPlayPauseCornerRadius",
        )
    val playPauseShape = RoundedCornerShape(playPauseCornerRadius)
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val horizontalPadding = mediaHorizontalPadding(maxWidth)
        val bottomHorizontalPadding = if (maxWidth < 240.dp) 4.dp else 8.dp
        val navigationWidth =
            minOf(maxWidth - bottomHorizontalPadding * 2, ExpandedMediaNavigationMaxWidth)
        val actionSize = 48.dp
        val secondaryActionIconSize = 22.dp
        val playPauseIconSize = 24.dp
        val playPauseWidth = 72.dp
        val bottomButtonCapacity =
            ((navigationWidth - ExpandedMediaMinSeekWidth).value / (actionSize + 8.dp).value)
                .toInt()
                .coerceAtLeast(0)
        val coreButtonCount = if (showCoreActions) 2 else 0
        val additionalActionLimit =
            (bottomButtonCapacity - coreButtonCount)
                .coerceAtLeast(0)
                .coerceAtMost(
                    if (showCoreActions) {
                        (mediaActionLimit(span.columns) - 3).coerceAtLeast(0)
                    } else {
                        span.columns
                    }
                )
        val additionalActions = session?.additionalActions.orEmpty().take(additionalActionLimit)
        Column(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth()) {
                MediaAppIcon(
                    session = session,
                    size = 24.dp,
                    tint = colors.primary,
                    modifier =
                        Modifier.align(Alignment.TopStart)
                            .padding(start = horizontalPadding, top = 8.dp),
                )
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    showLabel = showOutputText,
                    label = outputLabel,
                    compact = false,
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .widthIn(max = availableWidth * 0.4f)
                            .padding(top = 8.dp, end = horizontalPadding),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    AnimatedMediaText(
                        text = title,
                        color = colors.foreground,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    if (subtitle.isNotEmpty()) {
                        AnimatedMediaText(
                            text = subtitle,
                            color = colors.foreground.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (showCoreActions) {
                    CoreMediaAction(
                        action = session?.playPauseAction,
                        imageVector = playPauseIcon(session),
                        descriptionRes = playPauseDescription(session),
                        animatedIconRes = R.drawable.ic_media_play_button,
                        animatedIconAtEnd = session?.state == MediaSessionState.Playing,
                        viewModel = viewModel,
                        width = playPauseWidth,
                        height = actionSize,
                        iconSize = playPauseIconSize,
                        tint = colors.onPrimary,
                        background = colors.primary,
                        shape = playPauseShape,
                        interactive = interactive,
                    )
                } else {
                    Spacer(Modifier.size(width = 0.dp, height = actionSize))
                }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = bottomHorizontalPadding, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.width(navigationWidth),
                    ) {
                        if (showCoreActions) {
                            ExpandedNavigationAction(
                                action = session?.leftAction,
                                placeholderDescription = R.string.controls_media_button_prev,
                                viewModel = viewModel,
                                colors = colors,
                                interactive = interactive,
                                size = actionSize,
                                iconSize = secondaryActionIconSize,
                                imageVector = Icons.Filled.SkipPrevious,
                            )
                        }
                        MediaSeekBar(
                            session = session,
                            viewModel = viewModel,
                            colors = colors,
                            dense = true,
                            interactive = interactive,
                            modifier = Modifier.weight(1f),
                        )
                        if (showCoreActions) {
                            ExpandedNavigationAction(
                                action = session?.rightAction,
                                placeholderDescription = R.string.controls_media_button_next,
                                viewModel = viewModel,
                                colors = colors,
                                interactive = interactive,
                                size = actionSize,
                                iconSize = secondaryActionIconSize,
                                imageVector = Icons.Filled.SkipNext,
                            )
                        }
                        additionalActions.forEach { action ->
                            MediaAction(
                                action = action,
                                viewModel = viewModel,
                                width = actionSize,
                                height = actionSize,
                                iconSize = secondaryActionIconSize,
                                tint = colors.foreground,
                                interactive = interactive,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedNavigationAction(
    action: MediaActionModel?,
    @StringRes placeholderDescription: Int,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    size: Dp,
    iconSize: Dp,
    imageVector: ImageVector,
) {
    CoreMediaAction(
        action = action,
        imageVector = imageVector,
        descriptionRes = placeholderDescription,
        viewModel = viewModel,
        width = size,
        height = size,
        iconSize = iconSize,
        tint = colors.foreground,
        interactive = interactive,
    )
}

@Composable
internal fun AnimatedMediaText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Crossfade(
        targetState = text,
        animationSpec = tween(durationMillis = 180),
        label = "AxMediaText",
        modifier = modifier,
    ) { value ->
        Text(
            text = value,
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = 1),
        )
    }
}

@Composable
internal fun MediaAppIcon(
    session: MediaSessionModel?,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (session != null) {
        Icon(icon = session.appIcon, tint = tint, modifier = modifier.size(size).clip(CircleShape))
    } else {
        MaterialIcon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(size),
        )
    }
}

@Composable
internal fun MediaOutputChip(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    showLabel: Boolean,
    compact: Boolean,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val outputDescription = stringResource(R.string.ax_dynamic_bar_media_output)
    val interactionSource = remember { MutableInteractionSource() }
    val chipHeight = if (compact) 24.dp else 32.dp
    val iconSize = if (compact) 14.dp else 16.dp
    ExpandableContainer(
        controller =
            rememberExpandableController(color = { Color.Transparent }, shape = CircleShape),
        modifier = modifier,
        defaultMinSize = false,
        useModifierBasedImplementation = true,
    ) { expandable ->
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier.heightIn(min = chipHeight)
                    .semantics { contentDescription = outputDescription }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = interactive && session != null,
                    ) {
                        session?.let { viewModel.openOutput(it.outputDevice, expandable) }
                    },
        ) {
            // Session (and empty placeholder) chrome uses media/Material colors only —
            // UI Styles apply solely to the empty card body.
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.clip(CircleShape)
                        .background(colors.primary)
                        .indication(interactionSource, ripple())
                        .then(
                            if (showLabel) {
                                Modifier.padding(
                                    horizontal = if (compact) 6.dp else 8.dp,
                                    vertical = if (compact) 3.dp else 4.dp,
                                )
                            } else {
                                Modifier.size(chipHeight)
                            }
                        ),
            ) {
                if (session != null) {
                    Icon(
                        icon = session.outputDevice.icon,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(iconSize),
                    )
                } else {
                    MaterialIcon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = colors.onPrimary,
                        modifier = Modifier.size(iconSize),
                    )
                }
                if (showLabel) {
                    Text(
                        text = label ?: outputDescription,
                        color = colors.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

private fun mediaActionLimit(columns: Int): Int {
    return when {
        columns <= 2 -> 3
        columns == 3 -> 4
        else -> 5
    }
}

private fun mediaHorizontalPadding(width: Dp): Dp {
    return when {
        width < 144.dp -> 6.dp
        width < 240.dp -> 12.dp
        else -> 16.dp
    }
}

private fun mediaCompactActionSize(width: Dp, height: Dp): Dp {
    return when {
        width < 112.dp || height < 56.dp -> 28.dp
        width < 160.dp || height < 144.dp -> 32.dp
        else -> 36.dp
    }
}

/**
 * Frosted glass fill for the lockscreen media card. Uses the same cross-window path as the volume
 * dialog: [ViewRootImpl.createBackgroundBlurDrawable] with a translucent tint and corner radius.
 * Compose colour alone cannot blur what sits behind the window.
 *
 * When blur is unavailable (detached, policy, power save) the open body colour is kept as a plain
 * [ColorDrawable] so the card still tints instead of turning into a black slab.
 */
@Composable
private fun LockscreenGlassBackdrop(
    corner: Dp,
    /**
     * Set when "lockscreen media art" is on. The art is an `ImageView` in **this** window
     * (`CentralSurfacesImpl.attachCustomOverlays`), and cross-window blur only samples what is
     * behind the window — so the card would frost the wallpaper while everything around it shows
     * the art. When art is on we blur our own copy of it instead.
     */
    artwork: IconModel?,
    modifier: Modifier = Modifier,
) {
    val blurEnabled = rememberCrossWindowBlurEnabled()
    Box(modifier.fillMaxSize()) {
        if (artwork != null) {
            InWindowArtBackdrop(artwork)
        } else {
            CrossWindowBlurBackdrop(corner = corner)
        }
        // Tint sits above whatever backdrop we got. Without frost behind it, an open body leaves
        // white text on bare wallpaper, so fall back to the dense one.
        val body =
            if (blurEnabled || artwork != null) MediaChrome.LockscreenGlassBody
            else MediaChrome.LockscreenGlassBodyNoBlur
        Box(Modifier.fillMaxSize().background(body))
    }
}

/** True while the compositor is willing to blur (developer option, power save, GPU support). */
@Composable
private fun rememberCrossWindowBlurEnabled(): Boolean {
    val context = LocalContext.current
    val windowManager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var enabled by remember(windowManager) {
        mutableStateOf(windowManager?.isCrossWindowBlurEnabled ?: false)
    }
    DisposableEffect(windowManager) {
        val listener = Consumer<Boolean> { value -> enabled = value }
        // Called back immediately with the current state on registration.
        windowManager?.addCrossWindowBlurEnabledListener(listener)
        onDispose { windowManager?.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

/** Frost over whatever is behind the window — the volume dialog's path. */
@Composable
private fun CrossWindowBlurBackdrop(corner: Dp) {
    val density = LocalDensity.current
    val cornerPx = with(density) { corner.toPx() }
    val blurPx = with(density) { MediaChrome.LockscreenGlassBlurRadius.toPx().toInt() }
    AndroidView(
        factory = { context ->
            View(context).apply {
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            val root = v.viewRootImpl ?: return
                            v.background =
                                root.createBackgroundBlurDrawable().apply {
                                    // Tint is drawn in Compose above this; blur only here.
                                    setColor(Color.Transparent.toArgb())
                                    setBlurRadius(blurPx)
                                    setCornerRadius(cornerPx)
                                }
                        }

                        override fun onViewDetachedFromWindow(v: View) {
                            v.background = ColorDrawable(Color.Transparent.toArgb())
                        }
                    }
                )
            }
        },
        update = { view ->
            (view.background as? BackgroundBlurDrawable)?.apply {
                setBlurRadius(blurPx)
                setCornerRadius(cornerPx)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Frost over the lockscreen media art. Draws the same art at window size, offset so it lines up
 * with the copy behind the card, then blurs it — the card becomes a pane over the art instead of a
 * window onto the wallpaper.
 */
@Composable
private fun InWindowArtBackdrop(artwork: IconModel) {
    var cardOffset by remember { mutableStateOf(IntOffset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            rootSize = coordinates.findRootCoordinates().size
            val position = coordinates.positionInRoot()
            cardOffset = IntOffset(-position.x.roundToInt(), -position.y.roundToInt())
        }
    ) {
        if (rootSize.width > 0 && artwork is IconModel.Loaded) {
            val bitmap = remember(artwork) { artwork.asImageBitmap() }
            with(LocalDensity.current) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.size(rootSize.width.toDp(), rootSize.height.toDp())
                            .offset { cardOffset }
                            .blur(
                                MediaChrome.LockscreenGlassBlurRadius,
                                BlurredEdgeTreatment.Unbounded,
                            ),
                )
            }
        }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility")
internal fun MediaSeekBar(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    dense: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val height = if (dense) 20.dp else 32.dp
    val squiggleAnimationEnabled = rememberSquiggleAnimationEnabled()
    val currentSession by rememberUpdatedState(session)
    val currentViewModel by rememberUpdatedState(viewModel)
    val progress = session?.let(viewModel::progress) ?: 0f
    val seekDescription =
        session
            ?.let {
                stringResource(
                    R.string.controls_media_seekbar_description,
                    DateUtils.formatElapsedTime((progress * it.durationMs).toLong() / 1000L),
                    DateUtils.formatElapsedTime(it.durationMs / 1000L),
                )
            }
            .orEmpty()
    Column(modifier = modifier) {
        AndroidView(
            factory = { context ->
                var start = Offset.Zero
                var delta = Offset.Zero
                WaveformSeekBar(context).apply {
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    splitTrack = false
                    showThumbWhenDisabled = true
                    setOnTouchListener { view, event ->
                        val position = Offset(event.x, event.y)
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                view.parent?.requestDisallowInterceptTouchEvent(true)
                                start = position
                                delta = Offset.Zero
                            }
                            MotionEvent.ACTION_MOVE -> delta = position - start
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                delta = position - start
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false
                    }
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(
                                seekBar: SeekBar,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                val media = currentSession
                                if (fromUser && media != null && seekBar.max > 0) {
                                    currentViewModel.onScrubChange(
                                        media,
                                        progress.toFloat() / seekBar.max,
                                    )
                                }
                            }

                            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                            override fun onStopTrackingTouch(seekBar: SeekBar) {
                                currentSession?.let { currentViewModel.onScrubFinished(it, delta) }
                            }
                        }
                    )
                }
            },
            update = { seekBar ->
                val duration =
                    session?.durationMs?.coerceIn(1L, Int.MAX_VALUE.toLong())?.toInt() ?: 1
                seekBar.max = duration
                if (!seekBar.isPressed) {
                    seekBar.progress = (progress * duration).toInt().coerceIn(0, duration)
                }
                seekBar.isEnabled = interactive && session?.canBeScrubbed == true
                seekBar.contentDescription = seekDescription
                // Progress uses art accent (Phase 1/2 language); track stays on-glass neutral.
                // The Glass lockscreen style draws its own bar (GlassSeekBar) and never lands here.
                val progressColor = colors.primary.toArgb()
                val trackColor = MediaChrome.ProgressTrack.toArgb()
                seekBar.setMediaColor(progressColor)
                seekBar.thumbTintList = ColorStateList.valueOf(progressColor)
                seekBar.progressTintList = ColorStateList.valueOf(progressColor)
                seekBar.progressBackgroundTintList = ColorStateList.valueOf(trackColor)
                val playing = session?.state == MediaSessionState.Playing && !seekBar.isPressed
                val animate = playing && squiggleAnimationEnabled
                (seekBar.progressDrawable as? SquigglyProgress)?.animate = animate
                seekBar.setWaveformPlaying(animate)
            },
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}

@Composable
private fun MediaControls(
    session: MediaSessionModel?,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
    actionSize: Dp,
    maxActions: Int = 3,
    spreadCoreActions: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val spacing = dimensionResource(R.dimen.qs_media_action_spacing)
    val iconSize = if (actionSize < 32.dp) 18.dp else 20.dp
    val navigationIconSize = minOf(iconSize, MediaNavigationIconSize)
    val showSideActions = maxActions >= 3
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            horizontalArrangement =
                if (spreadCoreActions) {
                    Arrangement.SpaceEvenly
                } else {
                    Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
                },
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            if (session != null) {
                val coreSlots = if (showSideActions) 3 else 1
                val additionalActions =
                    session.additionalActions.take((maxActions - coreSlots).coerceAtLeast(0))
                if (showSideActions) {
                    additionalActions.firstOrNull()?.let { action ->
                        MediaAction(
                            action = action,
                            viewModel = viewModel,
                            width = actionSize,
                            iconSize = iconSize,
                            tint = colors.foreground,
                            interactive = interactive,
                        )
                    }
                }
                if (showSideActions) {
                    CoreMediaAction(
                        action = session.leftAction,
                        imageVector = Icons.Filled.SkipPrevious,
                        descriptionRes = R.string.controls_media_button_prev,
                        viewModel = viewModel,
                        width = actionSize,
                        iconSize = navigationIconSize,
                        tint = colors.foreground,
                        interactive = interactive,
                    )
                }
                CoreMediaAction(
                    action = session.playPauseAction,
                    imageVector = playPauseIcon(session),
                    descriptionRes = playPauseDescription(session),
                    viewModel = viewModel,
                    width = actionSize,
                    iconSize = iconSize,
                    tint = colors.foreground,
                    interactive = interactive,
                )
                if (showSideActions) {
                    CoreMediaAction(
                        action = session.rightAction,
                        imageVector = Icons.Filled.SkipNext,
                        descriptionRes = R.string.controls_media_button_next,
                        viewModel = viewModel,
                        width = actionSize,
                        iconSize = navigationIconSize,
                        tint = colors.foreground,
                        interactive = interactive,
                    )
                }
                if (showSideActions) {
                    additionalActions.drop(1).forEach { action ->
                        MediaAction(
                            action = action,
                            viewModel = viewModel,
                            width = actionSize,
                            iconSize = iconSize,
                            tint = colors.foreground,
                            interactive = interactive,
                        )
                    }
                }
            } else {
                if (showSideActions) {
                    PlaceholderMediaAction(
                        imageVector = Icons.Filled.SkipPrevious,
                        descriptionRes = R.string.controls_media_button_prev,
                        width = actionSize,
                        iconSize = navigationIconSize,
                        tint = colors.foreground,
                    )
                }
                PlaceholderMediaAction(
                    imageVector = Icons.Filled.PlayArrow,
                    descriptionRes = R.string.controls_media_button_play,
                    width = actionSize,
                    iconSize = iconSize,
                    tint = colors.foreground,
                )
                if (showSideActions) {
                    PlaceholderMediaAction(
                        imageVector = Icons.Filled.SkipNext,
                        descriptionRes = R.string.controls_media_button_next,
                        width = actionSize,
                        iconSize = navigationIconSize,
                        tint = colors.foreground,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CoreMediaAction(
    action: MediaActionModel?,
    imageVector: ImageVector,
    @StringRes descriptionRes: Int,
    @DrawableRes animatedIconRes: Int? = null,
    animatedIconAtEnd: Boolean = false,
    viewModel: AxMediaViewModel,
    width: Dp,
    height: Dp = width,
    iconSize: Dp,
    tint: Color,
    background: Color = Color.Transparent,
    shape: Shape = CircleShape,
    interactive: Boolean,
) {
    when (action) {
        is MediaActionModel.Action ->
            MediaAction(
                action = action,
                viewModel = viewModel,
                width = width,
                height = height,
                iconSize = iconSize,
                tint = tint,
                background = background,
                shape = shape,
                interactive = interactive,
                imageVector = imageVector,
                animatedIconRes = animatedIconRes,
                animatedIconAtEnd = animatedIconAtEnd,
                contentDescription = stringResource(descriptionRes),
            )
        MediaActionModel.ReserveSpace -> Spacer(Modifier.size(width = width, height = height))
        MediaActionModel.None,
        null ->
            PlaceholderMediaAction(
                imageVector = imageVector,
                descriptionRes = descriptionRes,
                width = width,
                height = height,
                iconSize = iconSize,
                tint = tint,
                background = background,
                shape = shape,
            )
    }
}

@Composable
private fun PlaceholderMediaAction(
    @DrawableRes iconRes: Int? = null,
    imageVector: ImageVector? = null,
    @StringRes descriptionRes: Int,
    width: Dp,
    height: Dp = width,
    iconSize: Dp = 20.dp,
    tint: Color,
    background: Color = Color.Transparent,
    shape: Shape = CircleShape,
) {
    val description = stringResource(descriptionRes)
    val buttonWidth by
        animateDpAsState(
            targetValue = width,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaPlaceholderWidth",
        )
    val buttonHeight by
        animateDpAsState(
            targetValue = height,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaPlaceholderHeight",
        )
    val buttonBackground by
        animateColorAsState(targetValue = background, label = "AxMediaPlaceholderBackground")
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(width = buttonWidth, height = buttonHeight)
                .clip(shape)
                .background(buttonBackground)
                .semantics {
                    contentDescription = description
                    disabled()
                },
    ) {
        if (imageVector != null) {
            MaterialIcon(
                imageVector = imageVector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        } else if (iconRes != null) {
            Icon(
                icon = IconModel.Resource(iconRes, contentDescription = null),
                tint = tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Immutable
internal data class AxMediaColors(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val foreground: Color,
)

private enum class AxMediaLayout {
    OneRow,
    Compact,
    Expanded,
    /** Full-width keyguard card — glass chrome (Phase 2). */
    Lockscreen,
}

internal val ExpandedMediaMinSeekWidth = 40.dp
private val ExpandedMediaNavigationMaxWidth = 320.dp
private val CompactMediaMaxHeight = 220.dp
private val MediaNavigationIconSize = 16.dp

@Composable
internal fun MediaAction(
    action: MediaActionModel,
    viewModel: AxMediaViewModel,
    width: Dp,
    height: Dp = width,
    iconSize: Dp = 20.dp,
    tint: Color,
    background: Color = Color.Transparent,
    shape: Shape = CircleShape,
    interactive: Boolean,
    imageVector: ImageVector? = null,
    @DrawableRes animatedIconRes: Int? = null,
    animatedIconAtEnd: Boolean = false,
    contentDescription: String? = null,
) {
    val buttonWidth by
        animateDpAsState(
            targetValue = width,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaActionWidth",
        )
    val buttonHeight by
        animateDpAsState(
            targetValue = height,
            animationSpec = tween(durationMillis = 220),
            label = "AxMediaActionHeight",
        )
    val buttonBackground by
        animateColorAsState(targetValue = background, label = "AxMediaActionBackground")
    when (action) {
        is MediaActionModel.Action -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.size(width = buttonWidth, height = buttonHeight)
                        .clip(shape)
                        .background(buttonBackground)
                        .clickable(enabled = interactive && action.onClick != null) {
                            viewModel.runAction(action)
                        },
            ) {
                if (animatedIconRes != null) {
                    val painter =
                        rememberAnimatedVectorPainter(
                            animatedImageVector =
                                AnimatedImageVector.animatedVectorResource(animatedIconRes),
                            atEnd = animatedIconAtEnd,
                        )
                    MaterialIcon(
                        painter = painter,
                        contentDescription = contentDescription,
                        tint = tint,
                        modifier = Modifier.size(iconSize),
                    )
                } else if (imageVector != null) {
                    AnimatedContent(
                        targetState = imageVector,
                        transitionSpec = {
                            (fadeIn(tween(180)) +
                                scaleIn(tween(220), initialScale = 0.72f)) togetherWith
                                (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 1.18f))
                        },
                        contentAlignment = Alignment.Center,
                        label = "AxMediaActionIcon",
                    ) { vector ->
                        MaterialIcon(
                            imageVector = vector,
                            contentDescription = contentDescription,
                            tint = tint,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                } else {
                    Icon(icon = action.icon, tint = tint, modifier = Modifier.size(iconSize))
                }
            }
        }
        MediaActionModel.None -> Unit
        MediaActionModel.ReserveSpace ->
            Spacer(Modifier.size(width = buttonWidth, height = buttonHeight))
    }
}

/** Empty-card-only UI Styles chrome. Session media owns its own palette/chrome. */
@Composable
private fun MediaStyledSurface(
    shape: Shape,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val styleRenderer = rememberQsTileStyleRenderer()
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        QSTileStyleWrapper(
            renderer = styleRenderer,
            shape = shape,
            state = if (active) STATE_ACTIVE else STATE_INACTIVE,
            materialColor = color,
            isSmallTile = true,
            modifier = Modifier.matchParentSize(),
        ) {
            if (color.alpha > 0.01f) {
                Box(Modifier.fillMaxSize().background(color, shape))
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

internal fun playPauseIcon(session: MediaSessionModel?): ImageVector {
    return if (session?.state == MediaSessionState.Playing) {
        Icons.Filled.Pause
    } else {
        Icons.Filled.PlayArrow
    }
}

@StringRes
internal fun playPauseDescription(session: MediaSessionModel?): Int {
    return if (session?.state == MediaSessionState.Playing) {
        R.string.controls_media_button_pause
    } else {
        R.string.controls_media_button_play
    }
}
