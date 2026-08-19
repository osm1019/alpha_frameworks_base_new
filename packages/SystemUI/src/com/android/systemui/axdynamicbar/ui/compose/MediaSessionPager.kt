package com.android.systemui.axdynamicbar.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.internal.logging.InstanceId
import com.android.systemui.axdynamicbar.model.IslandEvent
import kotlin.coroutines.cancellation.CancellationException

/**
 * Same carousel as QS / lockscreen B: the pager owns dead-zone swipes, the track consumes its
 * own pointer so it can scrub. Dots sit as a centered header over the card.
 *
 * E (keyguard) and D (stack) both pass through here with no edge-dismiss. QS is not
 * swipe-dismissable; leftover overscroll on D was latching the dismiss effect into a
 * terminal dismissed state.
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
    fillHeight: Boolean = false,
    dotActive: Color,
    dotInactive: Color,
    content: @Composable (IslandEvent.Media) -> Unit,
) {
    if (sessions.isEmpty()) return
    val keys = sessions.map { it.sessionKey }
    val currentIndex = keys.indexOf(selectedKey).let { if (it >= 0) it else 0 }
    val pagerState = rememberPagerState(initialPage = currentIndex) { sessions.size }
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

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier.fillMaxWidth().then(if (fillHeight) Modifier.fillMaxSize() else Modifier),
                userScrollEnabled = pagerState.pageCount > 1,
                pageSpacing = 8.dp,
                beyondViewportPageCount = 0,
                key = { page -> sessions[page].sessionKey },
            ) { page ->
                content(sessions[page])
            }
            if (pagerState.pageCount > 1) {
                SessionPagerDots(
                    pagerState = pagerState,
                    active = dotActive,
                    inactive = dotInactive,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionPagerDots(
    pagerState: PagerState,
    active: Color,
    inactive: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
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

/**
 * Owns the pointer on the progress track: consume in the Initial pass so [HorizontalPager]
 * never starts a page, then tap or drag maps to a fraction.
 *
 * Do not flip pager `userScrollEnabled` from this — that recomposes mid-gesture and cancels
 * both the scrub and the page.
 */
@Composable
internal fun Modifier.mediaScrubGesture(
    durationMs: Long,
    inset: Dp,
    onScrub: (Float) -> Unit,
    onFinished: (Float) -> Unit,
    onCancel: () -> Unit = {},
): Modifier {
    val scrub = rememberUpdatedState(onScrub)
    val finished = rememberUpdatedState(onFinished)
    val cancel = rememberUpdatedState(onCancel)
    if (durationMs <= 0L) return this
    return pointerInput(durationMs, inset) {
        val insetPx = inset.toPx()
        awaitEachGesture {
            val down: PointerInputChange
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val candidate =
                    event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }
                if (candidate != null) {
                    down = candidate
                    break
                }
            }
            down.consume()
            var fraction = scrubFraction(down.position.x, size.width, insetPx)
            scrub.value(fraction)
            val pointerId = down.id
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    change.consume()
                    fraction = scrubFraction(change.position.x, size.width, insetPx)
                    scrub.value(fraction)
                    if (!change.pressed) {
                        finished.value(fraction)
                        return@awaitEachGesture
                    }
                }
                cancel.value()
            } catch (c: CancellationException) {
                cancel.value()
                throw c
            }
        }
    }
}

internal fun scrubFraction(x: Float, width: Int, insetPx: Float): Float {
    val usable = (width - insetPx * 2f).coerceAtLeast(1f)
    return ((x - insetPx) / usable).coerceIn(0f, 1f)
}
