/*
 * SPDX-FileCopyrightText: 2025 AlphaDroid
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.alpha.style.brightness

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.alpha.style.brightness.renderers.BrightnessSliderStyleRenderer
import com.android.systemui.alpha.style.brightness.renderers.SegmentCornerRadii
import kotlin.math.min

/**
 * Wrapper that applies custom style effects to brightness slider components.
 *
 * Draw sequence:
 * 1. AOSP draws backgrounds (via drawContent)
 * 2. Renderer draws effect overlays (after drawContent)
 * 3. Icons drawn outside wrapper (on top of everything)
 *
 * @param renderer Style renderer, null for AOSP default
 * @param shape Component shape (respects external shape configuration)
 * @param segmentMode True for track (two segments), false for button
 * @param isActive For button: auto-brightness state. For track: unused
 * @param activeFraction For track: brightness level 0.0-1.0. For button: unused
 * @param trackCornerDp Outer corners of track segments
 * @param trackInsideCornerDp Inner corners of track segments (at gap)
 * @param thumbGapDp Gap width for thumb (AOSP uses 6dp, custom styles may use 0dp)
 * @param materialColors Material You colors for backgrounds
 * @param modifier Modifier chain
 * @param content Component content (backgrounds only, icons outside)
 */
@Composable
fun BrightnessSliderStyleWrapper(
    renderer: BrightnessSliderStyleRenderer?,
    shape: Shape,
    segmentMode: Boolean,
    isActive: Boolean,
    activeFraction: Float = 0f,
    trackCornerDp: Dp = 0.dp,
    trackInsideCornerDp: Dp = 0.dp,
    thumbGapDp: Dp = 0.dp,
    materialColors: BrightnessMaterialColors,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (renderer == null) {
        Box(modifier = modifier.clip(shape), content = content)
        return
    }

    val density = LocalDensity.current

    val styledModifier = modifier
        .clip(shape)
        .drawWithContent {
            drawContent()

            if (segmentMode) {
                renderTrackSegments(
                    renderer = renderer,
                    shape = shape,
                    activeFraction = activeFraction,
                    trackCornerDp = trackCornerDp,
                    trackInsideCornerDp = trackInsideCornerDp,
                    thumbGapDp = thumbGapDp,
                    materialColors = materialColors,
                    density = density
                )
            } else {
                renderButton(
                    renderer = renderer,
                    shape = shape,
                    isActive = isActive,
                    materialColors = materialColors,
                    density = density
                )
            }
        }

    Box(modifier = styledModifier, content = content)
}

/**
 * Render effect overlays for track segments (Parts 2 and 4).
 *
 * Inner corner policy is decided by caller:
 * - AOSP / system_default: trackInsideCornerDp = 2.dp
 * - Custom styles: trackInsideCornerDp = 0.dp
 */
private fun DrawScope.renderTrackSegments(
    renderer: BrightnessSliderStyleRenderer,
    shape: Shape,
    activeFraction: Float,
    trackCornerDp: Dp,
    trackInsideCornerDp: Dp,
    thumbGapDp: Dp,
    materialColors: BrightnessMaterialColors,
    density: Density
) {
    val fraction = activeFraction.coerceIn(0f, 1f)
    val trackHeight = size.height

    // AOSP logical thumb width
    val logicalThumbWidthPx = with(density) { 4.dp.toPx() }
    val thumbGapPx = with(density) { thumbGapDp.toPx() }
    val trackCornerPx = with(density) { trackCornerDp.toPx() }
    val trackInsideCornerPx = with(density) { trackInsideCornerDp.toPx() }

    val thumbCenterX = size.width * fraction
    val thumbHalf = logicalThumbWidthPx / 2f

    val activeTrackStart = 0f
    val activeTrackEnd = (thumbCenterX - thumbHalf - thumbGapPx).coerceAtLeast(0f)

    val inactiveTrackStart = (thumbCenterX + thumbHalf + thumbGapPx).coerceAtMost(size.width)
    val inactiveTrackEnd = size.width

    // PART 2: Active segment overlay
    if (activeTrackEnd > activeTrackStart) {
        val activeSegmentBounds = Rect(
            left = activeTrackStart,
            top = 0f,
            right = activeTrackEnd,
            bottom = trackHeight
        )

        val activeCornerRadii = SegmentCornerRadii(
            topLeft = trackCornerPx,
            topRight = trackInsideCornerPx,
            bottomLeft = trackCornerPx,
            bottomRight = trackInsideCornerPx
        )

        with(renderer) {
            renderActiveSegmentOverlay(
                segmentBounds = activeSegmentBounds,
                shape = shape,
                cornerRadii = activeCornerRadii,
                materialColor = materialColors.activeSegment,
                density = density
            )
        }
    }

    // PART 4: Inactive segment overlay
    if (inactiveTrackStart < inactiveTrackEnd) {
        val inactiveSegmentBounds = Rect(
            left = inactiveTrackStart,
            top = 0f,
            right = inactiveTrackEnd,
            bottom = trackHeight
        )

        val inactiveCornerRadii = SegmentCornerRadii(
            topLeft = trackInsideCornerPx,
            topRight = trackCornerPx,
            bottomLeft = trackInsideCornerPx,
            bottomRight = trackCornerPx
        )

        with(renderer) {
            renderInactiveSegmentOverlay(
                segmentBounds = inactiveSegmentBounds,
                shape = shape,
                cornerRadii = inactiveCornerRadii,
                materialColor = materialColors.inactiveSegment,
                density = density
            )
        }
    }
}

/**
 * Render effect overlay for button (auto-brightness chip).
 */
private fun DrawScope.renderButton(
    renderer: BrightnessSliderStyleRenderer,
    shape: Shape,
    isActive: Boolean,
    materialColors: BrightnessMaterialColors,
    density: Density
) {
    val buttonBounds = Rect(
        left = 0f,
        top = 0f,
        right = size.width,
        bottom = size.height
    )

    val cornerRadius = when (shape) {
        CircleShape -> min(size.width, size.height) / 2f
        // Resolved against the real size: a proportional corner only knows its radius from it.
        is RoundedCornerShape -> with(density) { shape.topStart.toPx(size, this) }
        else -> 0f
    }

    val materialColor = if (isActive) {
        materialColors.activeButton
    } else {
        materialColors.inactiveButton
    }

    with(renderer) {
        renderButtonOverlay(
            buttonBounds = buttonBounds,
            shape = shape,
            cornerRadius = cornerRadius,
            materialColor = materialColor,
            isActive = isActive,
            density = density
        )
    }
}

/**
 * Material You colors for brightness slider components.
 */
data class BrightnessMaterialColors(
    val activeSegment: Color,
    val inactiveSegment: Color,
    val activeButton: Color,
    val inactiveButton: Color
)