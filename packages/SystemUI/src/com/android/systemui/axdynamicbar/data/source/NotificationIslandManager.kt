package com.android.systemui.axdynamicbar.data.source

import android.app.Notification
import android.content.Context
import com.android.systemui.res.R
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.model.RecordingState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.util.ScrimUtils
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SysUISingleton
class NotificationIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "NotificationIslandManager"

        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")
        private val ALARM_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")

        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private val SPORTS_PACKAGES = setOf(
            "com.espn.score_center",
            "com.fivemobile.thescore",
            "com.yahoo.mobile.client.android.sportacular",
            "com.sofascore.app",
            "com.fotmob",
            "com.foxsports.android",
            "com.cbs.sports.android",
            "com.bleacherreport.android.teamstream",
            "com.nbaimd.gametime.nba2011",
            "com.nfl.fantasy.core",
            "com.mlb.atbat",
            "com.nhl.gc1112.free",
            "livescore.livesportsmedia.com",
            "com.flashscore.en",
            "com.365scores.android",
            "lunosoftware.sportsalerts",
            "com.onefootball.brasil",
        )

        private val SCORE_PATTERN =
            Regex("""(.+?)\s+(\d+)\s*[-–—:]\s*(\d+)\s+(.+)""")
        private val VS_PATTERN =
            Regex("""(.+?)\s+(?:vs\.?|v\.?|at)\s+(.+)""", RegexOption.IGNORE_CASE)

        private const val NOW_PLAYING_PACKAGE = "com.google.android.as"
        private const val NOW_PLAYING_CHANNEL = "ambientmusic"

        private val RECORDER_PACKAGES =
            setOf("com.google.android.apps.recorder", "com.android.soundrecorder")

        private val SUPPRESSED_PACKAGES: Set<String> = buildSet {
            addAll(CLOCK_PACKAGES)
            addAll(ALARM_PACKAGES)
            addAll(RECORDER_PACKAGES)
        }
    }

    private val _timerEvent = MutableStateFlow<IslandEvent.Timer?>(null)
    val timerEvent: StateFlow<IslandEvent.Timer?> = _timerEvent.asStateFlow()

    private val _stopwatchEvent = MutableStateFlow<IslandEvent.Stopwatch?>(null)
    val stopwatchEvent: StateFlow<IslandEvent.Stopwatch?> = _stopwatchEvent.asStateFlow()

    private val _alarmEvent = MutableStateFlow<IslandEvent.Alarm?>(null)
    val alarmEvent: StateFlow<IslandEvent.Alarm?> = _alarmEvent.asStateFlow()

    private val _notificationEvents = MutableStateFlow<List<IslandEvent.Notification>>(emptyList())
    val notificationEvents: StateFlow<List<IslandEvent.Notification>> =
        _notificationEvents.asStateFlow()

    private val _audioRecordingEvent = MutableStateFlow<IslandEvent.AudioRecording?>(null)
    val audioRecordingEvent: StateFlow<IslandEvent.AudioRecording?> =
        _audioRecordingEvent.asStateFlow()

    private val _promotedOngoingEvents =
        MutableStateFlow<List<IslandEvent.PromotedOngoing>>(emptyList())
    val promotedOngoingEvents: StateFlow<List<IslandEvent.PromotedOngoing>> =
        _promotedOngoingEvents.asStateFlow()

    private val _sportsEvents = MutableStateFlow<List<IslandEvent.Sports>>(emptyList())
    val sportsEvents: StateFlow<List<IslandEvent.Sports>> = _sportsEvents.asStateFlow()

    private val _nowPlayingEvent = MutableStateFlow<IslandEvent.NowPlaying?>(null)
    val nowPlayingEvent: StateFlow<IslandEvent.NowPlaying?> = _nowPlayingEvent.asStateFlow()

    private val _callEvents = MutableStateFlow<List<IslandEvent.Call>>(emptyList())
    val callEvents: StateFlow<List<IslandEvent.Call>> = _callEvents.asStateFlow()

    @Volatile var disabledTypes: Set<String> = emptySet()

    private var recorderPackage: String? = null

    private var recorderNotifKey: String? = null

    private var pauseStartMs: Long = 0L

    private var accumulatedPauseMs: Long = 0L

    val notificationFlow = MutableSharedFlow<IslandEvent.Notification>(extraBufferCapacity = 16)
    val notificationRemovedFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)

    var activeMediaPackageProvider: (() -> String?)? = null

    private val seenNotificationKeys = mutableSetOf<String>()
    private val seenMessagingTimestamps = mutableMapOf<String, Long>()

    var onTimerEvent: ((IslandEvent.Timer) -> Unit)? = null
    var onAlarmEvent: ((IslandEvent.Alarm) -> Unit)? = null
    var onNotificationPosted: ((IslandEvent.Notification) -> Unit)? = null

    @Volatile private var listening = false
    @Volatile private var timerJob: Job? = null

    private var timerNotificationKey: String? = null
    private var timerOriginalDurationMs: Long = 0L
    private var stopwatchNotificationKey: String? = null

    private val scrimListener =
        object : ScrimUtils.ScrimEventListener {
            override fun onNotificationRemoved(sbn: StatusBarNotification) {
                if (!listening) return
                val pkg = sbn.packageName ?: return
                seenNotificationKeys.remove(sbn.key)
                seenMessagingTimestamps.remove(sbn.key)

                if (sbn.key == timerNotificationKey) {
                    timerNotificationKey = null
                    timerJob?.cancel()
                    timerJob = null
                    _timerEvent.value = null
                }
                if (sbn.key == stopwatchNotificationKey) {
                    stopwatchNotificationKey = null
                    _stopwatchEvent.value = null
                }

                if (sbn.key == recorderNotifKey) {
                    recorderNotifKey = null
                    val currentState = _audioRecordingEvent.value?.state
                    if (currentState == RecordingState.SAVED) {
                        recorderPackage = null
                        _audioRecordingEvent.value = null
                        pauseStartMs = 0L
                        accumulatedPauseMs = 0L
                    }
                }

                _callEvents.value =
                    _callEvents.value.filter { it.sbn.key != sbn.key }

                _promotedOngoingEvents.value =
                    _promotedOngoingEvents.value.filter { it.sbn.key != sbn.key }

                _sportsEvents.value =
                    _sportsEvents.value.filter { it.key != sbn.key }

                if (_nowPlayingEvent.value?.key == sbn.key) {
                    _nowPlayingEvent.value = null
                }

                _notificationEvents.value =
                    _notificationEvents.value.filter { it.sbn.key != sbn.key }

                notificationRemovedFlow.tryEmit(sbn.key)
            }

            override fun onNotificationPosted(sbn: StatusBarNotification) {
                if (!listening) return
                val pkg = sbn.packageName ?: return
                val extras = sbn.notification?.extras ?: return
                when (classify(sbn)) {
                    NotificationRoute.STOPWATCH -> {
                        val (labels, intents) = clockActionLists(sbn)
                        handleStopwatch(sbn, extras, labels, intents)
                    }
                    NotificationRoute.TIMER -> {
                        val (labels, intents) = clockActionLists(sbn)
                        handleTimer(sbn, extras, labels, intents)
                    }
                    NotificationRoute.ALARM -> handleAlarm(sbn, extras)
                    NotificationRoute.CALL -> handleCallNotification(sbn, extras)
                    NotificationRoute.AUDIO_RECORDING ->
                        handleAudioRecording(sbn, extras, pkg)
                    NotificationRoute.AUDIO_RECORDING_SAVED ->
                        handleAudioRecordingSaved(sbn, extras)
                    NotificationRoute.NOW_PLAYING -> handleNowPlaying(sbn, extras)
                    NotificationRoute.SPORTS -> handleSportsPosted(sbn, extras, pkg)
                    NotificationRoute.MEDIA -> {
                        _promotedOngoingEvents.value =
                            _promotedOngoingEvents.value.filter { it.sbn.key != sbn.key }
                    }
                    NotificationRoute.PROMOTED -> handlePromotedOngoing(sbn, extras, pkg)
                    NotificationRoute.ALERT -> emitGenericAlert(sbn, extras, pkg)
                    NotificationRoute.IGNORED -> {
                        if (!sbn.isOngoing) {
                            _promotedOngoingEvents.value =
                                _promotedOngoingEvents.value.filter { it.sbn.key != sbn.key }
                        }
                    }
                }
            }
        }

    /**
     * True if this SBN is a generic notification alert — the only type the heads-up pipeline
     * may steal. Same [classify] the posted handler uses; dedup is not part of the route.
     */
    fun wouldConsumeAsNotificationAlert(sbn: StatusBarNotification): Boolean {
        if (!listening) return false
        return classify(sbn) == NotificationRoute.ALERT
    }

    /**
     * Build the island alert event from [sbn] with no dedup. Used when the pipeline has already
     * spent the peek on this key — the card must be drawable even if [emitGenericAlert] would
     * drop a second post of the same key.
     */
    fun buildNotificationAlert(sbn: StatusBarNotification): IslandEvent.Notification? {
        val pkg = sbn.packageName ?: return null
        val extras = sbn.notification?.extras ?: return null
        return buildNotificationAlert(sbn, extras, pkg)
    }

    private enum class NotificationRoute {
        STOPWATCH,
        TIMER,
        ALARM,
        CALL,
        AUDIO_RECORDING,
        AUDIO_RECORDING_SAVED,
        NOW_PLAYING,
        SPORTS,
        MEDIA,
        PROMOTED,
        ALERT,
        IGNORED,
    }

    /**
     * Side-effect-free route for an SBN. [onNotificationPosted] and
     * [wouldConsumeAsNotificationAlert] both call this — do not fork the chain.
     */
    private fun classify(sbn: StatusBarNotification): NotificationRoute {
        val pkg = sbn.packageName ?: return NotificationRoute.IGNORED
        val notification = sbn.notification ?: return NotificationRoute.IGNORED
        val extras = notification.extras ?: return NotificationRoute.IGNORED

        if (pkg in CLOCK_PACKAGES) {
            when (clockKind(sbn, extras)) {
                ClockKind.STOPWATCH ->
                    return if ("stopwatch" !in disabledTypes) NotificationRoute.STOPWATCH
                    else NotificationRoute.IGNORED
                ClockKind.TIMER ->
                    return if ("timer" !in disabledTypes) NotificationRoute.TIMER
                    else NotificationRoute.IGNORED
                ClockKind.NEITHER -> {}
            }
        }

        val isAlarmCategory = notification.category == Notification.CATEGORY_ALARM
        if ((isAlarmCategory || pkg in ALARM_PACKAGES) && sbn.isOngoing) {
            return if ("alarm" !in disabledTypes) NotificationRoute.ALARM
            else NotificationRoute.IGNORED
        }

        if (notification.category == Notification.CATEGORY_CALL && isCallStyle(extras)) {
            return if ("call" !in disabledTypes) NotificationRoute.CALL
            else NotificationRoute.IGNORED
        }

        val isMedia =
            notification.category == Notification.CATEGORY_TRANSPORT ||
                extras.containsKey(Notification.EXTRA_MEDIA_SESSION)

        if (
            sbn.isOngoing &&
                !isMedia &&
                "audio_recording" !in disabledTypes &&
                isAudioRecordingNotification(sbn, pkg)
        ) {
            return NotificationRoute.AUDIO_RECORDING
        }

        if (pkg == recorderPackage && _audioRecordingEvent.value != null && !sbn.isOngoing) {
            return NotificationRoute.AUDIO_RECORDING_SAVED
        }

        if (
            pkg == NOW_PLAYING_PACKAGE &&
                (notification.channelId ?: "").contains(NOW_PLAYING_CHANNEL)
        ) {
            return if ("now_playing" !in disabledTypes) NotificationRoute.NOW_PLAYING
            else NotificationRoute.IGNORED
        }

        if (pkg in SUPPRESSED_PACKAGES) return NotificationRoute.IGNORED

        if ("sports" !in disabledTypes && isSportsNotification(sbn, extras, pkg)) {
            return NotificationRoute.SPORTS
        }

        if (isMedia && "media" !in disabledTypes) return NotificationRoute.MEDIA

        val progress = progressOf(extras)
        val transferInFlight =
            progress.hasProgress &&
                (sbn.isOngoing || progress.indeterminate || progress.raw < progress.max)

        if (
            sbn.isOngoing &&
                isPromotable(sbn, extras) &&
                "promoted_ongoing" !in disabledTypes
        ) {
            return NotificationRoute.PROMOTED
        }
        if (transferInFlight && "promoted_ongoing" !in disabledTypes) {
            return NotificationRoute.PROMOTED
        }
        if (sbn.isOngoing) return NotificationRoute.IGNORED
        if ("notification" in disabledTypes) return NotificationRoute.IGNORED
        if (notification.category == Notification.CATEGORY_TRANSPORT) {
            return NotificationRoute.IGNORED
        }
        if (notification.category == Notification.CATEGORY_SERVICE && !progress.hasProgress) {
            return NotificationRoute.IGNORED
        }
        if (pkg == activeMediaPackageProvider?.invoke()) return NotificationRoute.IGNORED
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            return NotificationRoute.IGNORED
        }
        return NotificationRoute.ALERT
    }

    private enum class ClockKind { STOPWATCH, TIMER, NEITHER }

    private fun clockKind(sbn: StatusBarNotification, extras: Bundle): ClockKind {
        val channelId = sbn.notification?.channelId?.lowercase() ?: ""
        val (actionLabels, actionIntents) = clockActionLists(sbn)
        val hasStopwatchIntent =
            actionIntents.any {
                it == DeskClockActions.START_STOPWATCH ||
                    it == DeskClockActions.PAUSE_STOPWATCH ||
                    it == DeskClockActions.RESET_STOPWATCH ||
                    it == DeskClockActions.LAP_STOPWATCH ||
                    it == DeskClockActions.SHOW_STOPWATCH
            }
        val hasTimerIntent =
            actionIntents.any {
                it == DeskClockActions.START_TIMER ||
                    it == DeskClockActions.PAUSE_TIMER ||
                    it == DeskClockActions.RESET_TIMER ||
                    it == DeskClockActions.ADD_MINUTE_TIMER ||
                    it == DeskClockActions.SHOW_TIMER
            }
        val hasLap = actionLabels.any { it.contains("lap") }
        val isCountDown = extras.getBoolean("android.chronometerCountDown", false)
        val isStopwatch = hasStopwatchIntent || channelId.contains("stopwatch") || hasLap
        val isTimer =
            !isStopwatch &&
                (hasTimerIntent ||
                    channelId.contains("timer") ||
                    channelId.contains("firing") ||
                    isCountDown ||
                    actionLabels.any { it.contains("+1") || it.contains("add") })
        return when {
            isStopwatch -> ClockKind.STOPWATCH
            isTimer -> ClockKind.TIMER
            else -> ClockKind.NEITHER
        }
    }

    private fun clockActionLists(sbn: StatusBarNotification): Pair<List<String>, List<String>> {
        val actions = sbn.notification?.actions ?: return emptyList<String>() to emptyList()
        return actions.map { it.title?.toString()?.lowercase() ?: "" } to
            actions.map { actionIntentString(it) ?: "" }
    }

    private fun isCallStyle(extras: Bundle): Boolean =
        extras.containsKey(Notification.EXTRA_ANSWER_INTENT) ||
            extras.containsKey(Notification.EXTRA_DECLINE_INTENT) ||
            extras.containsKey(Notification.EXTRA_HANG_UP_INTENT)

    private fun isAudioRecordingNotification(sbn: StatusBarNotification, pkg: String): Boolean {
        val allActions = sbn.notification?.actions ?: return false
        val actionIntentMap = allActions.associateWith { actionIntentString(it) }
        val iconResMap =
            allActions.associateWith { actionIconResName(it, pkg)?.lowercase() }
        val recActions =
            allActions.filter { a ->
                val intent = actionIntentMap[a]
                val icon = iconResMap[a] ?: ""
                when {
                    intent == "STOP" || intent == "PAUSE" || intent == "RESUME" -> true
                    icon.matchesMaterial(MaterialIconSet.Stop) -> true
                    icon.matchesMaterial(MaterialIconSet.Pause) -> true
                    icon.matchesMaterial(MaterialIconSet.Play) -> true
                    else -> {
                        val lbl = a.title?.toString()?.lowercase() ?: ""
                        lbl.contains("stop") ||
                            lbl.contains("pause") ||
                            lbl.contains("resume")
                    }
                }
            }
        val hasStop =
            recActions.any { a ->
                actionIntentMap[a] == "STOP" ||
                    (iconResMap[a] ?: "").matchesMaterial(MaterialIconSet.Stop) ||
                    (a.title?.toString()?.lowercase() ?: "").contains("stop")
            }
        return hasStop && recActions.size >= 2
    }

    private fun isSportsGroupKey(sbn: StatusBarNotification): Boolean {
        val groupKey = sbn.groupKey ?: ""
        return groupKey.contains("::sports", ignoreCase = true) ||
            groupKey.contains("sport", ignoreCase = true) ||
            groupKey.contains("score", ignoreCase = true)
    }

    private fun isSportsNotification(
        sbn: StatusBarNotification,
        extras: Bundle,
        pkg: String,
    ): Boolean {
        if (pkg == GOOGLE_PACKAGE) {
            return wouldCaptureSports(extras, forceCapture = isSportsGroupKey(sbn))
        }
        if (pkg in SPORTS_PACKAGES) {
            return wouldCaptureSports(extras, forceCapture = true)
        }
        return false
    }

    private fun wouldCaptureSports(extras: Bundle, forceCapture: Boolean): Boolean {
        val title = extras.getCharSequence("android.title")?.toString() ?: return false
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val allFields = listOf(title.trim(), text.trim())
        if (allFields.any { SCORE_PATTERN.find(it) != null }) return true
        if (VS_PATTERN.find("$title $text") != null) return true
        return forceCapture
    }

    private data class ProgressInfo(
        val raw: Int,
        val max: Int,
        val indeterminate: Boolean,
        val hasProgress: Boolean,
    )

    private fun progressOf(extras: Bundle): ProgressInfo {
        val indeterminate = extras.getBoolean("android.progressIndeterminate", false)
        val raw = extras.getInt("android.progress", -1)
        val max = extras.getInt("android.progressMax", 0)
        val hasProgress =
            indeterminate || (extras.containsKey("android.progress") && max > 0 && raw >= 0)
        return ProgressInfo(raw, max, indeterminate, hasProgress)
    }

    private fun handleSportsPosted(
        sbn: StatusBarNotification,
        extras: Bundle,
        pkg: String,
    ) {
        if (pkg == GOOGLE_PACKAGE) {
            handleSportsScore(sbn, extras, forceCapture = isSportsGroupKey(sbn))
            return
        }
        handleSportsScore(sbn, extras, forceCapture = true)
    }

    private fun handleAudioRecording(
        sbn: StatusBarNotification,
        extras: Bundle,
        pkg: String,
    ) {
        val allActions = sbn.notification?.actions ?: emptyArray()
        val actionIntentMap = allActions.associateWith { actionIntentString(it) }
        val iconResMap =
            allActions.associateWith { actionIconResName(it, pkg)?.lowercase() }
        val recActions =
            allActions.filter { a ->
                val intent = actionIntentMap[a]
                val icon = iconResMap[a] ?: ""
                when {
                    intent == "STOP" || intent == "PAUSE" || intent == "RESUME" -> true
                    icon.matchesMaterial(MaterialIconSet.Stop) -> true
                    icon.matchesMaterial(MaterialIconSet.Pause) -> true
                    icon.matchesMaterial(MaterialIconSet.Play) -> true
                    else -> {
                        val lbl = a.title?.toString()?.lowercase() ?: ""
                        lbl.contains("stop") ||
                            lbl.contains("pause") ||
                            lbl.contains("resume")
                    }
                }
            }
        val hasResumeAction = recActions.any { actionIntentMap[it] == "RESUME" }
        val hasPauseAction = recActions.any { actionIntentMap[it] == "PAUSE" }
        val isPaused =
            when {
                hasResumeAction -> true
                hasPauseAction -> false
                else ->
                    recActions.any { a ->
                        (iconResMap[a] ?: "").matchesMaterial(MaterialIconSet.Play) ||
                            (a.title?.toString()?.lowercase() ?: "").contains("resume")
                    }
            }
        val notifActions =
            recActions.mapNotNull { a ->
                a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
            }
        val existing = _audioRecordingEvent.value
        val now = System.currentTimeMillis()
        val parsedElapsedMs = parseRecorderElapsedMs(extras)
        val startTime =
            when {
                parsedElapsedMs != null -> now - parsedElapsedMs
                existing != null &&
                    existing.state != RecordingState.SAVED &&
                    recorderNotifKey == sbn.key -> existing.startTimeMs
                sbn.notification?.extras?.getBoolean("android.showChronometer") == true ->
                    sbn.notification.`when`
                else -> now
            }
        accumulatedPauseMs = 0L
        pauseStartMs = 0L
        recorderPackage = pkg
        recorderNotifKey = sbn.key
        _audioRecordingEvent.value =
            IslandEvent.AudioRecording(
                appName = resolveAppName(pkg),
                state = if (isPaused) RecordingState.PAUSED else RecordingState.RECORDING,
                startTimeMs = startTime,
                actions = notifActions,
                pausedDurationMs = 0L,
                contentIntent = sbn.notification?.contentIntent,
            )
    }

    private fun handleAudioRecordingSaved(sbn: StatusBarNotification, extras: Bundle) {
        val allActions = sbn.notification?.actions ?: emptyArray()
        val notifActions =
            allActions.mapNotNull { a ->
                a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
            }
        val title = extras.getString("android.title") ?: ""
        val existing = _audioRecordingEvent.value ?: return
        recorderNotifKey = sbn.key
        _audioRecordingEvent.value =
            existing.copy(
                appName = title.ifEmpty { existing.appName },
                state = RecordingState.SAVED,
                actions = notifActions,
                contentIntent = sbn.notification?.contentIntent,
            )
    }

    private fun emitGenericAlert(
        sbn: StatusBarNotification,
        extras: Bundle,
        pkg: String,
    ) {
        val notif = sbn.notification
        val isMessagingStyle =
            notif != null && notif.isStyle(Notification.MessagingStyle::class.java)
        val latestMessageTime: Long =
            if (isMessagingStyle) {
                val msgs =
                    notif?.extras?.getParcelableArray(
                        Notification.EXTRA_MESSAGES,
                        Parcelable::class.java,
                    )
                if (msgs != null && msgs.isNotEmpty()) {
                    Notification.MessagingStyle.Message
                        .getMessagesFromBundleArray(msgs)
                        .maxOfOrNull { it.timestamp } ?: 0L
                } else 0L
            } else 0L

        if (isMessagingStyle && latestMessageTime > 0L) {
            val previous = seenMessagingTimestamps.put(sbn.key, latestMessageTime)
            if (previous != null && previous == latestMessageTime) return
        } else {
            if (!seenNotificationKeys.add(sbn.key)) return
        }

        val event = buildNotificationAlert(sbn, extras, pkg) ?: return
        applicationScope.launch { notificationFlow.emit(event) }
        onNotificationPosted?.invoke(event)
    }

    private fun buildNotificationAlert(
        sbn: StatusBarNotification,
        extras: Bundle,
        pkg: String,
    ): IslandEvent.Notification? {
        val progress = progressOf(extras)
        val title = extras.getString("android.title")
        val text =
            extras.getCharSequence("android.bigText")?.toString()?.takeIf { it.isNotEmpty() }
                ?: extras.getString("android.text")
        val notif = sbn.notification
        val isMessagingStyle =
            notif != null && notif.isStyle(Notification.MessagingStyle::class.java)
        val groupKey = if (sbn.isGroup) sbn.groupKey else null

        val icon =
            try {
                context.packageManager.getApplicationIcon(pkg)
            } catch (_: Exception) {
                null
            }
        val appName =
            try {
                context.packageManager
                    .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
                    .toString()
            } catch (_: Exception) {
                pkg
            }

        val allNotifActions = sbn.notification?.actions ?: emptyArray()
        val actions =
            allNotifActions
                .filter { a -> a.remoteInputs.isNullOrEmpty() && a.actionIntent != null }
                .take(2)
                .mapNotNull { a ->
                    a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
                }
        val replyAction =
            allNotifActions
                .firstOrNull { a ->
                    !a.remoteInputs.isNullOrEmpty() && a.actionIntent != null
                }
                ?.let { a ->
                    val remoteInput = a.remoteInputs!!.first()
                    IslandEvent.ReplyAction(
                        label = a.title ?: remoteInput.label ?: "Reply",
                        action = a,
                        remoteInput = remoteInput,
                    )
                }

        var senderIcon: Drawable? = null
        var senderName: String? = null
        var latestMessageText: String? = null
        var isConversation = false
        var isGroupConversation = false
        var conversationTitle: String? = null
        if (notif != null && isMessagingStyle && notif.extras != null) {
            isConversation = true
            isGroupConversation =
                notif.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            conversationTitle =
                notif.extras
                    .getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                    ?.toString()
                    ?.takeIf { it.isNotEmpty() }
            val messagesArray =
                notif.extras.getParcelableArray(
                    Notification.EXTRA_MESSAGES,
                    Parcelable::class.java,
                )
            if (messagesArray != null && messagesArray.isNotEmpty()) {
                val messages =
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                        messagesArray
                    )
                val lastMessage = messages.maxByOrNull { it.timestamp }
                val sender = lastMessage?.senderPerson
                senderName = sender?.name?.toString()
                latestMessageText = lastMessage?.text?.toString()
                senderIcon =
                    try {
                        sender?.icon?.loadDrawable(context)
                    } catch (_: Exception) {
                        null
                    }
            }
            if (senderIcon == null) {
                senderIcon =
                    try {
                        notif.extras
                            .getParcelable(
                                Notification.EXTRA_CONVERSATION_ICON,
                                Icon::class.java,
                            )
                            ?.loadDrawable(context)
                    } catch (_: Exception) {
                        null
                    }
            }
            if (senderIcon == null) {
                senderIcon =
                    try {
                        notif.getLargeIcon()?.loadDrawable(context)
                    } catch (_: Exception) {
                        null
                    }
            }
        }

        return IslandEvent.Notification(
            sbn = sbn,
            title = title,
            text = latestMessageText ?: text,
            appIcon = icon,
            appName = appName,
            progress = if (progress.hasProgress && !progress.indeterminate) progress.raw else -1,
            progressMax = progress.max.coerceAtLeast(1),
            isProgressIndeterminate = progress.indeterminate,
            actions = actions,
            replyAction = replyAction,
            isConversation = isConversation,
            isGroupConversation = isGroupConversation,
            conversationTitle = conversationTitle,
            senderIcon = senderIcon,
            senderName = senderName,
            groupKey = groupKey,
            notificationImage = extractNotificationImage(extras, sbn),
        )
    }

    fun startListening() {
        if (listening) return
        listening = true
        ScrimUtils.get().addListener(scrimListener)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        ScrimUtils.get().removeListener(scrimListener)
        seenNotificationKeys.clear()
        seenMessagingTimestamps.clear()
        timerJob?.cancel()
        timerJob = null
        _timerEvent.value = null
        _stopwatchEvent.value = null
        _alarmEvent.value = null
        _callEvents.value = emptyList()
        _notificationEvents.value = emptyList()
        _promotedOngoingEvents.value = emptyList()
        _sportsEvents.value = emptyList()
        _nowPlayingEvent.value = null
        _audioRecordingEvent.value = null
        recorderPackage = null
        recorderNotifKey = null
        pauseStartMs = 0L
        accumulatedPauseMs = 0L
    }

    fun clearAudioRecording() {
        _audioRecordingEvent.value = null
        recorderPackage = null
        recorderNotifKey = null
        pauseStartMs = 0L
        accumulatedPauseMs = 0L
    }

    fun dismissNotification(event: IslandEvent.Notification) {
        _notificationEvents.value = _notificationEvents.value.filter { it.id != event.id }
    }

    fun coalesceNotification(event: IslandEvent.Notification) {
        val current = _notificationEvents.value.toMutableList()
        current.removeAll { it.id == event.id }
        current.add(0, event)
        _notificationEvents.value = current
    }

    fun clearTimer() {
        _timerEvent.value = null
        timerOriginalDurationMs = 0L
    }

    fun clearStopwatch() {
        _stopwatchEvent.value = null
    }

    fun clearAlarm() {
        _alarmEvent.value = null
    }

    fun clearCall(key: String) {
        _callEvents.value = _callEvents.value.filter { it.sbn.key != key }
    }

    private fun handleTimer(
        sbn: StatusBarNotification,
        extras: Bundle,
        actionLabels: List<String> = emptyList(),
        actionIntents: List<String> = emptyList(),
    ) {
        val label = extras.getString("android.title") ?: context.getString(R.string.ax_dynamic_bar_timer)
        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }

        val hasPauseIntent = actionIntents.any { it == DeskClockActions.PAUSE_TIMER }
        val hasStartIntent = actionIntents.any { it == DeskClockActions.START_TIMER }
        val isPaused = when {
            hasStartIntent -> true
            hasPauseIntent -> false
            else -> {
                val hasPauseLabel = actionLabels.any { it.contains("pause") }
                val hasResumeLabel = actionLabels.any {
                    it.contains("resume") || it.contains("play") ||
                        (it.contains("start") && !it.contains("stop"))
                }
                hasResumeLabel || (actionLabels.isNotEmpty() && !hasPauseLabel)
            }
        }

        var endTimeMs = sbn.notification.`when`
        if (endTimeMs <= System.currentTimeMillis()) {
            val chronoBase = extractChronometerBase(sbn)
            if (chronoBase > 0L) {
                val remaining = chronoBase - SystemClock.elapsedRealtime()
                endTimeMs = if (remaining > 0L) System.currentTimeMillis() + remaining else 0L
            } else {
                endTimeMs = 0L
            }
        }

        if (isPaused && endTimeMs > System.currentTimeMillis()) {
            val existing = _timerEvent.value
            if (existing != null && existing.endTimeMs > 0L) {
                endTimeMs = existing.endTimeMs
            }
        }

        val actions = extractNotificationActions(sbn)
        val isNewTimer = timerNotificationKey != sbn.key
        if (isNewTimer && endTimeMs > 0L) {
            timerOriginalDurationMs = (endTimeMs - System.currentTimeMillis()).coerceAtLeast(1000L)
        }
        val event =
            IslandEvent.Timer(
                label = label,
                endTimeMs = endTimeMs,
                originalDurationMs = timerOriginalDurationMs,
                appIcon = icon,
                isPaused = isPaused,
                actions = actions,
                contentIntent = sbn.notification?.contentIntent,
            )
        timerNotificationKey = sbn.key
        _timerEvent.value = event
        onTimerEvent?.invoke(event)

        timerJob?.cancel()
        if (endTimeMs > 0L && !isPaused) {
            val remainingMs = endTimeMs - System.currentTimeMillis()
            timerJob =
                applicationScope.launch {
                    delay((remainingMs + 3_000L).coerceAtLeast(3_000L))
                    _timerEvent.value = null
                }
        }
    }

    private fun handleStopwatch(
        sbn: StatusBarNotification,
        extras: Bundle,
        actionLabels: List<String> = emptyList(),
        actionIntents: List<String> = emptyList(),
    ) {
        val label = extras.getString("android.title") ?: ""
        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }

        val hasPauseIntent = actionIntents.any { it == DeskClockActions.PAUSE_STOPWATCH }
        val hasStartIntent = actionIntents.any { it == DeskClockActions.START_STOPWATCH }
        val hasLapIntent = actionIntents.any { it == DeskClockActions.LAP_STOPWATCH }
        val isRunning = when {
            hasPauseIntent || hasLapIntent -> true
            hasStartIntent -> false
            else -> actionLabels.any { it.contains("pause") || it.contains("lap") }
        }

        var startTimeMs = System.currentTimeMillis()
        val notifState = extractStopwatchState(sbn)
        if (notifState.chronometerBase > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - notifState.chronometerBase
            if (elapsed > 0L) startTimeMs = System.currentTimeMillis() - elapsed
        }

        val actions = extractNotificationActions(sbn)
        val event =
            IslandEvent.Stopwatch(
                label = label,
                startTimeMs = startTimeMs,
                isRunning = isRunning,
                appIcon = icon,
                actions = actions,
                lapNumber = notifState.lapNumber,
                contentIntent = sbn.notification?.contentIntent,
            )
        stopwatchNotificationKey = sbn.key
        _stopwatchEvent.value = event
    }

    private fun extractChronometerBase(sbn: StatusBarNotification): Long =
        inflateContentView(sbn) { findChronometer(it)?.base } ?: 0L

    private data class StopwatchNotifState(val chronometerBase: Long, val lapNumber: Int?)

    /** One inflation, both values — the stopwatch re-posts on every tick. */
    private fun extractStopwatchState(sbn: StatusBarNotification): StopwatchNotifState =
        inflateContentView(sbn) { inflated ->
            StopwatchNotifState(
                chronometerBase = findChronometer(inflated)?.base ?: 0L,
                lapNumber = readLapNumber(inflated, sbn.packageName),
            )
        } ?: StopwatchNotifState(0L, null)

    /**
     * The lap the stopwatch is on, straight off the notification.
     *
     * DeskClock writes it into the content view's `state` field and replaces it with paused text
     * while stopped, so reading a number here is also what makes the count vanish on pause and
     * return on resume — and it stays right when the lap was taken from the shade or the app,
     * which counting our own taps would not.
     */
    private fun readLapNumber(inflated: View, packageName: String): Int? {
        val id = try {
            context.packageManager
                .getResourcesForApplication(packageName)
                .getIdentifier("state", "id", packageName)
        } catch (_: Exception) { 0 }
        if (id == 0) return null
        val text = inflated.findViewById<TextView>(id)?.text?.toString() ?: return null
        return Regex("\\d+").find(text)?.value?.toIntOrNull()
    }

    private fun <T> inflateContentView(sbn: StatusBarNotification, read: (View) -> T?): T? {
        try {
            val rv = sbn.notification.contentView ?: sbn.notification.bigContentView ?: return null
            val pkgCtx = context.createPackageContext(sbn.packageName, Context.CONTEXT_RESTRICTED)
            val container = FrameLayout(context)
            val inflated = rv.apply(pkgCtx, container) ?: return null
            return read(inflated)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read content view from ${sbn.packageName}", e)
            return null
        }
    }

    private fun findChronometer(view: View): Chronometer? {
        if (view is Chronometer) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChronometer(view.getChildAt(i))?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun extractNotificationActions(
        sbn: StatusBarNotification
    ): List<IslandEvent.NotificationAction> {
        return sbn.notification
            ?.actions
            ?.filter { a -> a.remoteInputs.isNullOrEmpty() && a.actionIntent != null }
            ?.take(3)
            ?.mapNotNull { a ->
                a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
            } ?: emptyList()
    }

    private fun handleAlarm(sbn: StatusBarNotification, extras: Bundle) {
        val label = extras.getString("android.title") ?: context.getString(R.string.ax_dynamic_bar_alarm)
        val isRinging = sbn.notification.category == Notification.CATEGORY_ALARM
        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }
        val event =
            IslandEvent.Alarm(
                label = label,
                triggerTimeMs = sbn.notification.`when`,
                isRinging = isRinging,
                appIcon = icon,
            )
        _alarmEvent.value = event
        onAlarmEvent?.invoke(event)
    }

    private fun resolveAppName(packageName: String): String =
        try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            ""
        }

    private fun handleCallNotification(sbn: StatusBarNotification, extras: Bundle) {
        val callerName = extras.getString("android.title")
        val number = extras.getString("android.text")
        val callerPhoto =
            try {
                sbn.notification?.getLargeIcon()?.loadDrawable(context)
            } catch (_: Exception) {
                null
            }

        val allActions = sbn.notification?.actions ?: emptyArray()
        val actions =
            allActions
                .filter { a -> a.remoteInputs.isNullOrEmpty() && a.actionIntent != null }
                .take(3)
                .mapNotNull { a ->
                    a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
                }

        val androidCallType = extras.getInt(
            Notification.EXTRA_CALL_TYPE,
            Notification.CallStyle.CALL_TYPE_UNKNOWN,
        )
        val callType =
            if (androidCallType == Notification.CallStyle.CALL_TYPE_INCOMING)
                "Phone:incoming"
            else "Phone:active"

        val icon =
            try {
                context.packageManager.getApplicationIcon(sbn.packageName)
            } catch (_: Exception) {
                null
            }

        val callWhen = sbn.notification?.`when` ?: 0L
        val callStart = if (callWhen > 0L) callWhen else System.currentTimeMillis()

        val event =
            IslandEvent.Call(
                sbn = sbn,
                callerName = callerName,
                number = number,
                appIcon = icon,
                callerPhoto = callerPhoto,
                callType = callType,
                callStartTimeMs = callStart,
                actions = actions,
            )

        val current = _callEvents.value.toMutableList()
        current.removeAll { it.sbn.key == sbn.key }
        current.add(0, event)
        _callEvents.value = current
    }

    private fun actionIconResName(action: Notification.Action, pkg: String): String? {
        val icon = action.getIcon() ?: return null
        val resId = try { icon.resId } catch (_: Exception) { 0 }
        if (resId == 0) return null
        return try {
            context.packageManager.getResourcesForApplication(pkg).getResourceEntryName(resId)
        } catch (_: Exception) {
            null
        }
    }

    private fun actionIntentString(action: Notification.Action): String? {
        val pi = action.actionIntent ?: return null
        return try {
            pi.intent?.action
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRecorderElapsedMs(extras: Bundle): Long? {
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return null
        val match = Regex("""(\d+):(\d+)(?::(\d+))?""").find(text) ?: return null
        val a = match.groupValues[1].toLongOrNull() ?: return null
        val b = match.groupValues[2].toLongOrNull() ?: return null
        val c = match.groupValues[3].toLongOrNull()
        val secs = if (c != null) a * 3600 + b * 60 + c else a * 60 + b
        return secs * 1000L
    }

    private enum class MaterialIconSet(val patterns: List<String>) {
        Play(listOf("play_arrow", "media_play", "play_circle", "play")),
        Pause(listOf("pause_circle", "media_pause", "pause")),
        Stop(listOf("stop_circle", "media_stop", "stop")),
    }

    private object DeskClockActions {
        private const val PREFIX = "com.android.deskclock.action."
        const val START_TIMER = PREFIX + "START_TIMER"
        const val PAUSE_TIMER = PREFIX + "PAUSE_TIMER"
        const val RESET_TIMER = PREFIX + "RESET_TIMER"
        const val ADD_MINUTE_TIMER = PREFIX + "ADD_MINUTE_TIMER"
        const val SHOW_TIMER = PREFIX + "SHOW_TIMER"
        const val START_STOPWATCH = PREFIX + "START_STOPWATCH"
        const val PAUSE_STOPWATCH = PREFIX + "PAUSE_STOPWATCH"
        const val RESET_STOPWATCH = PREFIX + "RESET_STOPWATCH"
        const val LAP_STOPWATCH = PREFIX + "LAP_STOPWATCH"
        const val SHOW_STOPWATCH = PREFIX + "SHOW_STOPWATCH"
        const val ALARM_SNOOZE = "com.android.deskclock.ALARM_SNOOZE"
        const val ALARM_DISMISS = "com.android.deskclock.ALARM_DISMISS"
    }

    private fun String.matchesMaterial(set: MaterialIconSet): Boolean =
        set.patterns.any { this.contains(it) }

    private fun isPromotable(sbn: StatusBarNotification, extras: Bundle): Boolean {
        val notification = sbn.notification ?: return false

        if (notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0) return true

        return notification.hasPromotableCharacteristics()
    }

    private fun handlePromotedOngoing(sbn: StatusBarNotification, extras: Bundle, pkg: String) {
        val shortCritical =
            try {
                sbn.notification?.shortCriticalText?.toString() ?: ""
            } catch (_: Exception) {
                extras.getString("android.shortCriticalText") ?: ""
            }
        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""
        val appName = resolveAppName(pkg)
        val icon = loadNotificationIcon(sbn, pkg)

        val actions = extractNotificationActions(sbn)

        val progressRaw = extras.getInt("android.progress", -1)
        val progressMax = extras.getInt("android.progressMax", 0)
        val indeterminate = extras.getBoolean("android.progressIndeterminate", false)
        val progress =
            if (progressRaw >= 0 && progressMax > 0)
                (progressRaw.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f)
            else -1f

        val event =
            IslandEvent.PromotedOngoing(
                shortText = shortCritical,
                title = title,
                text = text,
                appName = appName,
                appIcon = icon,
                sbn = sbn,
                actions = actions,
                progress = progress,
                isIndeterminate = indeterminate,
            )

        val current = _promotedOngoingEvents.value.toMutableList()
        current.removeAll { it.sbn.key == sbn.key }
        current.add(0, event)
        _promotedOngoingEvents.value = current
    }

    fun clearPromotedOngoing(key: String) {
        _promotedOngoingEvents.value = _promotedOngoingEvents.value.filter { it.sbn.key != key }
    }

    fun clearSportsEvent(key: String) {
        _sportsEvents.value = _sportsEvents.value.filter { it.key != key }
    }

    fun clearNowPlaying() {
        _nowPlayingEvent.value = null
    }

    private fun handleNowPlaying(sbn: StatusBarNotification, extras: Bundle) {
        val title = extras.getCharSequence("android.title")?.toString() ?: return
        val byMatch = Regex("""(.+?)\s+by\s+(.+)""", RegexOption.IGNORE_CASE).find(title)
        val dashParts = if (byMatch == null) title.split(" - ", " – ", limit = 2) else null
        val songTitle: String
        val artist: String
        when {
            byMatch != null -> {
                songTitle = byMatch.groupValues[1].trim()
                artist = byMatch.groupValues[2].trim()
            }
            dashParts != null && dashParts.size == 2 -> {
                songTitle = dashParts[0].trim()
                artist = dashParts[1].trim()
            }
            else -> {
                songTitle = title
                artist = ""
            }
        }

        val allActions = sbn.notification?.actions ?: emptyArray()
        val notifActions = allActions.mapNotNull { a ->
            a.title?.let { IslandEvent.NotificationAction(label = it, action = a) }
        }
        val appIcon = loadNotificationIcon(sbn, sbn.packageName)

        _nowPlayingEvent.value = IslandEvent.NowPlaying(
            songTitle = songTitle,
            artist = artist,
            key = sbn.key,
            sbn = sbn,
            appIcon = appIcon,
            actions = notifActions,
        )
    }

    private fun handleSportsScore(
        sbn: StatusBarNotification,
        extras: Bundle,
        forceCapture: Boolean = false,
    ): Boolean {
        val title = extras.getCharSequence("android.title")?.toString() ?: return false
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        val allFields = listOf(title.trim(), text.trim())
        val scoreMatch = allFields.firstNotNullOfOrNull { SCORE_PATTERN.find(it) }

        var team1Name: String
        var team2Name: String
        var score1 = ""
        var score2 = ""

        if (scoreMatch != null) {
            team1Name = scoreMatch.groupValues[1].trim()
            score1 = scoreMatch.groupValues[2]
            score2 = scoreMatch.groupValues[3]
            team2Name = scoreMatch.groupValues[4].trim()
                .replace(Regex("""\s*[·•|].*"""), "")
        } else {
            val combined = "$title $text"
            val vsMatch = VS_PATTERN.find(combined)
            if (vsMatch != null) {
                team1Name = vsMatch.groupValues[1].trim()
                    .replace(Regex("""^.*[·•|]\s*"""), "")
                team2Name = vsMatch.groupValues[2].trim()
                    .replace(Regex("""\s*[·•|].*$"""), "")
            } else if (forceCapture) {
                val parts = text.split("·", "•", "|", " - ", " – ")
                    .map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    team1Name = parts[0]
                    team2Name = parts[1]
                } else {
                    team1Name = text.ifEmpty { title }
                    team2Name = ""
                }
            } else {
                return false
            }
        }

        val isOngoing = sbn.isOngoing
        val status = when {
            score1.isNotEmpty() && !isOngoing -> IslandEvent.GameStatus.FINAL
            score1.isNotEmpty() && isOngoing -> IslandEvent.GameStatus.LIVE
            isOngoing -> IslandEvent.GameStatus.PRE_GAME
            else -> IslandEvent.GameStatus.FINAL
        }

        var statusDetail = ""
        if (score1.isNotEmpty()) {
            val shortCritical = try {
                sbn.notification?.shortCriticalText?.toString() ?: ""
            } catch (_: Exception) {
                extras.getString("android.shortCriticalText") ?: ""
            }
            statusDetail = shortCritical
                .replace(score1, "").replace(score2, "")
                .replace("-", "").replace("–", "").replace(":", "")
                .trim()
        }

        val allText = "$title · $text"
        val league = allText.split("·", "•", "|")
            .map { it.trim() }
            .firstOrNull { part ->
                part.length in 2..30 &&
                    !part.any { it.isDigit() } &&
                    part != team1Name && part != team2Name
            } ?: ""

        val commentary = extras.getCharSequence("android.bigText")?.toString()
            ?: extras.getString("android.subText") ?: ""

        val appIcon = loadNotificationIcon(sbn, sbn.packageName)

        val event = IslandEvent.Sports(
            team1Name = team1Name,
            team2Name = team2Name,
            score1 = score1,
            score2 = score2,
            status = status,
            statusDetail = statusDetail,
            league = league,
            commentary = commentary,
            key = sbn.key,
            sbn = sbn,
            appIcon = appIcon,
        )

        val current = _sportsEvents.value.toMutableList()
        current.removeAll { it.key == sbn.key }
        current.add(0, event)
        _sportsEvents.value = current
        return true
    }

    private fun extractNotificationImage(extras: Bundle, sbn: StatusBarNotification): Drawable? {
        try {
            extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
                ?.loadDrawable(context)?.let { return it }
        } catch (_: Exception) {}

        try {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
                ?.let { return BitmapDrawable(context.resources, it) }
        } catch (_: Exception) {}

        if (!sbn.notification.isStyle(Notification.MessagingStyle::class.java)) {
            try {
                sbn.notification?.getLargeIcon()?.loadDrawable(context)?.let { return it }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun loadNotificationIcon(sbn: StatusBarNotification, pkg: String): Drawable? {
        return try {
            sbn.notification?.smallIcon?.loadDrawable(context)
        } catch (_: Exception) {
            null
        }
            ?: try {
                context.packageManager.getApplicationIcon(pkg)
            } catch (_: Exception) {
                null
            }
    }
}

