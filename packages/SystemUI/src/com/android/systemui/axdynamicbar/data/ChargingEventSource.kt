/*
 * Copyright (C) 2026 AlphaDroid
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

package com.android.systemui.axdynamicbar.data

import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.battery.domain.interactor.BatteryInteractor
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.broadcast.BroadcastDispatcher
import java.util.Locale

/**
 * Sole owner of the charging data model for the Dynamic Bar.
 *
 * Observes battery state via [BatteryInteractor] and [BatteryController] and emits
 * [IslandEvent.Charging] events.
 */
@SysUISingleton
class ChargingEventSource @Inject constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val batteryInteractor: BatteryInteractor,
    private val batteryController: BatteryController,
    private val context: Context,
    private val broadcastDispatcher: BroadcastDispatcher,
) {
    private val _chargingEvent = MutableStateFlow<IslandEvent.Charging?>(null)
    val chargingEvent: StateFlow<IslandEvent.Charging?> = _chargingEvent.asStateFlow()

    /**
     * True only while the charger is actually pushing current into the battery.
     *
     * Distinct from [BatteryInteractor.isCharging], which is really "plugged in and not an
     * incompatible charger" — it stays true when the charger is attached but charging is
     * disabled (bypass charging, battery defender, thermal cut-off). Unlike [chargingEvent]
     * this is not cleared by a user dismissal, so it stays a truthful hardware signal.
     */
    private val _isActuallyCharging = MutableStateFlow(false)
    val isActuallyCharging: StateFlow<Boolean> = _isActuallyCharging.asStateFlow()

    /** Callback fired when charging starts (mirrors SystemIslandManager.onChargingStarted). */
    var onChargingStarted: ((IslandEvent.Charging) -> Unit)? = null

    @Volatile var chargingDismissed = false

    @Volatile private var wasCharging = false
    @Volatile private var chargingListening = false
    private var batteryJob: Job? = null
    private var estimateRetryJob: Job? = null

    private val hasDashCharger = try {
        context.resources.getBoolean(com.android.internal.R.bool.config_hasDashCharger)
    } catch (_: Exception) { false }
    private val hasWarpCharger = try {
        context.resources.getBoolean(com.android.internal.R.bool.config_hasWarpCharger)
    } catch (_: Exception) { false }
    private val hasVoocCharger = try {
        context.resources.getBoolean(com.android.internal.R.bool.config_hasVoocCharger)
    } catch (_: Exception) { false }

    fun startListening() {
        if (chargingListening) return
        chargingListening = true
        wasCharging = false
        batteryJob?.cancel()
        batteryJob = applicationScope.launch(backgroundDispatcher) {
            val batteryStatsFlow = broadcastDispatcher.broadcastFlow(
                filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) { intent, _ ->
                val bs = BatteryStatus(intent)
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                // Adapter-side values from BatteryService: µA / µV. During VOOC the
                // service injects Vbus x |Ibat| (Ibat is mA on Oplus, already scaled).
                var chargingCurrentUa = bs.maxChargingCurrent.toFloat()
                val chargingVoltageUv = bs.maxChargingVoltage.toFloat()
                val oemRatedWatts = intent.getIntExtra("oem_charger_watts", 0)

                if (chargingCurrentUa <= 0) {
                    val bm = context.getSystemService(BatteryManager::class.java)
                    if (bm != null) {
                        val currentNow = bm.getIntProperty(
                            BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                        if (currentNow != 0) {
                            // ABI says µA, but Oplus battery/current_now is mA.
                            // Scale only on VOOC devices where that violation is known.
                            chargingCurrentUa = if (hasVoocCharger) {
                                Math.abs(currentNow).toFloat() * 1000f
                            } else {
                                Math.abs(currentNow).toFloat()
                            }
                        }
                    }
                }

                // Compute A / V / W directly from µ-units — a single divider cannot
                // correctly scale both linear current and quadratic wattage at once.
                val amps = chargingCurrentUa / 1_000_000f
                val volts = chargingVoltageUv / 1_000_000f
                val watts = amps * volts

                val power = if (watts > 0f) {
                    String.format(Locale.US, "%.1fW", watts)
                } else null

                val current = if (chargingCurrentUa > 0) {
                    if (amps >= 1f) {
                        String.format(Locale.US, "%.1fA", amps)
                    } else {
                        String.format(Locale.US, "%.0fmA", amps * 1000f)
                    }
                } else null

                val voltage = if (chargingVoltageUv > 0) {
                    String.format(Locale.US, "%.1fV", volts)
                } else null

                val tempStr = if (temp > 0) {
                    String.format(Locale.US, "%.1f°C", temp / 10f)
                } else null

                val chargeType = when {
                    bs.chargingStatus == BatteryManager.BATTERY_STATUS_FULL ->
                        context.getString(R.string.ax_dynamic_bar_fully_charged)
                    bs.getChargingSpeed(context) == BatteryStatus.CHARGING_OEM -> when {
                        hasVoocCharger -> if (oemRatedWatts > 0) {
                            "${oemRatedWatts}W SuperVOOC Charging"
                        } else {
                            "VOOC Charging"
                        }
                        hasWarpCharger -> "Warp Charging"
                        hasDashCharger -> "Dash Charging"
                        else -> context.getString(R.string.ax_dynamic_bar_charging_rapidly)
                    }
                    bs.getChargingSpeed(context) == BatteryStatus.CHARGING_FAST ->
                        context.getString(R.string.ax_dynamic_bar_charging_rapidly)
                    bs.getChargingSpeed(context) == BatteryStatus.CHARGING_SLOWLY ->
                        context.getString(R.string.ax_dynamic_bar_charging_slowly)
                    else -> context.getString(R.string.ax_dynamic_bar_charging)
                }

                // The health HAL's own verdict on whether the battery is taking charge.
                // BatteryInteractor.isCharging is really "plugged in", which stays true while
                // bypass charging holds the charge FET open — the chip would then latch on a
                // charging session that never progresses (frozen level, frozen ETA). This is
                // the same discriminator KeyguardIndicationController gates the lock screen
                // battery indication on, so both surfaces agree.
                val isChargingOrFull =
                    bs.status == BatteryManager.BATTERY_STATUS_CHARGING || bs.isCharged()

                HardwareStats(power, current, voltage, tempStr, chargeType, isChargingOrFull)
            }.onStart { emit(HardwareStats(null, null, null, null, null, false)) }

            combine(
                batteryInteractor.isCharging,
                batteryInteractor.level,
                batteryInteractor.powerSave,
                batteryInteractor.batteryTimeRemainingEstimate,
                batteryStatsFlow,
            ) { pluggedIn, level, isPowerSave, timeEst, stats ->
                // Keep the interactor's incompatible-charger veto, add the HAL charging verdict.
                ChargingSnapshot(
                    pluggedIn && stats.isChargingOrFull, level, isPowerSave, timeEst, stats)
            }
            .distinctUntilChanged()
            .collect { snap ->
                _isActuallyCharging.value = snap.isCharging
                val wasChargingBefore = wasCharging
                wasCharging = snap.isCharging

                if (snap.isCharging && !wasChargingBefore && snap.level != null) {
                    // Charging started
                    chargingDismissed = false
                    // Try to compute time remaining if system doesn't provide it
                    val computedTime = snap.timeEst ?: run {
                        val rawCurrent = getCurrentInMa(snap.stats.current)
                        if (rawCurrent > 0f) {
                            val capacity = getBatteryCapacity()
                            computeTimeToFull(snap.level, rawCurrent, capacity)
                        } else null
                    }
                    val event = IslandEvent.Charging(
                        level = snap.level,
                        isWireless = batteryController.isWirelessCharging,
                        isPowerSave = snap.isPowerSave,
                        timeRemaining = computedTime,
                        power = snap.stats.power,
                        current = snap.stats.current,
                        voltage = snap.stats.voltage,
                        temp = snap.stats.temp,
                        chargeType = snap.stats.chargeType,
                    )
                    _chargingEvent.value = event
                    onChargingStarted?.invoke(event)

                    // If we don't have a time estimate yet, retry once every minute
                    // until the system provides one. Skip retry if battery is already full.
                    if (event.timeRemaining == null && snap.level < 100) {
                        startEstimateRetry()
                    }

                } else if (snap.isCharging && wasChargingBefore && !chargingDismissed) {
                    // Level or metadata update while charging
                    val newLevel = snap.level ?: _chargingEvent.value?.level ?: 0
                    val oldLevel = _chargingEvent.value?.level ?: 0
                    val existingTime = _chargingEvent.value?.timeRemaining

                    // Prefer system estimate if it's provided and new.
                    // Otherwise, if level changed, re-compute our internal estimate.
                    val updateTime = if (snap.timeEst != null && snap.timeEst != _chargingEvent.value?.timeRemaining) {
                        snap.timeEst
                    } else if (newLevel != oldLevel || existingTime == null) {
                        val rawCurrent = getCurrentInMa(snap.stats.current)
                        if (rawCurrent > 0f) {
                            val capacity = getBatteryCapacity()
                            computeTimeToFull(newLevel, rawCurrent, capacity)
                        } else existingTime
                    } else {
                        existingTime
                    }

                    _chargingEvent.value = _chargingEvent.value?.copy(
                        level = newLevel,
                        isPowerSave = snap.isPowerSave,
                        timeRemaining = updateTime,
                        power = snap.stats.power,
                        current = snap.stats.current,
                        voltage = snap.stats.voltage,
                        temp = snap.stats.temp,
                        chargeType = snap.stats.chargeType,
                    )

                } else if (!snap.isCharging && wasChargingBefore) {
                    // Charging stopped
                    chargingDismissed = false
                    estimateRetryJob?.cancel()
                    estimateRetryJob = null
                    _chargingEvent.value = null
                }
            }
        }
    }

    /**
     * Retries fetching the system time estimate every minute if it was missing on connect.
     * Stops once an estimate is found or charging stops.
     */
    private fun startEstimateRetry() {
        estimateRetryJob?.cancel()
        estimateRetryJob = applicationScope.launch(backgroundDispatcher) {
            while (chargingListening && !chargingDismissed && _chargingEvent.value != null) {
                delay(ESTIMATE_RETRY_INTERVAL_MS)
                batteryController.getEstimatedTimeRemainingString { estimate ->
                    if (estimate != null && _chargingEvent.value != null && !chargingDismissed) {
                        val current = _chargingEvent.value ?: return@getEstimatedTimeRemainingString
                        _chargingEvent.value = current.copy(timeRemaining = estimate)
                        // Once we have an estimate, we can stop the retry loop
                        estimateRetryJob?.cancel()
                    }
                }
            }
        }
    }

    fun stopListening() {
        if (!chargingListening) return
        chargingListening = false
        batteryJob?.cancel()
        batteryJob = null
        estimateRetryJob?.cancel()
        estimateRetryJob = null
        _chargingEvent.value = null
        _isActuallyCharging.value = false
    }

    fun clearCharging() {
        chargingDismissed = true
        estimateRetryJob?.cancel()
        estimateRetryJob = null
        _chargingEvent.value = null
    }

    private data class HardwareStats(
        val power: String?,
        val current: String?,
        val voltage: String?,
        val temp: String?,
        val chargeType: String?,
        /** Health HAL says the battery is taking charge — see the mapper for why this matters. */
        val isChargingOrFull: Boolean,
    )

    private data class ChargingSnapshot(
        val isCharging: Boolean,
        val level: Int?,
        val isPowerSave: Boolean,
        val timeEst: String?,
        val stats: HardwareStats,
    )

    /**
     * Computes an estimated time to full charge based on current charging rate.
     * Used as a fallback when the system doesn't provide a time estimate.
     *
     * @param level Current battery level (0-100)
     * @param current Charging current in mA (must be > 0)
     * @param capacity Battery capacity in mAh (must be > 0)
     * @return Formatted time string like "2h 30m until full" or null if computation isn't possible
     */
    private fun computeTimeToFull(level: Int, current: Float, capacity: Int): String? {
        if (level >= 100 || current <= 0f || capacity <= 0) return null
        
        // Charging is not linear - it slows down significantly above 80%
        // We'll use a simplified model: fast charge up to 80%, then slow charge
        val fastChargePercent = 80
        val fastChargeEfficiency = 0.85f // Account for heat loss, etc.
        val slowChargeEfficiency = 0.5f // Much slower above 80%
        
        val remainingFast = (fastChargePercent - level).coerceAtLeast(0)
        // Slow phase should only cover remaining capacity above 80%.
        val remainingSlow = (100 - maxOf(level, fastChargePercent)).coerceAtLeast(0)
        
        // Current in mA, capacity in mAh -> time in hours
        val fastChargeTimeHours = (remainingFast.toFloat() / 100f * capacity * fastChargeEfficiency) / current
        val slowChargeTimeHours = (remainingSlow.toFloat() / 100f * capacity * slowChargeEfficiency) / current
        
        val totalMinutes = ((fastChargeTimeHours + slowChargeTimeHours) * 60).toInt()
        
        if (totalMinutes <= 0) return null
        if (totalMinutes > 24 * 60) return null // Don't show if > 24 hours
        
        return buildString {
            val hours = totalMinutes / 60
            val mins = totalMinutes % 60
            if (hours > 0) {
                append("$hours h ")
            }
            if (mins > 0 || hours == 0) {
                append("$mins min")
            }
            append(" until full")
        }
    }

    /**
     * Tries to get battery capacity in mAh from system resources.
     * Falls back to 4000 mAh if not available.
     */
    private fun getBatteryCapacity(): Int {
        return try {
            val resId = context.resources.getIdentifier(
                "config_nominalBatteryCapacity", "integer", "android"
            )
            if (resId != 0) {
                val cap = context.resources.getInteger(resId)
                if (cap > 0) cap else 4000
            } else 4000
        } catch (_: Exception) {
            4000
        }
    }

    /**
     * Parses the current string (e.g., "1.5A" or "1500mA") back to a numeric value in mA.
     * Returns 0f if parsing fails or the string is null.
     */
    private fun getCurrentInMa(currentStr: String?): Float {
        if (currentStr.isNullOrEmpty()) return 0f
        return try {
            when {
                currentStr.endsWith("A", ignoreCase = true) && !currentStr.endsWith("mA", ignoreCase = true) -> {
                    currentStr.dropLast(1).toFloatOrNull()?.times(1000f) ?: 0f
                }
                currentStr.endsWith("mA", ignoreCase = true) -> {
                    currentStr.dropLast(2).toFloatOrNull() ?: 0f
                }
                else -> currentStr.toFloatOrNull() ?: 0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    companion object {
        private const val ESTIMATE_RETRY_INTERVAL_MS = 60_000L
    }
}