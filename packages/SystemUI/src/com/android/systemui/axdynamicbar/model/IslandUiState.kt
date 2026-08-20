package com.android.systemui.axdynamicbar.model

data class IslandUiState(
    val events: List<IslandEvent> = emptyList(),
    val islandState: IslandState = IslandState.HIDDEN,
    val pinnedEventIndex: Int = 0,
    val manuallyHidden: Boolean = false,
    val forceVisible: Boolean = false,
    val notificationAlert: IslandEvent.Notification? = null,
) {

    val shouldShow: Boolean
        get() = islandState == IslandState.CHIP || notificationAlert != null

    val isChip: Boolean
        get() = islandState == IslandState.CHIP

    val topEvent: IslandEvent?
        get() = events.getOrNull(pinnedEventIndex) ?: events.firstOrNull()

    val activeEvents: List<IslandEvent>
        get() = events.filter { it !is IslandEvent.Notification }
}

/**
 * Status bar notification keys that are already represented in the Dynamic Bar chip / alert card.
 * Used to suppress duplicate tray icons and a second heads-up for a key the island is already
 * showing. First-post routing lives in HeadsUpCoordinator, not here.
 */
fun IslandUiState.mirroredStatusBarNotificationKeys(): Set<String> {
    if (!shouldShow) return emptySet()
    val keys = LinkedHashSet<String>()
    topEvent?.let { ev ->
        when (ev) {
            is IslandEvent.PromotedOngoing -> keys.add(ev.sbn.key)
            is IslandEvent.Notification -> keys.add(ev.sbn.key)
            is IslandEvent.Sports -> ev.sbn?.let { keys.add(it.key) }
            is IslandEvent.NowPlaying -> ev.sbn?.let { keys.add(it.key) }
            else -> {}
        }
    }
    notificationAlert?.let { keys.add(it.sbn.key) }
    return keys
}
