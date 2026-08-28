package com.android.systemui.axdynamicbar.model

import android.app.Notification
import com.android.internal.logging.InstanceId
import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.service.notification.StatusBarNotification
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel

enum class RecordingState {
    RECORDING,
    PAUSED,
    SAVED,
}

const val NOTIFICATION_DECAY_MS = 5000L
const val NOTIFICATION_FRESH_PRIORITY = 75
const val NOTIFICATION_STALE_PRIORITY = 15
/** Immediately below Alarm (48), Timer (45), Stopwatch (44) — first slot after compact time events. */
const val MEDIA_PLAYING_PRIORITY = 43
/** Below playing media; stacks with NowPlaying (42); still above charging. */
const val MEDIA_PAUSED_PRIORITY = 42

data class EventBehavior(
    val autoDismissMs: Long? = null,
    val suppressOnDismiss: Boolean = true,
    val autoShowsIsland: Boolean = true,
)

/**
 * One active item in the Dynamic Bar stack. **Higher [priority] wins** when ordering.
 *
 * ### Chip / pill “top” event
 *
 * [IslandUiState.events] is sorted with this type’s natural order ([Comparable]): larger
 * `priority` first. [IslandUiState.topEvent] is `events[pinnedIndex]` else `events.first()`.
 *
 * Overrides (not changed by sorting):
 *
 * – **Heads-up / alert:** [IslandUiState.notificationAlert], when non-null, becomes the rendered
 *   chip body (`notificationAlert ?: topEvent`).
 * – **Pinned card:** User/system may move another stack index forward without changing intrinsic
 *   priorities (`pinnedEventIndex`).
 *
 * ### Reference priority scale (baseline `priority =` on each class)
 *
 * | Range / notes | Types |
 * |---|---|
 * | 97 | `Torch` |
 * | 95 | `PromotedOngoing` |
 * | 90 | `AospChip` (ScreenRecord) |
 * | 86 | `AospChip` (ShareToApp) |
 * | 84 | `AudioRecording` |
 * | 82 | `AospChip` (CastToOtherDevice) |
 * | 80 | `BiometricUnlock`, `AospChip` (default) |
 * | 73 | `Sports` |
 * | 48 | `Alarm` |
 * | 45 | `Timer` |
 * | 44 | `Stopwatch` |
 * | 43 / 42 | `Media` playing / paused (`MEDIA_PLAYING_PRIORITY` / `MEDIA_PAUSED_PRIORITY`) |
 * | 42 | `NowPlaying` |
 * | 41 | `Charging` |
 * | 40 | `Bluetooth` |
 * | 39 | `Hotspot` |
 * | 38 | `RingerMode` |
 * | 35 | `Vpn` |
 * | 25 | `Clipboard` |
 * | 75 / 15 | `Notification` fresh vs stale (`NOTIFICATION_FRESH*` + `NOTIFICATION_DECAY_MS`) |
 * | 10 | `AppSwitch` |
 * | 5 | `KeyguardIndication` — **lane state**, not a stack member (per-type `EventBehavior` may add `autoDismissMs`) |
 *
 * Ties on the same integer are **not** otherwise broken; prefer keeping a stable sort if both exist.
 *
 * ### Center-cutout idle collapse
 *
 * See [com.android.systemui.axdynamicbar.shared.CutoutCenterCollapsePolicy].
 */
sealed class IslandEvent(open val priority: Int, val id: String) : Comparable<IslandEvent> {

    open val behavior: EventBehavior
        get() = DEFAULT_BEHAVIOR

    open fun withoutDrawables(): IslandEvent = this

    data class AospChip(
        val active: OngoingActivityChipModel.Active,
    ) : IslandEvent(priority = priorityForAospChipKey(active.key), id = "aosp_${active.key}")

