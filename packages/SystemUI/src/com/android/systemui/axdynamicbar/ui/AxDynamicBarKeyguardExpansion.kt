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

package com.android.systemui.axdynamicbar.ui

import com.android.systemui.axdynamicbar.data.ChargingEventSource
import com.android.systemui.axdynamicbar.domain.AxDynamicBarInteractor
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class AxDynamicBarKeyguardExpansion
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val interactor: AxDynamicBarInteractor,
    private val chargingEventSource: ChargingEventSource,
) {
    private val _intent = MutableStateFlow(false)

    private val hasChip =
        interactor.uiState
            .map { it.shouldShow && it.topEvent != null }
            .distinctUntilChanged()

    private val hasChargingSession =
        chargingEventSource.chargingEvent.map { it != null }.distinctUntilChanged()

    /**
     * Context gate shared by both cards: on the keyguard, awake, with nothing else expanded over
     * it. Deliberately excludes [hasChip] — the battery card's occupant is a lane member, not a
     * stack event, so it can legitimately be the only thing on the lockscreen.
     *
     * Every input is a committed state, never a gesture fraction. Closing the card unmounts it,
     * which drops the held event and leaves the keyguard section's host view in card layout params
     * — so a gate that trips on drag progress destroys state a cancelled gesture then cannot give
     * back. The card is a child of the keyguard root, and `KeyguardRootViewBinder` fades that whole
     * view through the transition, so it already animates with the drag without being unmounted;
     * see `LockscreenToPrimaryBouncerTransitionViewModel`, which does the same for the shortcuts
     * either side of the lane. Teardown belongs to [isOnKeyguard] flipping once the swipe commits.
     */
    private val canShowCard: StateFlow<Boolean> =
        combine(
                interactor.isOnKeyguard,
                interactor.isDozing,
                interactor.dozeAmount.map { it > 0f }.distinctUntilChanged(),
                interactor.isPanelExpanded,
                interactor.isBouncerShowing,
            ) { onKg, dozing, dozeAmt, panelExp, bouncer ->
                onKg && !dozing && !dozeAmt && !panelExp && !bouncer
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val canShow: StateFlow<Boolean> =
        combine(canShowCard, hasChip) { context, chip -> context && chip }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, false)

    val isExpanded: StateFlow<Boolean> =
        combine(_intent, canShow) { intent, show -> intent && show }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    private val _batteryIntent = MutableStateFlow(false)

    /**
     * The battery card. `IslandEvent.Charging` is filtered on the keyguard so there are never two
     * charging displays, which leaves the lane's battery occupant with no event to pin — hence a
     * path of its own rather than [expandPinned].
     *
     * [hasChargingSession] is part of the gate, not just of the intent reset: the panel stops being
     * composed the instant charging ends, and its scrim goes with it, so a card left "expanded"
     * here would hold the keyguard's notification stack and clock hidden with nothing left to tap.
     */
    val isBatteryExpanded: StateFlow<Boolean> =
        combine(_batteryIntent, canShowCard, hasChargingSession) { intent, show, charging ->
                intent && show && charging
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    /**
     * Either card is up.
     *
     * What the keyguard section acts on: swapping the host view to expanded layout params and
     * hiding the notification stack / masking the clock are properties of *a card being open*,
     * not of which one. Keep [isExpanded] for anything that renders the event card itself.
     */
    val isAnyExpanded: StateFlow<Boolean> =
        combine(isExpanded, isBatteryExpanded) { event, battery -> event || battery }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Lazily, false)

    private val _collapseSettled = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val collapseSettled: SharedFlow<Unit> = _collapseSettled.asSharedFlow()

    /** Set while a lane tap owns the pin, so the card closing can hand it back. */
    private var pinnedByLane = false

    /**
     * The event this card was opened for. The stack pin is shared with C and still moves when a
     * new event arrives; this card must not follow it or a clipboard copy replaces a timer the
     * user is reading. Looked up live so a media track change still updates the same sheet.
     * A snapshot covers the exit animation after that event has already left the stack.
     */
    private val _heldEventId = MutableStateFlow<String?>(null)
    private val _heldSnapshot = MutableStateFlow<IslandEvent?>(null)

    val heldEvent: StateFlow<IslandEvent?> =
        combine(_heldEventId, _heldSnapshot, interactor.uiState) { id, snap, ui ->
                if (id == null) null
                else ui.events.firstOrNull { it.id == id } ?: snap?.takeIf { it.id == id }
            }
            .distinctUntilChanged()
            .stateIn(applicationScope, SharingStarted.Eagerly, null)

    fun notifyCollapseSettled() {
        releaseLanePin()
        clearHold()
        _collapseSettled.tryEmit(Unit)
    }

    init {
        interactor.isOnKeyguard
            .onEach {
                if (!it) {
                    collapse()
                    releaseLanePin()
                    clearHold()
                }
            }
            .launchIn(applicationScope)

        // An intent outlives its subject. Nothing above clears it when the thing the card was
        // opened for ends, so the next charging session — or the next event — would find the
        // intent still set and reopen a card the user never asked for.
        hasChargingSession.onEach { if (!it) _batteryIntent.value = false }.launchIn(applicationScope)
        hasChip.onEach { if (!it) _intent.value = false }.launchIn(applicationScope)

        combine(_heldEventId, interactor.uiState) { id, ui ->
                id != null && ui.events.none { it.id == id }
            }
            .distinctUntilChanged()
            .onEach { gone -> if (gone) _intent.value = false }
            .launchIn(applicationScope)
    }

    /**
     * Open the card for the lane occupant at [index].
     *
     * Pinning still happens so C/cutout agree on which event won the tap. The card itself
     * renders [heldEvent], not `topEvent`, because a later transient pin must not swap the
     * sheet. [releaseLanePin] puts the pin back once the card has finished closing.
     */
    fun expandPinned(index: Int) {
        val event = interactor.uiState.value.events.getOrNull(index) ?: return
        interactor.pinEventAt(index)
        pinnedByLane = true
        hold(event)
        showEventCard()
    }

    private fun releaseLanePin() {
        if (!pinnedByLane) return
        pinnedByLane = false
        interactor.pinEventAt(0)
    }

    private fun hold(event: IslandEvent) {
        _heldEventId.value = event.id
        _heldSnapshot.value = event
    }

    private fun clearHold() {
        _heldEventId.value = null
        _heldSnapshot.value = null
    }

    private fun showEventCard() {
        _batteryIntent.value = false
        _intent.value = true
    }

    fun expand() {
        val top = interactor.uiState.value.topEvent ?: return
        if (_heldEventId.value == null) hold(top)
        showEventCard()
    }

    /** Incoming call — the one interrupt allowed to replace an open sheet. */
    fun expandFor(eventId: String) {
        val event = interactor.uiState.value.events.firstOrNull { it.id == eventId } ?: return
        hold(event)
        showEventCard()
    }

    /** Only one card at a time; the battery tap closes an event card that is already open. */
    fun expandBattery() {
        if (chargingEventSource.chargingEvent.value == null) return
        _intent.value = false
        _batteryIntent.value = true
    }

    fun collapse() {
        _intent.value = false
        _batteryIntent.value = false
    }

    fun toggle() {
        if (_intent.value) collapse() else expand()
    }
}
