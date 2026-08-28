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

package com.android.systemui.axdynamicbar.shared

import android.app.Notification
import com.android.systemui.axdynamicbar.model.IslandEvent

/**
 * What a progress notification says about the transfer behind it.
 *
 * Apps split evenly between two ways of saying how far along a download is, and neither is
 * derivable from the other:
 *
 *  * `setProgress(totalBytes, doneBytes, false)` — the counters *are* the sizes, and stay exact
 *    for as long as the app keeps posting.
 *  * `setProgress(100, percent, false)` plus a hand-written "1,2 MB/5,0 MB" somewhere in the text.
 *    The platform download manager is in this camp and writes no byte string at all, which is why
 *    [sizeLine] can legitimately come back null on a perfectly ordinary download.
 *
 * Counters win when both are present: they are the live number, the string is only as fresh as the
 * app's last post.
 */
object DownloadShape {

    private val DOWNLOAD_KEYWORDS = Regex("download", RegexOption.IGNORE_CASE)

    /**
     * The pill's test, unchanged from when it lived there — it decides whether the chip draws the
     * tray-and-arrow glyph instead of the app icon, so widening it would repaint chips that have
     * nothing to do with this card.
     */
    fun isDownloadLike(event: IslandEvent.PromotedOngoing): Boolean =
        (event.progress >= 0f || event.isIndeterminate) && (
            DOWNLOAD_KEYWORDS.containsMatchIn(event.title) ||
                DOWNLOAD_KEYWORDS.containsMatchIn(event.text) ||
                DOWNLOAD_KEYWORDS.containsMatchIn(event.shortText)
        )

    /** "1,2 MB/5,0 MB", "800 KB / 12 MB" — the second unit is required, the first is not. */
    private val SIZE_PAIR =
        Regex(
            """(\d+(?:[.,]\d+)?)\s*([KMGT]i?B|B)?\s*/\s*(\d+(?:[.,]\d+)?)\s*([KMGT]i?B|B)""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * There is deliberately no byte counter here.
     *
     * `setProgress` units are the app's own and are not discoverable. CloudStream drives its bar in
     * **kilobytes** — 3111652 of 20553926, which its own text renders as "3111.7 MB" — so reading
     * those counters as bytes printed a confident "3.1 MB" against a real 3.1 GB. A size we cannot
     * state correctly is worse than no size. The percentage is derived from the same counters and
     * is right whatever the unit is, so that is what the card shows when the app names no sizes.
     */

    /** A size pair the app formatted itself, taken from any text slot it may have used. */
    private fun writtenSizeLine(event: IslandEvent.PromotedOngoing): String? {
        val extras = try { event.sbn.notification?.extras } catch (_: Exception) { null }
        val candidates =
            sequenceOf(
                event.text,
                event.shortText,
                extras?.getCharSequence("android.subText")?.toString(),
                extras?.getCharSequence("android.infoText")?.toString(),
                extras?.getCharSequence("android.summaryText")?.toString(),
                extras?.getCharSequence("android.bigText")?.toString(),
                event.title,
            )
        for (candidate in candidates) {
            val match = candidate?.let { SIZE_PAIR.find(it) } ?: continue
            val (done, doneUnit, total, totalUnit) = match.destructured
            // "1,2/5,0 MB" leaves the first half bare; it shares the second's unit.
            val unit = doneUnit.ifEmpty { totalUnit }
            return "$done $unit / $total $totalUnit"
        }
        return null
    }

    /** Downloaded over total, ready to draw, or null when the notification never said. */
    fun sizeLine(event: IslandEvent.PromotedOngoing): String? = writtenSizeLine(event)

    /**
     * The line to draw under the name when the app states no sizes — whichever text slot it did
     * fill. CloudStream leaves `text` null and names the source in `subText`.
     */
    fun detailLine(event: IslandEvent.PromotedOngoing): String? {
        if (isFinished(event)) outcomeLine(event)?.let { return it }
        event.text.takeIf { it.isNotBlank() }?.let { return it }
        val extras = try { event.sbn.notification?.extras } catch (_: Exception) { null }
        return extras?.getCharSequence("android.subText")?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * A transfer that has stopped for good: no bar left and nothing to press. A pause looks the
     * same from the counters alone, which is why the actions decide — a paused transfer still
     * offers Resume.
     */
    fun isFinished(event: IslandEvent.PromotedOngoing): Boolean =
        event.progress < 0f && !event.isIndeterminate && event.actions.isEmpty()

    /**
     * What the app said when it ended. CloudStream writes "Download Canceled - Interstellar" into
     * `bigText` and leaves `text` null, so the card would otherwise announce the outcome by
     * repeating the source name. Only the first line: `bigText` is a paragraph while running.
     */
    private fun outcomeLine(event: IslandEvent.PromotedOngoing): String? {
        val extras = try { event.sbn.notification?.extras } catch (_: Exception) { null }
        val big = extras?.getCharSequence("android.bigText")?.toString()
        return big?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
            ?: event.text.takeIf { it.isNotBlank() }
    }

    /**
     * Whether this event has enough shape to be drawn as a transfer rather than as its own views.
     *
     * A **determinate bar** is the real signal, and it is the same one that routed the event here:
     * `classify()` admits a promoted ongoing on `transferInFlight`. Something measuring itself from
     * 0 to 100% is a job with an end, and a name, a bar and the app's own actions describe it
     * completely — which is all this card draws. What stays on the app's own views is the ongoing
     * notification with *no* bar: navigation, a ride, a call. Those are bespoke layouts saying
     * something we could not reconstruct.
     *
     * An **indeterminate** bar counts too. It is what a transfer looks like before it knows its
     * own size — CloudStream spends the first seconds resolving a stream — and the card has a name,
     * artwork and a Cancel button to show meanwhile, which is more than the app's template manages
     * without laying its thumbnail across the bar.
     *
     * `CATEGORY_PROGRESS` is kept beside both because it survives the transfer stopping. An app
     * that drops its counters while paused would otherwise flip back to its own views at exactly
     * the post that carries Resume.
     *
     * This is only the *first* answer for a key: [IslandEvent.PromotedOngoing.isTransfer] carries
     * it from then on, because a finished transfer satisfies none of these tests.
     */
    fun hasTransferShape(event: IslandEvent.PromotedOngoing): Boolean =
        event.progress >= 0f ||
            event.isIndeterminate ||
            isTransferCategory(event) ||
            isDownloadLike(event) ||
            writtenSizeLine(event) != null

    private fun isTransferCategory(event: IslandEvent.PromotedOngoing): Boolean =
        try {
            event.sbn.notification?.category == Notification.CATEGORY_PROGRESS
        } catch (_: Exception) {
            false
        }

    /**
     * Actions that leave the transfer running. Pause and Resume are mid-flight states — the card
     * has to survive them or the button can never come back as its opposite, which is the whole
     * point of the pair. Everything else here ends the transfer, and the card goes with it.
     */
    val HOLDS_CARD_OPEN = setOf(NotificationActionType.PAUSE, NotificationActionType.RESUME)
}
