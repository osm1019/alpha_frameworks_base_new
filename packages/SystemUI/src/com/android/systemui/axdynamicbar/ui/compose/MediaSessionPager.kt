package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.compose.gesture.overscrollToDismiss
import com.android.internal.logging.InstanceId
import com.android.systemui.axdynamicbar.model.IslandEvent

/**
 * Remedia's lockscreen gesture: the pager eats the swipe while it can still move; leftover
 * scroll at an edge becomes dismiss when [onEdgeDismiss] is set. One session is already at
 * both edges. Keyguard E passes null — that sheet is not swipe-dismissable.
 *
 * [onSelect] runs on bind and when the user pages, so island transport follows the visible
 * session. [onRelease] puts transport back on the chip's primary when the card goes away.
 */
@Composable
internal fun MediaSessionPager(
    sessions: List<IslandEvent.Media>,
    selectedKey: InstanceId,
    onSelect: (InstanceId) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    onEdgeDismiss: (() -> Unit)? = null,
    fillHeight: Boolean = false,
    dotActive: Color,
    dotInactive: Color,
    content: @Composable (IslandEvent.Media) -> Unit,
) {
    if (sessions.isEmpty()) return
    val keys = sessions.map { it.sessionKey }
    val currentIndex = keys.indexOf(selectedKey).let { if (it >= 0) it else 0 }
    val pagerState = rememberPagerState(initialPage = currentIndex) { sessions.size }
    val swipeLock = remember { mutableStateOf(false) }
    val scrolling = !swipeLock.value
    val release = rememberUpdatedState(onRelease)

    LaunchedEffect(selectedKey) { onSelect(selectedKey) }
    DisposableEffect(Unit) { onDispose { release.value() } }

    LaunchedEffect(currentIndex, sessions.size) {
        if (currentIndex != pagerState.currentPage && currentIndex in sessions.indices) {
            pagerState.scrollToPage(currentIndex)
        }
    }
    LaunchedEffect(pagerState.currentPage, keys) {
        val key = keys.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (key != selectedKey) onSelect(key)
    }

    CompositionLocalProvider(LocalDismissSwipeLock provides swipeLock) {
        val dismiss =
            if (onEdgeDismiss != null) {
                Modifier.overscrollToDismiss(enabled = scrolling, onDismissed = onEdgeDismiss)
            } else {
                Modifier
            }
        Column(
            modifier = modifier.then(dismiss),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier),
                userScrollEnabled = scrolling,
                pageSpacing = 8.dp,
                beyondViewportPageCount = 0,
                key = { page -> sessions[page].sessionKey },
            ) { page ->
                content(sessions[page])
            }
            if (pagerState.pageCount > 1) {
                SessionPagerDots(pagerState, dotActive, dotInactive)
            }
        }
    }
}

@Composable
private fun SessionPagerDots(
    pagerState: PagerState,
    active: Color,
    inactive: Color,
) {
    Row(
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pagerState.pageCount) { index ->
            Box(
                modifier =
                    Modifier.size(6.dp)
                        .clip(CircleShape)
                        .background(if (index == pagerState.currentPage) active else inactive)
            )
        }
    }
}
