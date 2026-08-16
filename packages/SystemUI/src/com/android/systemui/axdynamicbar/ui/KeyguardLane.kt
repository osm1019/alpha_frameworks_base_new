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

package com.android.systemui.axdynamicbar.ui

import androidx.compose.ui.unit.Dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.SpaceMd

/**
 * Inputs for the lockscreen chip lane. Occupancy is resolved in composition once
 * [BoxWithConstraints] knows the usable width.
 */
internal data class KeyguardLaneInputs(
    val temporaryIndication: IslandEvent.KeyguardIndication?,
    val persistentIndication: IslandEvent.KeyguardIndication?,
    val battery: KeyguardBatteryInfo?,
    val events: List<IslandEvent>,
) {
    val hasContent: Boolean
        get() =
            temporaryIndication != null ||
                persistentIndication != null ||
                battery != null ||
                events.isNotEmpty()
}

internal sealed class KeyguardLaneContent {
    data class Indication(val indication: IslandEvent.KeyguardIndication) : KeyguardLaneContent()

    data class Occupants(val items: List<KeyguardLaneOccupant>) : KeyguardLaneContent()

    data object Empty : KeyguardLaneContent()
}

internal sealed class KeyguardLaneOccupant {
    data class Battery(val info: KeyguardBatteryInfo) : KeyguardLaneOccupant()

    data class Event(val event: IslandEvent, val eventIndex: Int) : KeyguardLaneOccupant()
}

private val TemporaryIndicationOrder =
    listOf(
        IslandEvent.KeyguardIndication.IndicationType.ALIGNMENT,
        IslandEvent.KeyguardIndication.IndicationType.BIOMETRIC,
        IslandEvent.KeyguardIndication.IndicationType.TRANSIENT,
    )

private val PersistentIndicationOrder =
    listOf(
        IslandEvent.KeyguardIndication.IndicationType.DISCLOSURE,
        IslandEvent.KeyguardIndication.IndicationType.OWNER_INFO,
        IslandEvent.KeyguardIndication.IndicationType.TRUST,
        IslandEvent.KeyguardIndication.IndicationType.PERSISTENT_UNLOCK,
    )

internal fun pickTemporaryIndication(
    indications: Collection<IslandEvent.KeyguardIndication>,
): IslandEvent.KeyguardIndication? {
    val temps = indications.filter { it.behavior.autoDismissMs != null }
    return TemporaryIndicationOrder.firstNotNullOfOrNull { type ->
        temps.firstOrNull { it.indicationType == type }
    }
}

internal fun pickPersistentIndication(
    indications: Collection<IslandEvent.KeyguardIndication>,
): IslandEvent.KeyguardIndication? {
    val persistents = indications.filter { it.behavior.autoDismissMs == null }
    return PersistentIndicationOrder.firstNotNullOfOrNull { type ->
        persistents.firstOrNull { it.indicationType == type }
    }
}

internal fun batteryForLane(mode: Int, info: KeyguardBatteryInfo): KeyguardBatteryInfo? {
    if (mode <= 0) return null
    if (mode == 1 && !info.isCharging) return null
    return info
}

/**
 * First rule that produces content wins:
 * 1. temporary indication (autoDismissMs != null)
 * 2. battery + events, up to [capacity]
 * 3. persistent indication
 *
 * Nothing here decides how an occupant is drawn. Text belongs to a lane holding exactly one
 * thing; the moment a second arrives, everything is a circle.
 */
internal fun resolveKeyguardLane(
    inputs: KeyguardLaneInputs,
    capacity: Int,
): KeyguardLaneContent {
    inputs.temporaryIndication?.let {
        return KeyguardLaneContent.Indication(it)
    }
    val eventSlots =
        if (inputs.battery == null) capacity else (capacity - 1).coerceAtLeast(0)
    val items = buildList {
        inputs.battery?.let { add(KeyguardLaneOccupant.Battery(it)) }
        inputs.events.take(eventSlots).forEachIndexed { index, event ->
            add(KeyguardLaneOccupant.Event(event, eventIndex = index))
        }
    }
    if (items.isNotEmpty()) return KeyguardLaneContent.Occupants(items)
    inputs.persistentIndication?.let {
        return KeyguardLaneContent.Indication(it)
    }
    return KeyguardLaneContent.Empty
}

internal fun laneCapacity(maxWidth: Dp, chip: Dp, gap: Dp = SpaceMd, cap: Int = 4): Int {
    val w = maxWidth.value
    val c = chip.value
    val g = gap.value
    if (!w.isFinite() || w <= 0f || c <= 0f) return cap
    return ((w + g) / (c + g)).toInt().coerceIn(1, cap)
}

internal fun laneContentKey(content: KeyguardLaneContent): Any =
    when (content) {
        KeyguardLaneContent.Empty -> "empty"
        is KeyguardLaneContent.Indication -> "ind:${content.indication.indicationType}"
        is KeyguardLaneContent.Occupants ->
            content.items.joinToString("|") { item ->
                when (item) {
                    is KeyguardLaneOccupant.Battery -> "bat"
                    is KeyguardLaneOccupant.Event -> item.event.id
                }
            }
    }
