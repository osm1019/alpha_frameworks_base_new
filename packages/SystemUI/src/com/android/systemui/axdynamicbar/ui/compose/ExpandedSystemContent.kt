package com.android.systemui.axdynamicbar.ui.compose

import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R
import com.android.systemui.alpha.theme.AlphaColors



@Composable
internal fun BluetoothExpanded(event: IslandEvent.Bluetooth, interactor: IslandActions) {
    ExpandedCardLayout(
        accentColor = BlueAccent,
        icon = { glyph ->
            event.deviceIcon?.let {
                Image(
                    bitmap = it.toScaledBitmap(30.dp),
                    contentDescription = event.deviceTypeLabel.ifEmpty {
                        stringResource(R.string.ax_dynamic_bar_bluetooth_device)
                    },
                    modifier = Modifier.size(30.dp),
                )
            } ?: Icon(Icons.Filled.Bluetooth, null, tint = glyph, modifier = Modifier.size(28.dp))
        },
        title = {
            Text(event.deviceName, color = OnCardText, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
            ) {
                StatusChip(
                    event.deviceTypeLabel.ifEmpty { stringResource(R.string.ax_dynamic_bar_connected) },
                    BlueAccent,
                )
                if (event.batteryLevel >= 0) {
                    val batteryColor = if (event.batteryLevel > 20) GreenAccent else RedAccent
                    StatusChip("${event.batteryLevel}%", batteryColor)
                }
            }
        },
        trailing = { PulsingDot(color = BlueAccent, size = SpaceMd) },
        actions = {
            ActionChip(
                label = stringResource(R.string.ax_dynamic_bar_disconnect),
                icon = Icons.Filled.BluetoothDisabled,
                color = OnDestructiveText,
                bg = DestructiveBg,
                modifier = Modifier.fillMaxWidth(),
                onClick = { interactor.disconnectBluetooth(event.address) },
            )
        },
    )
}

@Composable
internal fun HotspotExpanded(event: IslandEvent.Hotspot) {
    ExpandedCardLayout(
        accentColor = OrangeAccent,
        icon = { glyph -> Icon(Icons.Filled.Wifi, null, tint = glyph, modifier = Modifier.size(SizeIconMd)) },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_hotspot_active), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            StatusChip(
                when (event.numDevices) {
                    0 -> stringResource(R.string.ax_dynamic_bar_no_devices)
                    1 -> stringResource(R.string.ax_dynamic_bar_one_device_connected)
                    else -> stringResource(R.string.ax_dynamic_bar_devices_connected, event.numDevices)
                },
                OrangeAccent,
            )
        },
        trailing = { PulsingDot(color = OrangeAccent, size = SpaceMd) },
    )
}

