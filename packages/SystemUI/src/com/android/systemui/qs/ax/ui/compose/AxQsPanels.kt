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

import android.service.quicksettings.Tile.STATE_INACTIVE
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.android.compose.animation.Expandable
import com.android.compose.animation.scene.ContentScope
import com.android.compose.gesture.gesturesDisabled
import com.android.systemui.alpha.style.qs.QSTileStyleWrapper
import com.android.systemui.alpha.style.qs.rememberQsTileStyleRenderer
import com.android.systemui.animation.Expandable as SystemUiExpandable
import com.android.systemui.common.ui.compose.Icon as SystemUiIcon
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.ax.ui.model.AxQsGridItem
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsButtonViewModel
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsForegroundServicesButtonViewModel
import com.android.systemui.qs.panels.ui.viewmodel.toolbar.ToolbarViewModel
import com.android.systemui.res.R
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel

private val DragHandleWidth = 56.dp
private val DragHandleHeight = 4.dp

@Composable
internal fun <T> ContentScope.AxQQS(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    showTileLabels: Boolean,
    rowHeight: Dp,
    spacing: Dp,
    customShapeCells: Boolean,
    onSideSwipe: () -> Unit,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    separateMode: Boolean,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(ShadeHeader.Dimensions.StatusBarHeight),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        AxQsDateHeader(
            toolbarViewModel = toolbarViewModel,
            shadeHeaderViewModel = shadeHeaderViewModel,
            showEdit = false,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
        )
        if (!separateMode) {
            val pagerState = rememberAxQsTilePagerState(tileItems, tileColumns, tileRows)
            if (controlItems.isNotEmpty()) {
                AxQsGrid(
                    items = controlItems,
                    columns = controlColumns,
                    rowHeight = rowHeight,
                    spacing = spacing,
                    maxRows = controlRows,
                    customShapeCells = customShapeCells,
                    modifier = Modifier.fillMaxWidth(),
                    content = controlContent,
                )
            }
            if (tileItems.isNotEmpty()) {
                AxQsTileGrid(
                    items = tileItems,
                    columns = tileColumns,
                    rows = tileRows,
                    spacing = spacing,
                    showLabels = showTileLabels,
                    customShapeCells = customShapeCells,
                    pagerState = pagerState,
                    onSideSwipe = onSideSwipe,
                    modifier = Modifier.fillMaxWidth(),
                    content = tileContent,
                )
            }
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.width(DragHandleWidth)
                        .height(DragHandleHeight)
                        .background(
                            colorResource(R.color.ax_qqs_drag_handle),
                            RoundedCornerShape(2.dp),
                        )
                )
            }
        }
    }
}

@Composable
internal fun <T> ContentScope.AxQS(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    isFullyVisible: () -> Boolean,
    controlItems: List<AxQsGridItem<T>>,
    tileItems: List<AxQsGridItem<T>>,
    controlColumns: Int,
    controlRows: Int,
    tileColumns: Int,
    tileRows: Int,
    showTileLabels: Boolean,
    rowHeight: Dp,
    spacing: Dp,
    onSideSwipe: () -> Unit,
    editButtonProgress: () -> Float,
    scrollState: ScrollState,
    customShapeCells: Boolean,
    showDate: Boolean = true,
    splitShade: Boolean = false,
    modifier: Modifier = Modifier,
    controlContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileContent: @Composable (AxQsGridItem<T>) -> Unit,
    tileLabel: @Composable (AxQsGridItem<T>) -> Unit,
) {
    val pagerState = rememberAxQsTilePagerState(tileItems, tileColumns, tileRows)
    // Portrait can spend a full tile gap around the date row; the split shade is height-bound and
    // pays for it in tile rows, so there it is halved.
    val headerGap = if (splitShade) spacing / 2 else spacing
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(headerGap),
    ) {
        AxQsDateHeader(
            toolbarViewModel = toolbarViewModel,
            shadeHeaderViewModel = shadeHeaderViewModel,
            showEdit = true,
            showDate = showDate,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
        )
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .clipScrollableContainer(Orientation.Vertical)
                    .verticalScroll(scrollState, overscrollEffect = null),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                if (controlItems.isNotEmpty()) {
                    AxQsGrid(
                        items = controlItems,
                        columns = controlColumns,
                        rowHeight = rowHeight,
                        spacing = spacing,
                        maxRows = controlRows,
                        customShapeCells = customShapeCells,
                        modifier = Modifier.fillMaxWidth(),
                        content = controlContent,
                    )
                }
                if (tileItems.isNotEmpty()) {
                    Column {
                        AxQsTileGrid(
                            items = tileItems,
                            columns = tileColumns,
                            rows = tileRows,
                            spacing = spacing,
                            showLabels = showTileLabels,
                            customShapeCells = customShapeCells,
                            pagerState = pagerState,
                            onSideSwipe = onSideSwipe,
                            modifier = Modifier.fillMaxWidth(),
                            content = tileContent,
                            label = tileLabel,
                        )
                        AxQsPagerIndicator(
                            pagerState = pagerState,
                            modifier = Modifier.fillMaxWidth().padding(top = headerGap),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> rememberAxQsTilePagerState(
    items: List<AxQsGridItem<T>>,
    columns: Int,
    rows: Int,
): PagerState = rememberPagerState { axQsTileGridPageCount(items.size, columns, rows) }

@Composable
private fun ContentScope.AxQsDateHeader(
    toolbarViewModel: ToolbarViewModel,
    shadeHeaderViewModel: ShadeHeaderViewModel,
    showEdit: Boolean,
    showDate: Boolean = true,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (showDate) {
                AxQuickSettingsDate(viewModel = shadeHeaderViewModel)
            }
        }
        AxQsHeaderActions(
            viewModel = toolbarViewModel,
            isFullyVisible = isFullyVisible,
            editButtonProgress = editButtonProgress,
            showEdit = showEdit,
            modifier = Modifier.offset(x = 6.dp),
        )
    }
}

@Composable
internal fun ContentScope.AxQsHeaderActions(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
    showEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showEdit) {
            AxEditButton(viewModel, isFullyVisible, editButtonProgress)
        }
        FooterIconButton(
            model = viewModel.settingsButtonViewModel,
            containerColor = AxTileDefaults.backgroundColor(),
            modifier =
                Modifier.sysuiResTag("settings_button_container").minimumInteractiveComponentSize(),
        )
        AxFooterOverflowMenu(viewModel)
    }
}

