/*
 * SPDX-FileCopyrightText: 2026 AlphaDroid
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.axdynamicbar.shared

import com.android.systemui.axdynamicbar.model.IslandEvent

/**
 * **Center cutout pill only** (`CutoutPillCenter`): the right-hand text lane starts expanded,
 * then auto-collapses to **0dp** after [CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS] (5 s).
 *
 * ### Rule
 *
 * **All sticky events** (`EventBehavior.autoDismissMs == null`) auto-collapse after the timeout.
 * No per-event exceptions — Timer, Stopwatch, AudioRecording, AospChip, Charging,
 * Notification, Media, and all others follow the same rule.
 *
 * Events that dismiss themselves (`autoDismissMs != null`, e.g. ringer ping, biometric toast,
 * transient keyguard indications) skip idle collapse to avoid racing their own teardown.
 *
 * ### Re-expansion
 *
 * The composable (`CutoutPillCenter`) re-expands the right lane when the content key or
 * collapse identity key changes (e.g. media track change, timer pause/resume). Each
 * re-expansion restarts the 5 s window. Ticking time updates do **not** trigger re-expansion.
 *
 * ### Non-goals
 *
 * Left icon lane stays fixed width; badge behavior is unrelated. Status bar chip does **not**
 * use this policy. The lockscreen **media** chip reuses the same delay + width-collapse spring
 * for its track/artist lane only (`KeyguardMediaChipContent`); other keyguard events do not.
 */
internal object CutoutCenterCollapsePolicy {

    internal const val CUTOUT_CENTER_RIGHT_IDLE_COLLAPSE_DELAY_MS = 5_000L

    /** Right text lane may schedule idle collapse; `false` means keep expanded indefinitely. */
    internal fun rightLaneEligibleForIdleCollapse(event: IslandEvent): Boolean {
        if (event.behavior.autoDismissMs != null) return false
        return true
    }
}