@Composable
internal fun RingerModeExpanded(event: IslandEvent.RingerMode, interactor: IslandActions) {
    val style = eventStyleFor(event)
    ExpandedCardLayout(
        accentColor = style.accent,
        icon = { glyph -> style.icon?.let { Icon(it, null, tint = glyph, modifier = Modifier.size(22.dp)) } },
        title = {
            Text(stringResource(R.string.ax_dynamic_bar_sound_mode), color = OnCardText, style = MaterialTheme.typography.titleMedium)
            StatusChip(stringResource(style.labelRes), style.accent)
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                RingerCard(
                    isSelected = event.mode == AudioManager.RINGER_MODE_NORMAL,
                    icon = Icons.Filled.VolumeUp,
                    label = stringResource(R.string.ax_dynamic_bar_ring),
                    accent = BlueAccent,
                    onClick = { interactor.setRingerMode(AudioManager.RINGER_MODE_NORMAL) },
                    modifier = Modifier.weight(1f),
                )
                RingerCard(
                    isSelected = event.mode == AudioManager.RINGER_MODE_VIBRATE,
                    icon = Icons.Filled.Vibration,
                    label = stringResource(R.string.ax_dynamic_bar_vibrate),
                    accent = OrangeAccent,
                    onClick = { interactor.setRingerMode(AudioManager.RINGER_MODE_VIBRATE) },
                    modifier = Modifier.weight(1f),
                )
                RingerCard(
                    isSelected = event.mode == AudioManager.RINGER_MODE_SILENT,
                    icon = Icons.Filled.VolumeOff,
                    label = stringResource(R.string.ax_dynamic_bar_silent),
                    accent = RedAccent,
                    onClick = { interactor.setRingerMode(AudioManager.RINGER_MODE_SILENT) },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RingerCard(
    isSelected: Boolean,
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by
        animateColorAsState(
            targetValue = if (isSelected) accent else OnCardText.copy(alpha = AlphaFaint),
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "ringer_bg",
        )
    val tint by
        animateColorAsState(
            targetValue = if (isSelected) AlphaColors.onAccentColor else SubtleGray,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "ringer_tint",
        )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ShapeLg,
        color = bg,
    ) {
        Column(
            modifier = Modifier.padding(vertical = SpaceSection),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
            Text(label, color = tint, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun VpnExpanded(event: IslandEvent.Vpn) {
    val style = eventStyleFor(event)
    ExpandedCardLayout(
        accentColor = style.accent,
        icon = { glyph -> style.icon?.let { Icon(it, null, tint = glyph, modifier = Modifier.size(28.dp)) } },
        title = {
            Text(
                stringResource(style.labelRes),
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
            )
            StatusChip(
                if (event.isValidated) stringResource(R.string.ax_dynamic_bar_secured)
                else stringResource(R.string.ax_dynamic_bar_connecting),
                if (event.isValidated) GreenAccent else OrangeAccent,
            )
        },
        trailing = {
            PulsingDot(color = if (event.isValidated) GreenAccent else OrangeAccent, size = SpaceMd)
        },
    )
}

@Composable
internal fun RowScope.CompactBluetoothRow(event: IslandEvent.Bluetooth) {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(BlueAccent),
        contentAlignment = Alignment.Center,
    ) {
        event.deviceIcon?.let {
            Image(bitmap = it.toScaledBitmap(SizeIconSm), null, modifier = Modifier.size(SizeIconSm))
        } ?: Icon(Icons.Filled.Bluetooth, null, tint = AlphaColors.onAccentColor, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.deviceName,
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            event.deviceTypeLabel.ifEmpty { stringResource(R.string.ax_dynamic_bar_connected) },
            color = SubtleGray,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    if (event.batteryLevel >= 0) {
        Spacer(Modifier.width(SpaceMd))
        Text(
            "${event.batteryLevel}%",
            color = if (event.batteryLevel > 20) GreenAccent else RedAccent,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun RowScope.CompactHotspotRow(event: IslandEvent.Hotspot) {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(OrangeAccent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Wifi, null, tint = AlphaColors.onAccentColor, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(stringResource(R.string.ax_dynamic_bar_hotspot), color = OnCardText, style = MaterialTheme.typography.bodySmall)
        Text(
            when (event.numDevices) {
                0 -> stringResource(R.string.ax_dynamic_bar_no_devices)
                1 -> stringResource(R.string.ax_dynamic_bar_one_device)
                else -> stringResource(R.string.ax_dynamic_bar_hotspot_devices, event.numDevices)
            },
            color = SubtleGray,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    PulsingDot(color = OrangeAccent, size = 7.dp)
}



@Composable
internal fun RowScope.CompactRingerRow(event: IslandEvent.RingerMode) {
    val style = eventStyleFor(event)
    Box(
        modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(style.accent),
        contentAlignment = Alignment.Center,
    ) {
        style.icon?.let { Icon(it, null, tint = AlphaColors.onAccentColor, modifier = Modifier.size(18.dp)) }
    }
    Spacer(Modifier.width(SpaceLg))
    Text(event.label, color = OnCardText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
}

@Composable
internal fun RowScope.CompactVpnRow() {
    Box(
        modifier =
            Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(IndigoAccent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.VpnKey, null, tint = AlphaColors.onAccentColor, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.width(SpaceLg))
    Text(stringResource(R.string.ax_dynamic_bar_vpn_active), color = OnCardText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    PulsingDot(color = GreenAccent, size = 7.dp)
}