@Composable
internal fun AxQsPagerIndicator(pagerState: PagerState, modifier: Modifier = Modifier) {
    if (pagerState.pageCount > 1) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PagerDots(
                pagerState = pagerState,
                activeColor = MaterialTheme.colorScheme.primary,
                nonActiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                showArrows = false,
            )
        }
    }
}

@Composable
private fun AxEditButton(
    viewModel: ToolbarViewModel,
    isFullyVisible: () -> Boolean,
    editButtonProgress: () -> Float,
) {
    val editModeButtonViewModel =
        rememberViewModel("AxQsActions") { viewModel.editModeButtonViewModelFactory.create() }
    if (!editModeButtonViewModel.isEditButtonVisible) return
    val editEnabled by remember(editButtonProgress, isFullyVisible) {
        derivedStateOf {
            editButtonProgress() >= EDIT_BUTTON_ENABLE_THRESHOLD && isFullyVisible()
        }
    }
    Box(
        modifier =
            Modifier.graphicsLayer { alpha = editButtonProgress().coerceIn(0f, 1f) }
                .then(
                    if (editEnabled) Modifier
                    else Modifier.gesturesDisabled().clearAndSetSemantics {}
                )
    ) {
        AxQsHeaderCircleButton(
            onClick = { editModeButtonViewModel.onButtonClick() },
            modifier =
                Modifier.sysuiResTag("qs_edit_mode_button").minimumInteractiveComponentSize(),
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.accessibility_quick_settings_edit),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AxFooterOverflowMenu(viewModel: ToolbarViewModel) {
    var expanded by remember { mutableStateOf(false) }
    var expandable by remember { mutableStateOf<SystemUiExpandable?>(null) }
    val context = LocalContext.current
    val menuColor =
        AxTileDefaults.backgroundColor().compositeOver(MaterialTheme.colorScheme.surface)
    Box {
        AxQsHeaderCircleButton(
            onClick = {
                expandable = it
                expanded = true
            },
            modifier = Modifier.minimumInteractiveComponentSize(),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.qs_edit_menu_content_description),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, (-12).dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = menuColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            viewModel.foregroundServicesViewModel?.let { model ->
                AxForegroundServicesMenuItem(model) {
                    val source = expandable ?: return@AxForegroundServicesMenuItem
                    expanded = false
                    model.onClick(context, source)
                }
            }
            AxPowerMenuItem(viewModel.powerButtonViewModel) {
                val source = expandable ?: return@AxPowerMenuItem
                expanded = false
                viewModel.powerButtonViewModel.onClick(source)
            }
        }
    }
}

@Composable
private fun AxForegroundServicesMenuItem(
    model: FooterActionsForegroundServicesButtonViewModel,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(R.string.ax_qs_running_services),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        leadingIcon = {
            SystemUiIcon(
                icon = model.icon,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            Text(
                text = model.foregroundServicesCount.toString(),
                style = MaterialTheme.typography.labelMedium,
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 52.dp),
    )
}

@Composable
private fun AxPowerMenuItem(model: FooterActionsButtonViewModel, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(R.string.accessibility_quick_settings_power_menu),
                style = MaterialTheme.typography.labelLarge,
            )
        },
        leadingIcon = {
            SystemUiIcon(
                icon = model.icon,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 52.dp),
    )
}

@Composable
private fun FooterIconButton(
    model: FooterActionsButtonViewModel?,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    if (model == null) return
    AxQsHeaderCircleButton(
        onClick = model.onClick,
        containerColor = containerColor,
        modifier = modifier,
    ) {
        SystemUiIcon(
            icon = model.icon,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Top-bar circular action (edit / settings / overflow). Keeps [Expandable] morph for launches, but
 * paints inactive UI Styles chrome like the control-zone icon buttons.
 */
@Composable
private fun AxQsHeaderCircleButton(
    onClick: (SystemUiExpandable) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = AxTileDefaults.backgroundColor(),
    content: @Composable () -> Unit,
) {
    val styleRenderer = rememberQsTileStyleRenderer()
    // Transparent Expandable fill so the style wrapper is the sole chrome (same empty-media trick).
    Expandable(
        color = Color.Transparent,
        shape = CircleShape,
        onClick = onClick,
        modifier = modifier,
        useModifierBasedImplementation = true,
    ) {
        Box(modifier = Modifier.size(AxQsHeaderButtonSize), contentAlignment = Alignment.Center) {
            QSTileStyleWrapper(
                renderer = styleRenderer,
                shape = CircleShape,
                state = STATE_INACTIVE,
                materialColor = containerColor,
                isSmallTile = true,
                modifier = Modifier.matchParentSize(),
            ) {
                Box(Modifier.fillMaxSize().background(containerColor, CircleShape))
            }
            content()
        }
    }
}

private val AxQsHeaderButtonSize = 36.dp
private const val EDIT_BUTTON_ENABLE_THRESHOLD = 0.99f
