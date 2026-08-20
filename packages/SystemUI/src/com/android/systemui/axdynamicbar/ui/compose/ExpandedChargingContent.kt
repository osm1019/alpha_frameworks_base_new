/*
 * Copyright 2025-2026 AlphaDroid
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

package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R

@Composable
internal fun ChargingExpanded(event: IslandEvent.Charging, interactor: IslandActions) {
    val accent = if (event.isPowerSave) OrangeAccent else batteryLevelColor(event.level)
    // PrimaryCard is dense glass; OnCard* is light-on-glass so both day and night stay legible.
    val onCard = OnCardText
    val onCardSecondary = OnCardSecondary

    ExpandedCardLayout(
        accentColor = accent,
        iconBackground = false,
        icon = {
            CPRBatteryIcon(level = event.level, outline = onCard)
        },
        title = {
            // Row 1: Charging Type (VOOC, Charging slowly, etc.)
            event.chargeType?.let { chargeType ->
                val displayText = if (event.level == 100) stringResource(R.string.ax_dynamic_bar_fully_charged) else chargeType
                Text(
                    displayText,
                    color = onCard.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                    maxLines = 1,
                )
            }
            // Row 2: Large Battery Percentage
            Text(
                "${event.level}%",
                color = onCard,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-1.0).sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
            )
            // Row 3: Time Remaining message
            event.timeRemaining?.let {
                Text(
                    it,
                    color = onCardSecondary,
                    style = MaterialTheme.typography.labelMedium.copy(
                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                    ),
                )
            }
        },
        trailing = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.End
            ) {
                StatLine(label = "Power:", value = event.power ?: "--")
                StatLine(label = "Current:", value = event.current ?: "--")
                StatLine(label = "Voltage:", value = event.voltage ?: "--")
                StatLine(label = "Temp:", value = event.temp ?: "--")
            }
        },
    )
}

@Composable
private fun CPRBatteryIcon(
    level: Int?,
    outline: Color,
    modifier: Modifier = Modifier,
) {
    val progress = (level ?: 0) / 100f
    val fillColor =
        batteryLevelBand(
            level ?: 0,
            AlphaColors.DbStackCard.chargeRingLow,
            AlphaColors.DbStackCard.chargeRingMid,
            AlphaColors.DbStackCard.chargeRingHigh,
        )

    val transition = rememberInfiniteTransition(label = "charging_pulse")
    val pulseMultiplier by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(ShapeCompact),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 40.dp)) {
            val barW = 18.dp.toPx()
            val barH = 36.dp.toPx()
            val capW = 8.dp.toPx()
            val capH = 2.5f.dp.toPx()

            val barLeft = (size.width - barW) / 2f
            val barTop = (size.height - barH) / 2f + capH / 2f

            // Cap
            drawRoundRect(
                color = outline.copy(alpha = 0.31f),
                topLeft = Offset(barLeft + (barW - capW) / 2f, barTop - capH),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                style = Stroke(width = 1.5f.dp.toPx())
            )
            drawRoundRect(
                color = outline.copy(alpha = 0.08f),
                topLeft = Offset(barLeft + (barW - capW) / 2f, barTop - capH),
                size = Size(capW, capH),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )

            // Body outline
            drawRoundRect(
                color = outline.copy(alpha = 0.31f),
                topLeft = Offset(barLeft, barTop),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                style = Stroke(width = 1.5f.dp.toPx())
            )
            // Body subtle fill
            drawRoundRect(
                color = outline.copy(alpha = 0.08f),
                topLeft = Offset(barLeft, barTop),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
            // Body progress fill
            if (progress > 0) {
                clipRect(
                    left = barLeft,
                    top = barTop + barH - (barH * progress),
                    right = barLeft + barW,
                    bottom = barTop + barH
                ) {
                    drawRoundRect(
                        color = fillColor.copy(alpha = if (progress >= 1f) 1f else pulseMultiplier),
                        topLeft = Offset(barLeft, barTop),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = OnCardSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            color = OnCardText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
        )
    }
}

@Composable
internal fun RowScope.CompactChargingRow(event: IslandEvent.Charging) {
    val accent = if (event.isPowerSave) OrangeAccent else batteryLevelColor(event.level)

    Box(
        modifier = Modifier
            .size(SizeCompactIcon)
            .clip(ShapeCompact)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.BatteryChargingFull,
            null,
            tint = AlphaColors.onAccentColor,
            modifier = Modifier.size(18.dp),
        )
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            if (event.isWireless) stringResource(R.string.ax_dynamic_bar_wireless_charging)
            else stringResource(R.string.ax_dynamic_bar_charging),
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
        )
        val saverLabel = if (event.isPowerSave) stringResource(R.string.ax_dynamic_bar_saver) else null
        Text(
            buildString {
                append("${event.level ?: 0}%")
                saverLabel?.let { append(" · $it") }
            },
            color = accent,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
