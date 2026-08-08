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

package com.android.systemui.qs.ax.ui.compose

import android.service.quicksettings.Tile.STATE_ACTIVE
import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.alpha.style.common.LocalAlphaColorScheme
import com.android.systemui.alpha.style.qs.QSTileStyleWrapper
import com.android.systemui.alpha.style.qs.rememberQsTileStyleRenderer
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.common.shared.model.Icon as IconModel
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.qs.ax.shared.model.AxQsControl
import com.android.systemui.qs.ax.shared.model.AxQsSpan
import com.android.systemui.qs.ax.shared.model.AxQsVerticalSliderStyle
import com.android.systemui.qs.ax.ui.viewmodel.AxMediaViewModel
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQSTileCornerFraction
import com.android.systemui.qs.panels.ui.compose.infinitegrid.LocalQSTileShape
import com.android.systemui.qs.panels.ui.compose.infinitegrid.animateQSTileShapeAsState
import com.android.systemui.qs.tiles.ringer.RingerSliderTileContent
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import kotlin.math.abs

@Composable
internal fun AxQsBrightnessButton(
    viewModel: BrightnessSliderViewModel,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val active = viewModel.autoMode
    AxQsButtonControl(
        description = stringResource(R.string.ax_qs_auto_brightness),
        active = active,
        interactive = interactive,
        onClick = viewModel::onIconClick,
        modifier = modifier,
    ) { tint ->
        MaterialIcon(
            painter =
                painterResource(
                    if (active) {
                        R.drawable.ic_qs_brightness_auto_on
                    } else {
                        R.drawable.ic_qs_brightness_auto_off
                    }
                ),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(AxButtonControlIconSize),
        )
    }
}

@Composable
internal fun AxQsVolumeMuteButton(
    viewModel: AudioStreamSliderViewModel,
    interactive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.slider.collectAsStateWithLifecycle()
    val muted = state.value <= state.valueRange.start
    AxQsButtonControl(
        description = stringResource(R.string.ax_qs_volume_mute),
        active = muted,
        interactive = interactive && state.isMutable,
        onClick = { viewModel.toggleMuted(state) },
        modifier = modifier,
    ) { tint ->
        state.icon?.let { icon ->
            Icon(
                icon = icon.unshared(),
                tint = tint,
                modifier = Modifier.size(AxButtonControlIconSize),
            )
        }
    }
}

/**
 * [AudioStreamSliderViewModel] hands every consumer the *same* [android.graphics.drawable.Drawable]
 * instance, and Compose tints a Drawable by mutating its `colorFilter`. The panel slider, the edit
 * screen's slider preview and the mute button all read the same slider state, so they overwrite
 * each other's tint and the last one to draw wins for all of them — most visible on the mute icon,
 * where the button asks for `onPrimary` while the sliders want an on-surface colour.
 *
 * Re-wrap as [IconModel.Resource] whenever the resource id is known: that path renders through
 * `VectorPainter`, which receives the tint per draw call and shares no mutable state.
 */
internal fun IconModel.unshared(): IconModel =
    resId?.let { IconModel.Resource(it, contentDescription) } ?: this

@Composable
private fun AxQsButtonControl(
    description: String,
    active: Boolean,
    interactive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    // Same pipeline as 1x1 QS tiles: UI Styles + user tile shape on a small cell.
    val styleRenderer = rememberQsTileStyleRenderer()
    val userShape = LocalQSTileShape.current
    val scheme = LocalAlphaColorScheme.current
    val tileState = if (active) STATE_ACTIVE else STATE_INACTIVE
    val stateShape by animateQSTileShapeAsState(tileState)
    val background by
        animateColorAsState(
            targetValue = if (active) scheme.accent else AxTileDefaults.backgroundColor(),
            label = "AxQsButtonBackground",
        )
    val foreground by
        animateColorAsState(
            targetValue = if (active) scheme.onAccent else scheme.onNeutral,
            label = "AxQsButtonForeground",
        )
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .fillMaxSize()
                .clickable(enabled = interactive, role = Role.Switch, onClick = onClick)
                .semantics { contentDescription = description },
    ) {
        // Same cell rule as AxLiveTile: a silhouette only claims a 1:1 cell.
        val shape =
            userShape?.takeIf { abs(maxWidth.value - maxHeight.value) < 1f } ?: stateShape
        QSTileStyleWrapper(
            renderer = styleRenderer,
            shape = shape,
            state = tileState,
            materialColor = background,
            isSmallTile = true,
            modifier = Modifier.matchParentSize(),
        ) {
            Box(Modifier.fillMaxSize().background(background, shape))
        }
        icon(foreground)
    }
}

private val AxButtonControlIconSize = 24.dp

@Composable
fun AxQsControlPreview(
    control: AxQsControl,
    span: AxQsSpan,
    maxColumns: Int,
    verticalSliderStyle: AxQsVerticalSliderStyle,
    brightnessViewModel: BrightnessSliderViewModel,
    volumeViewModel: AudioStreamSliderViewModel,
    mediaViewModel: AxMediaViewModel,
    modifier: Modifier = Modifier,
) {
    when (control) {
        AxQsControl.BRIGHTNESS,
        AxQsControl.BRIGHTNESS_HORIZONTAL,
        AxQsControl.VOLUME,
        AxQsControl.VOLUME_HORIZONTAL ->
            AxQsSliderPreview(
                control = control,
                verticalStyle = verticalSliderStyle,
                brightnessViewModel = brightnessViewModel,
                volumeViewModel = volumeViewModel,
                modifier = modifier,
            )
        AxQsControl.AUTO_BRIGHTNESS ->
            Box(modifier, contentAlignment = Alignment.Center) {
                AxQsBrightnessButton(
                    viewModel = brightnessViewModel,
                    interactive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        AxQsControl.VOLUME_MUTE ->
            Box(modifier, contentAlignment = Alignment.Center) {
                AxQsVolumeMuteButton(
                    viewModel = volumeViewModel,
                    interactive = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        AxQsControl.RINGER ->
            RingerSliderTileContent(
                interactable = false,
                shape =
                    axQsControlShape(
                        AxQsControl.RINGER,
                        span,
                        cornerFraction = LocalQSTileCornerFraction.current,
                    ),
                modifier = modifier.fillMaxSize(),
            )
        AxQsControl.MEDIA ->
            AxMediaPanel(
                viewModel = mediaViewModel,
                span = span,
                modifier = modifier.fillMaxSize(),
                showPlaceholder = true,
                interactive = false,
            )
    }
}