    data class AudioRecording(
        val appName: String = "",
        val state: RecordingState = RecordingState.RECORDING,
        val startTimeMs: Long = System.currentTimeMillis(),
        val actions: List<NotificationAction> = emptyList(),
        val appIcon: Drawable? = null,
        val pausedDurationMs: Long = 0L,
        /** The recorder notification's own tap target, so a card can open the app it describes. */
        val contentIntent: PendingIntent? = null,
    ) : IslandEvent(priority = 84, id = "audio_recording") {
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Media(
        /** Remedia [InstanceId]. Island [id] stays `"media"` so the stack still has one occupant. */
        val sessionKey: InstanceId,
        val track: String,
        val artist: String,
        val isPlaying: Boolean,
        val albumArt: Drawable? = null,
        val progress: Float = 0f,
        val duration: Long = 0L,
        val position: Long = 0L,
        val playbackSpeed: Float = 1f,
        val positionUpdateTime: Long = 0L,
        val outputDeviceName: String = "",
        val customActions: List<MediaCustomAction> = emptyList(),
        val appIcon: Drawable? = null,
        val packageName: String = "",
        val mediaColor: Int = 0,
    ) : IslandEvent(priority = MEDIA_PLAYING_PRIORITY, id = "media") {
        override val priority: Int
            get() = if (isPlaying) MEDIA_PLAYING_PRIORITY else MEDIA_PAUSED_PRIORITY
        override fun withoutDrawables() = copy(albumArt = null, appIcon = null)
    }

    data class Bluetooth(
        val deviceName: String,
        val batteryLevel: Int = -1,
        val isConnected: Boolean = true,
        val address: String = "",
        val deviceIcon: Drawable? = null,
        val deviceTypeLabel: String = "",
    ) : IslandEvent(priority = 40, id = "bluetooth") {
        override fun withoutDrawables() = copy(deviceIcon = null)
    }

    data class Hotspot(val numDevices: Int) : IslandEvent(priority = 39, id = "hotspot")

    data class Charging(
        val level: Int,
        val isWireless: Boolean,
        val isCharging: Boolean = true,
        val isPowerSave: Boolean = false,
        val timeRemaining: String? = null,
        val power: String? = null,
        val current: String? = null,
        val voltage: String? = null,
        val temp: String? = null,
        val chargeType: String? = null,
    ) : IslandEvent(priority = 41, id = "charging") {
        // No auto-dismiss — charging persists until the user dismisses it or unplugs.
        // This ensures the charging card is visible in stack mode and the pill stays
        // in the status bar when 'show cutout' is off.
        override val behavior = EventBehavior(autoDismissMs = null, suppressOnDismiss = false)
    }

    data class RingerMode(val mode: Int, val label: String) :
        IslandEvent(priority = 38, id = "ringer_mode") {
        override val behavior = EventBehavior(autoDismissMs = 2500L, suppressOnDismiss = false)
    }

    data class BiometricUnlock(val sourceType: Int = 0, val sourceName: String = "Fingerprint") :
        IslandEvent(priority = 80, id = "biometric_unlock") {
        override val behavior = EventBehavior(autoDismissMs = 2000L, suppressOnDismiss = false)
    }

    data class Torch(val level: Int = -1, val maxLevel: Int = -1) :
        IslandEvent(priority = 97, id = "torch") {
        override val behavior = EventBehavior(suppressOnDismiss = false)
        val supportsLevel: Boolean
            get() = level >= 1 && maxLevel > 1
    }

    data class Vpn(val isBranded: Boolean = false, val isValidated: Boolean = false) :
        IslandEvent(priority = 35, id = "vpn")

    data class ClipboardItem(
        val id: Long,
        val preview: String,
        val label: String = "",
        val isUrl: Boolean = false,
        val isImage: Boolean = false,
        val imageUri: Uri? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class Clipboard(
        val label: String = "",
        val preview: String = "",
        val isUrl: Boolean = false,
        val isImage: Boolean = false,
        val imageUri: Uri? = null,
        val items: List<ClipboardItem> = emptyList(),
    ) : IslandEvent(priority = 25, id = "clipboard")

    data class PromotedOngoing(
        val shortText: String = "",
        val title: String = "",
        val text: String = "",
        val appName: String = "",
        val appIcon: Drawable? = null,
        /** The notification's own largeIcon — a video thumbnail or cover, when the app ships one. */
        val largeIcon: Drawable? = null,
        val sbn: StatusBarNotification,
        val actions: List<NotificationAction> = emptyList(),
        val progress: Float = -1f,
        val isIndeterminate: Boolean = false,
        /**
         * Decided once, when the event is built, and then carried. A transfer stops *looking* like
         * one at both ends of its life — it starts indeterminate and ends with no counters at all —
         * so re-deriving this per post would hand the card back to the app's views exactly at the
         * start and the finish.
         */
        val isTransfer: Boolean = false,
    ) : IslandEvent(priority = 95, id = "promoted_${sbn.key}") {
        override fun withoutDrawables() = copy(appIcon = null, largeIcon = null)
    }

    data class Sports(
        val team1Name: String,
        val team2Name: String,
        val score1: String,
        val score2: String,
        val team1Icon: Drawable? = null,
        val team2Icon: Drawable? = null,
        val status: GameStatus = GameStatus.LIVE,
        val statusDetail: String = "",
        val league: String = "",
        val commentary: String = "",
        val key: String,
        val sbn: StatusBarNotification? = null,
        val appIcon: Drawable? = null,
    ) : IslandEvent(priority = 73, id = "sports_$key") {
        override fun withoutDrawables() = copy(team1Icon = null, team2Icon = null, appIcon = null)
    }

    enum class GameStatus { PRE_GAME, LIVE, HALFTIME, FINAL }

    data class NowPlaying(
        val songTitle: String,
        val artist: String,
        val key: String,
        val sbn: StatusBarNotification? = null,
        val appIcon: Drawable? = null,
        /** Album cover when available (notif largeIcon or metadata fallback). */
        val albumArt: Drawable? = null,
        val actions: List<NotificationAction> = emptyList(),
        /** ASI's OPEN_INTENT for this match: its own result screen. */
        val openIntent: PendingIntent? = null,
        /** ASI's FAVORITING_INTENT: the same toggle as the heart in its History screen. */
        val favoritingIntent: PendingIntent? = null,
        val isFavorite: Boolean = false,
        /**
         * When this song was first recognised. Nothing expires a match any more, so the card can
         * outlive the music by a long way; the time is what keeps a stale title honest.
         */
        val recognizedAtMillis: Long = 0L,
    ) : IslandEvent(priority = 42, id = "now_playing") {
        override val behavior = EventBehavior(autoDismissMs = null)
        // The cover resolves after this event is published, so collapsing it to null would make
        // its arrival compare equal to its absence and the update would never be emitted. Stand
        // it on a shared placeholder instead: presence still differs, identity no longer does.
        override fun withoutDrawables() =
            copy(appIcon = null, albumArt = albumArt?.let { DRAWABLE_PRESENT })
    }

    data class Timer(
        val label: String = "",
        val endTimeMs: Long = 0L,
        val originalDurationMs: Long = 0L,
        val appIcon: Drawable? = null,
        val isPaused: Boolean = false,
        val actions: List<NotificationAction> = emptyList(),
        val contentIntent: PendingIntent? = null,
    ) : IslandEvent(priority = 45, id = "timer") {
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Stopwatch(
        val label: String = "",
        val startTimeMs: Long = System.currentTimeMillis(),
        val isRunning: Boolean = true,
        val appIcon: Drawable? = null,
        val actions: List<NotificationAction> = emptyList(),
        /** Null until the first lap, and again while paused — the app stops reporting one. */
        val lapNumber: Int? = null,
        val contentIntent: PendingIntent? = null,
    ) : IslandEvent(priority = 44, id = "stopwatch") {
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class Alarm(
        val label: String = "",
        val triggerTimeMs: Long = 0L,
        val isRinging: Boolean = false,
        val appIcon: Drawable? = null,
    ) : IslandEvent(priority = 48, id = "alarm") {
        override val behavior = EventBehavior(autoDismissMs = 5000L)
        override fun withoutDrawables() = copy(appIcon = null)
    }

    data class AppSwitch(
        val recentApps: List<RecentApp> = emptyList(),
        val previousApp: RecentApp? = null,
    ) : IslandEvent(priority = 10, id = "app_switch") {
        override val behavior = EventBehavior(suppressOnDismiss = false, autoShowsIsland = false)
        override fun withoutDrawables() = copy(
            recentApps = recentApps.map { it.copy(appIcon = null) },
            previousApp = previousApp?.copy(appIcon = null),
        )
    }

    data class KeyguardIndication(
        val text: String,
        val indicationType: IndicationType,
    ) : IslandEvent(priority = 5, id = "kg_indication_${indicationType.name}") {
        override val behavior: EventBehavior
            get() = when (indicationType) {
                IndicationType.BIOMETRIC -> EventBehavior(autoDismissMs = 3500L, suppressOnDismiss = false)
                IndicationType.TRANSIENT -> EventBehavior(autoDismissMs = 3500L, suppressOnDismiss = false)
                IndicationType.TRUST -> EventBehavior(suppressOnDismiss = false)
                IndicationType.DISCLOSURE -> EventBehavior(suppressOnDismiss = false)
                IndicationType.OWNER_INFO -> EventBehavior(suppressOnDismiss = false)
                IndicationType.ALIGNMENT -> EventBehavior(autoDismissMs = 5000L, suppressOnDismiss = false)
                IndicationType.PERSISTENT_UNLOCK -> EventBehavior(suppressOnDismiss = false)
            }

        enum class IndicationType {
            BIOMETRIC,
            TRANSIENT,
            TRUST,
            DISCLOSURE,
            OWNER_INFO,
            ALIGNMENT,
            PERSISTENT_UNLOCK,
        }
    }

    data class RecentApp(
        val packageName: String,
        val appName: String,
        val appIcon: Drawable? = null,
        val taskId: Int = -1,
        val lastActiveTime: Long = System.currentTimeMillis(),
    )

    data class NotificationAction(val label: CharSequence, val action: Notification.Action)

    data class ReplyAction(
        val label: CharSequence,
        val action: Notification.Action,
        val remoteInput: RemoteInput,
    )

    data class MediaCustomAction(
        val label: String,
        val action: String,
        val icon: Drawable? = null,
    )

    data class Call(
        val sbn: StatusBarNotification,
        val callerName: String? = null,
        val number: String? = null,
        val appIcon: Drawable? = null,
        val callerPhoto: Drawable? = null,
        val callType: String = "Phone:incoming",
        val callStartTimeMs: Long = System.currentTimeMillis(),
        val actions: List<NotificationAction> = emptyList(),
    ) : IslandEvent(priority = 100, id = "call_${sbn.key}") {
        override val behavior = EventBehavior(autoDismissMs = null, suppressOnDismiss = false)
        override fun withoutDrawables() = copy(appIcon = null, callerPhoto = null)
    }

    data class Notification(
        val sbn: StatusBarNotification,
        val title: String? = null,
        val text: String? = null,
        val appIcon: Drawable? = null,
        val appName: String = "",
        val progress: Int = -1,
        val progressMax: Int = 100,
        val isProgressIndeterminate: Boolean = false,
        val actions: List<NotificationAction> = emptyList(),
        val replyAction: ReplyAction? = null,
        val isConversation: Boolean = false,
        val isGroupConversation: Boolean = false,
        val conversationTitle: String? = null,
        val senderIcon: Drawable? = null,
        val senderName: String? = null,
        val groupKey: String? = null,
        val isGroupSummary: Boolean = false,
        val notificationImage: Drawable? = null,
        val callStartTimeMs: Long = 0L,
        val createdAt: Long = System.currentTimeMillis(),
    ) : IslandEvent(priority = NOTIFICATION_STALE_PRIORITY, id = "notification_${sbn.key}") {
        override val behavior = EventBehavior(autoDismissMs = null)
        override val priority: Int
            get() =
                if (System.currentTimeMillis() - createdAt < NOTIFICATION_DECAY_MS)
                    NOTIFICATION_FRESH_PRIORITY
                else NOTIFICATION_STALE_PRIORITY
    }

    companion object {
        /** Stands in for any non-null drawable so equality tracks presence, not identity. */
        private val DRAWABLE_PRESENT: Drawable = ColorDrawable()

        internal val DEFAULT_BEHAVIOR = EventBehavior()

        val ONGOING_TYPES: Set<Class<out IslandEvent>> =
            setOf(
                AudioRecording::class.java,
                PromotedOngoing::class.java,
                Sports::class.java,
                Media::class.java,
                Call::class.java,
                Timer::class.java,
                Stopwatch::class.java,
                AospChip::class.java,
            )
    }

    val isOngoing: Boolean
        get() = this::class.java in ONGOING_TYPES

    override fun compareTo(other: IslandEvent): Int = other.priority - this.priority
}

enum class IslandState {
    HIDDEN,
    CHIP,
}

internal fun priorityForAospChipKey(key: String): Int = when {
    key.startsWith("callChip-") -> 88
    key == "ScreenRecord" -> 90
    key == "ShareToApp" -> 86
    key == "CastToOtherDevice" -> 82
    else -> 70
}

