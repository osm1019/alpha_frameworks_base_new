package com.android.systemui.axdynamicbar.ui.compose

import android.app.Notification
import android.util.Log
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R
import com.android.systemui.alpha.theme.AlphaColors

private fun resolveRemoteViews(ctx: Context, notification: Notification): RemoteViews? {
    notification.bigContentView?.let {
        return it
    }
    notification.contentView?.let {
        return it
    }
    try {
        val builder = Notification.Builder.recoverBuilder(ctx, notification)
        builder.createBigContentView()?.let {
            return it
        }
        builder.createContentView()?.let {
            return it
        }
    } catch (_: Exception) {}
    return null
}

private fun applyOrReapplyRemoteViews(frame: FrameLayout, notification: Notification): Boolean {
    val rv =
        try {
            resolveRemoteViews(frame.context, notification)
        } catch (_: Exception) {
            return false
        } ?: return false
    val existing = if (frame.childCount > 0) frame.getChildAt(0) else null
    if (existing != null) {
        try {
            rv.reapply(frame.context, existing)
            prepareForIsland(existing)
            return true
        } catch (_: Exception) {
            frame.removeAllViews()
        }
    }
    return try {
        val inflated = rv.apply(frame.context, frame)
        prepareForIsland(inflated)
        frame.addView(
            inflated,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        true
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun SbnContentView(sbn: StatusBarNotification, fallback: @Composable () -> Unit) {
    var failed by remember(sbn.key) { mutableStateOf(false) }
    if (failed) {
        fallback()
        return
    }
    AndroidView(
        factory = { ctx ->
            FrameLayout(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { frame ->
            val notification = sbn.notification
            if (notification == null || !applyOrReapplyRemoteViews(frame, notification)) {
                failed = true
            }
        },
        modifier = Modifier.fillMaxWidth().clip(ShapeIconMedium),
    )
}

private val COLLAPSE_CHIP_IDS = arrayOf("expand_button_touch_container", "expand_button")

private fun prepareForIsland(root: View) {
    val res = root.resources

    for (name in COLLAPSE_CHIP_IDS) {
        val id = res.getIdentifier(name, "id", "android")
        if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
    }
    hideCollapseButtons(root)
}

private val COLLAPSE_LABELS = setOf("collapse", "expand", "minimize")

private fun hideCollapseButtons(view: View) {
    if (view is Button) {
        val text = view.text?.toString()?.lowercase() ?: ""
        if (COLLAPSE_LABELS.any { text.contains(it) }) {
            view.visibility = View.GONE
        }
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            hideCollapseButtons(view.getChildAt(i))
        }
    }
}

@Composable
internal fun PromotedOngoingExpanded(
    event: IslandEvent.PromotedOngoing,
    interactor: IslandActions,
) {
    if (event.isTransfer) {
        TransferExpanded(event, interactor)
        return
    }
    SbnContentView(event.sbn, fallback = { PromotedOngoingFallback(event, interactor) })
}

/**
 * A download, drawn rather than borrowed.
 *
 * Every other promoted-ongoing event renders the app's own notification views, and for most of
 * them that is right — a navigation or a rideshare notification is a bespoke layout that says
 * something we could not reconstruct. A transfer is the opposite: it is always the same four
 * facts, and the app's views spend a card-sized surface restating them in notification furniture.
 * So this one is ours: what is moving, how much of it has moved, and the two buttons that steer it.
 *
 * The name is the notification's title — every downloader puts the file there. The size line is
 * [DownloadShape.sizeLine], which can legitimately be null (the platform download manager writes
 * no byte figure anywhere); the notification's own text stands in when it is, so the row is never
 * empty.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransferExpanded(
    event: IslandEvent.PromotedOngoing,
    interactor: IslandActions,
) {
    val colors = rememberIslandColors(event)
    val sizeLine = DownloadShape.sizeLine(event)
    val hasProgress = event.progress >= 0f || event.isIndeterminate
    val percent = if (event.progress in 0f..1f) (event.progress * 100).toInt() else null

    ExpandedCardLayout(
        accentColor = colors.accent,
        iconSize = SizeButtonLg,
        iconBackground = false,
        // The notification's own largeIcon first — that is where a downloader puts the video
        // thumbnail or the cover, and it says more about the file than the app's status glyph does.
        // It is drawn in the layout's own icon cell, cropped square like the media card's art, so
        // it cannot reach the title or the bar the way the app's big content view could.
        icon = { glyph ->
            val art = event.largeIcon ?: event.appIcon
            if (art != null) {
                Image(
                    bitmap = art.toScaledBitmap(SizeButtonLg),
                    contentDescription = null,
                    modifier = Modifier.size(SizeButtonLg).clip(ShapeIconLarge),
                    contentScale = ContentScale.Crop,
                    // The fallback is the notification's small icon, which is a template by
                    // contract -- a flat white glyph the consumer is meant to tint. A largeIcon is
                    // a picture and is never tinted. See [pillIconIsTemplate].
                    colorFilter =
                        if (event.largeIcon == null && pillIconIsTemplate(event)) {
                            ColorFilter.tint(glyph)
                        } else {
                            null
                        },
                )
            } else {
                AnimatedDownloadIcon(glyph, SizeButtonLg)
            }
        },
        title = {
            Text(
                event.title.ifEmpty { event.appName },
                color = OnCardText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = sizeLine ?: DownloadShape.detailLine(event)
            if (detail != null) {
                Text(
                    detail,
                    color = SubtleGray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        // The percentage sits beside the name, not under it: the size line already occupies that
        // slot, and a second number there reads as part of the file's description.
        trailing =
            if (percent != null) {
                {
                    Text(
                        "$percent%",
                        color = colors.accent,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } else null,
        actions = {
            // A transfer that has not sized itself yet gets a real indeterminate bar. Feeding 0f
            // to the determinate one drew an empty track that never moved, which reads as stalled.
            if (event.isIndeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.tonal,
                    gapSize = 0.dp,
                )
            } else if (hasProgress) {
                LinearProgressIndicator(
                    progress = { event.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.tonal,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            TransferActions(event, interactor, colors)
        },
    )
}

/**
 * The app's own actions, classified so the card knows which of them end the transfer.
 *
 * Labels and intents are the app's — [NotificationActionType] only decides the glyph and whether
 * the card survives the tap. That distinction is the bug this card was rewritten for: collapsing
 * on every action meant Pause dismissed the card it had just paused, so the button never came back
 * as Resume and pausing looked like a one-way door.
 */
@Composable
private fun TransferActions(
    event: IslandEvent.PromotedOngoing,
    interactor: IslandActions,
    colors: IslandColorScheme,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val classified =
        event.actions
            .filter { it.label.toString().lowercase() !in COLLAPSE_LABELS }
            .map { notifAction ->
                val pkg = notifAction.action.actionIntent?.creatorPackage ?: context.packageName
                notifAction to notifAction.action.classify(context, pkg)
            }
            .take(MaxTransferActions)
    if (classified.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        classified.forEachIndexed { index, (notifAction, kind) ->
            val filled = index == 0
            ExpressivePillButton(
                label = notifAction.label.toString(),
                icon = actionIcon(kind),
                contentColor = if (filled) colors.onAccent else colors.accent,
                backgroundColor = if (filled) colors.accent else colors.tonal,
                modifier = Modifier.weight(1f),
                onClick = {
                    // An app rebuilds its action intents every time it re-posts, and a card
                    // holding an older one sends into a corpse. Swallowing that silently is how
                    // "Cancel does nothing" becomes unfalsifiable: the tap lands, the intent is
                    // dead, and nothing anywhere says so. Buzz like a refused tap and leave a line.
                    val intent = notifAction.action.actionIntent
                    val sent =
                        if (intent == null) false
                        else try {
                            intent.sendWithBal(context)
                            true
                        } catch (e: Exception) {
                            Log.w(TAG, "action '${notifAction.label}' failed to send", e)
                            false
                        }
                    if (sent) {
                        // Nothing is dismissed here. The app answers a terminal action by
                        // re-posting the same key with its outcome, and the card follows that —
                        // it says what the notification says, and leaves when the notification does.
                        if (kind !in DownloadShape.HOLDS_CARD_OPEN) interactor.collapseIsland()
                    } else {
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    }
                },
            )
        }
    }
}

private const val MaxTransferActions = 3

private const val TAG = "AxDbTransfer"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PromotedOngoingFallback(
    event: IslandEvent.PromotedOngoing,
    interactor: IslandActions,
) {
    val context = LocalContext.current
    ExpandedCardLayout(
        accentColor = BlueAccent,
        iconSize = SizeButtonLg,
        iconBackground = false,
        icon = {
            event.appIcon?.let { icon ->
                Image(
                    bitmap = icon.toScaledBitmap(SizeButtonLg),
                    contentDescription = null,
                    modifier = Modifier.size(SizeButtonLg).clip(ShapeIconLarge),
                    contentScale = ContentScale.Crop,
                )
            } ?: PulsingDot(color = BlueAccent, size = 10.dp)
        },
        title = {
            Text(event.title.ifEmpty { event.appName }, color = OnCardText, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (event.text.isNotEmpty()) {
                Text(event.text, color = SubtleGray, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (event.shortText.isNotEmpty()) {
                StatusChip(event.shortText, BlueAccent)
            }
        },
        actions = {
            if (event.progress >= 0f || event.isIndeterminate) {
                LinearProgressIndicator(
                    progress = { if (event.isIndeterminate) 0f else event.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = BlueAccent,
                    trackColor = BlueAccent.copy(alpha = 0.20f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
            val usableActions =
                event.actions.filter { it.label.toString().lowercase() !in COLLAPSE_LABELS }
            if (usableActions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpaceLg),
                ) {
                    usableActions.take(2).forEach { notifAction ->
                        ActionChip(
                            label = notifAction.label.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                try {
                                    notifAction.action.actionIntent?.sendWithBal(context)
                                } catch (_: Exception) {}
                                val pkg =
                                    notifAction.action.actionIntent?.creatorPackage
                                        ?: context.packageName
                                if (notifAction.action.classify(context, pkg) !in
                                        DownloadShape.HOLDS_CARD_OPEN) {
                                    interactor.collapseIsland()
                                }
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun RowScope.CompactPromotedOngoingRow(event: IslandEvent.PromotedOngoing) {
    event.appIcon?.let { icon ->
        Image(
            bitmap = icon.toScaledBitmap(SizeCompactIcon),
            contentDescription = null,
            modifier = Modifier.size(SizeCompactIcon).clip(ShapeCompact),
            contentScale = ContentScale.Crop,
        )
    }
        ?: Box(
            modifier =
                Modifier.size(SizeCompactIcon).clip(ShapeCompact).background(BlueAccent),
            contentAlignment = Alignment.Center,
        ) {
            PulsingDot(color = AlphaColors.onAccentColor, size = SpaceMd)
        }
    Spacer(Modifier.width(SpaceLg))
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceXxs)) {
        Text(
            event.title.ifEmpty { event.appName },
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subLabel = event.shortText.ifEmpty {
            if ((event.progress >= 0f || event.isIndeterminate) && event.text.isNotEmpty()) event.text
            else ""
        }
        if (subLabel.isNotEmpty()) {
            Text(subLabel, color = BlueAccent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

