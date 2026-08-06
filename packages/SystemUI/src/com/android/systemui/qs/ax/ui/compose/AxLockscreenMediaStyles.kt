/*
 * Copyright (C) 2026 The AlphaDroid Project
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

import android.text.format.DateUtils
import androidx.annotation.DimenRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.android.systemui.media.ax.ui.compose.MediaChrome
import com.android.systemui.media.remedia.domain.model.MediaSessionModel
import com.android.systemui.media.remedia.shared.model.MediaCardActionButtonLayout
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import com.android.systemui.qs.ax.ui.model.AxLockscreenMediaStyle
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.res.R

/**
 * Styles whose layout has landed. The rest fall back to [AxLockscreenMediaStyle.GLASS] — layout and
 * card height alike — so the tree stays shippable while the remaining styles are built.
 */
private val ImplementedStyles = setOf(AxLockscreenMediaStyle.GLASS)

/** The style actually rendered for [this] selection. */
internal val AxLockscreenMediaStyle.effective: AxLockscreenMediaStyle
    get() = if (this in ImplementedStyles) this else AxLockscreenMediaStyle.DEFAULT

/** Card height the keyguard host reserves for [this] style. */
@DimenRes
internal fun AxLockscreenMediaStyle.heightRes(): Int =
    when (this) {
        AxLockscreenMediaStyle.GLASS -> R.dimen.ax_lockscreen_media_height_glass
        AxLockscreenMediaStyle.MINIMAL -> R.dimen.ax_lockscreen_media_height_minimal
        AxLockscreenMediaStyle.WAVEFORM -> R.dimen.ax_lockscreen_media_height_waveform
    }

/** Picks the lockscreen card layout the user asked for. */
@Composable
internal fun LockscreenMediaContent(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    when (viewModel.lockscreenMediaStyle.effective) {
        AxLockscreenMediaStyle.GLASS,
        // Land in M2 / M3; until then every selection renders Glass (see [ImplementedStyles]).
        AxLockscreenMediaStyle.MINIMAL,
        AxLockscreenMediaStyle.WAVEFORM ->
            GlassLockscreenMedia(
                session = session,
                title = title,
                subtitle = subtitle,
                viewModel = viewModel,
                colors = colors,
                interactive = interactive,
            )
    }
}

/**
 * Lockscreen card: app icon + output chip above, title with the accent play control, and a
 * transport row where the seek bar sits between its elapsed / total labels and the extra session
 * actions. Element layout follows the Axion 2.8 card; chrome stays ours ([MediaChrome] glass,
 * accent play, tonal skips), and the art is full-bleed under the glass wash rather than a
 * thumbnail.
 */
@Composable
private fun GlassLockscreenMedia(
    session: MediaSessionModel?,
    title: String,
    subtitle: String,
    viewModel: AxMediaViewModel,
    colors: AxMediaColors,
    interactive: Boolean,
) {
    val playPauseCornerRadius by
        animateDpAsState(
            targetValue = if (session?.state == MediaSessionState.Playing) 16.dp else 28.dp,
            label = "AxLockscreenMediaPlayPauseCornerRadius",
        )
    val playPauseShape = RoundedCornerShape(playPauseCornerRadius)
    val showCoreActions =
        session?.actionButtonLayout != MediaCardActionButtonLayout.SecondaryActionsOnly
    val skipBg = MediaChrome.skipBackground(colors.primary)
    val outputLabel =
        session?.outputDevice?.name?.takeUnless { it.isBlank() || it == "null" }
            ?: stringResource(R.string.ax_dynamic_bar_media_output)
    val progress = session?.let(viewModel::progress) ?: 0f
    val durationMs = session?.durationMs ?: 0L
    val hasDuration = durationMs > 0L
    val elapsedLabel =
        if (hasDuration) DateUtils.formatElapsedTime((progress * durationMs).toLong() / 1000L)
        else ""
    val totalLabel = if (hasDuration) DateUtils.formatElapsedTime(durationMs / 1000L) else ""

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Extra actions only earn their place once the seek bar still has room to be scrubbable.
        val cardWidth = maxWidth
        val transportWidth = cardWidth - 24.dp
        val extraActionCapacity =
            ((transportWidth - ExpandedMediaMinSeekWidth - 88.dp) / 40.dp).toInt().coerceIn(0, 2)
        val additionalActions = session?.additionalActions.orEmpty().take(extraActionCapacity)

        // Height budget: qs_media_session_height_expanded = 184dp.
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(Modifier.fillMaxWidth()) {
                MediaAppIcon(
                    session = session,
                    size = 24.dp,
                    tint = colors.primary,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                )
                MediaOutputChip(
                    session = session,
                    viewModel = viewModel,
                    colors = colors,
                    interactive = interactive,
                    showLabel = true,
                    label = outputLabel,
                    compact = false,
                    modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = cardWidth * 0.5f),
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                // Pinned so the row cannot grow with the font scale: the title and subtitle are
                // single-line, but two stacked lines can still outgrow the play control at 2x and
                // push the transport past the fixed card height.
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
                    AnimatedMediaText(
                        text = title,
                        color = colors.foreground,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    if (subtitle.isNotEmpty()) {
                        AnimatedMediaText(
                            text = subtitle,
                            color = MediaChrome.OnGlassSecondary,
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
                        width = 72.dp,
                        height = 48.dp,
                        iconSize = 26.dp,
                        tint = colors.onPrimary,
                        background = colors.primary,
                        shape = playPauseShape,
                        interactive = interactive,
                    )
                }
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showCoreActions) {
                        CoreMediaAction(
                            action = session?.leftAction,
                            imageVector = Icons.Filled.SkipPrevious,
                            descriptionRes = R.string.controls_media_button_prev,
                            viewModel = viewModel,
                            width = 40.dp,
                            height = 40.dp,
                            iconSize = 22.dp,
                            tint = MediaChrome.OnGlass,
                            background = skipBg,
                            shape = CircleShape,
                            interactive = interactive,
                        )
                    }
                    if (hasDuration) {
                        Text(
                            text = elapsedLabel,
                            color = MediaChrome.OnGlassSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
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
                    if (hasDuration) {
                        Text(
                            text = totalLabel,
                            color = MediaChrome.OnGlassHint,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    if (showCoreActions) {
                        CoreMediaAction(
                            action = session?.rightAction,
                            imageVector = Icons.Filled.SkipNext,
                            descriptionRes = R.string.controls_media_button_next,
                            viewModel = viewModel,
                            width = 40.dp,
                            height = 40.dp,
                            iconSize = 22.dp,
                            tint = MediaChrome.OnGlass,
                            background = skipBg,
                            shape = CircleShape,
                            interactive = interactive,
                        )
                    }
                    additionalActions.forEach { action ->
                        MediaAction(
                            action = action,
                            viewModel = viewModel,
                            width = 36.dp,
                            height = 36.dp,
                            iconSize = 20.dp,
                            tint = MediaChrome.OnGlassSecondary,
                            interactive = interactive,
                        )
                    }
                }
            }
        }
    }
}
