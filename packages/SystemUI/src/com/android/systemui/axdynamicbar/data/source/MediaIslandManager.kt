package com.android.systemui.axdynamicbar.data.source

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.toArgb
import com.android.internal.logging.InstanceId
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.shared.model.MediaAction
import com.android.systemui.media.controls.shared.model.MediaButton
import com.android.systemui.media.dialog.MediaOutputDialogManager
import com.android.systemui.media.remedia.data.model.MediaDataModel
import com.android.systemui.media.remedia.data.repository.MediaRepositoryImpl
import com.android.systemui.media.remedia.shared.model.MediaSessionState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Produces [IslandEvent.Media] from the remedia session list, so every Dynamic Bar media surface
 * shows and controls the same session as Ax QS.
 *
 * The island UI contract is unchanged — this is a producer swap, not a redesign. Metadata, art,
 * accent, position and transport all come from [MediaDataModel]; nothing here binds
 * `NotificationMediaManager` or the platform `MediaSessionManager` any more.
 */
@SysUISingleton
class MediaIslandManager
@Inject
constructor(
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
    @Main private val mainHandler: Handler,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val mediaRepository: MediaRepositoryImpl,
    private val mediaOutputDialogManager: MediaOutputDialogManager,
) {
    companion object {
        private const val TAG = "MediaIslandManager"
        private const val CUSTOM_ACTION_0 = "ax_media_custom_0"
        private const val CUSTOM_ACTION_1 = "ax_media_custom_1"
    }

    private val _mediaEvent = MutableStateFlow<IslandEvent.Media?>(null)
    val mediaEvent: StateFlow<IslandEvent.Media?> = _mediaEvent.asStateFlow()

    /** Package of the session on the island, so its own notifications do not alert twice. */
    var activeMediaPackage: String? = null
        private set

    private var listening = false
    private var collectJob: Job? = null

    /**
     * Session the user dismissed from the island. remedia keeps publishing it, so without this the
     * next emission (≤500 ms while playing) would put the chip straight back.
     */
    @Volatile private var dismissed: DismissToken? = null

    /** Off while the island is hidden or covered: position-only changes stop re-emitting. */
    @Volatile private var progressUpdatesEnabled = true

    /** Transport targets whatever the island is currently showing. */
    @Volatile private var primaryKey: InstanceId? = null
    @Volatile private var transport: MediaButton? = null
    @Volatile private var clickIntent: PendingIntent? = null

    fun startListening() {
        if (listening) return
        listening = true
        collectJob =
            applicationScope.launch(context = backgroundDispatcher) {
                snapshotFlow { selectPrimary() }.collect(::onPrimaryChanged)
            }
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        collectJob?.cancel()
        collectJob = null
        _mediaEvent.value = null
        activeMediaPackage = null
        primaryKey = null
        transport = null
        clickIntent = null
        dismissed = null
    }

    /**
     * Island-only dismiss: hides the chip without touching the remedia session, matching the old
     * behaviour where [clear] dropped the event and nothing else.
     */
    fun clear() {
        val event = _mediaEvent.value
        dismissed = event?.let { DismissToken(primaryKey, it.track, it.artist) }
        _mediaEvent.value = null
        activeMediaPackage = null
    }

    /** Position updates are pointless while nothing renders them; text/state changes still emit. */
    fun setProgressUpdatesEnabled(enabled: Boolean) {
        progressUpdatesEnabled = enabled
    }

    private fun onPrimaryChanged(model: MediaDataModel?) {
        if (model == null) {
            primaryKey = null
            transport = null
            clickIntent = null
            dismissed = null
            activeMediaPackage = null
            _mediaEvent.value = null
            return
        }

        val event = model.toIslandMedia()

        dismissed?.let { token ->
            if (token.matches(model.instanceId, event.track, event.artist)) return
            dismissed = null
        }

        val current = _mediaEvent.value
        if (!progressUpdatesEnabled && current != null && current.isSameExceptProgress(event)) {
            return
        }

        primaryKey = model.instanceId
        transport = model.playbackStateActions
        clickIntent = model.clickIntent
        activeMediaPackage = model.packageName.takeIf { event.isPlaying }
        _mediaEvent.value = event
    }

    /**
     * Same order the hub uses ([com.android.systemui.media.MediaSessionManager]), so the chip,
     * Pulse and the lockscreen art never disagree about which session is current: a playing card
     * the user selected wins, then any playing card, then the selection, then the first displayable
     * one. Resume entries never own the island.
     */
    private fun selectPrimary(): MediaDataModel? {
        val sessions = mediaRepository.currentMedia
        val selected = sessions.getOrNull(mediaRepository.currentCarouselIndex)
        return selected?.takeIf { it.isDisplayable() && it.isPlaying() }
            ?: sessions.firstOrNull { it.isDisplayable() && it.isPlaying() }
            ?: selected?.takeIf { it.isDisplayable() }
            ?: sessions.firstOrNull { it.isDisplayable() }
    }

    private fun MediaDataModel.isPlaying(): Boolean = state == MediaSessionState.Playing

    private fun MediaDataModel.isDisplayable(): Boolean =
        isActive && !isResume && (title.isNotBlank() || isPlaying())

    private fun MediaDataModel.toIslandMedia(): IslandEvent.Media {
        val playing = isPlaying()
        val duration = durationMs.coerceAtLeast(0L)
        val position = positionMs.coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
        val progress = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        return IslandEvent.Media(
            track = title,
            artist = subtitle,
            isPlaying = playing,
            albumArt = background?.toDrawable(),
            progress = progress,
            duration = duration,
            position = position,
            outputDeviceName = outputDevice?.name?.toString().orEmpty(),
            customActions = customActionsOf(playbackStateActions),
            appIcon = appIcon.toDrawable(),
            packageName = packageName,
            mediaColor = colorScheme?.primary?.toArgb() ?: 0,
        )
    }

    private fun customActionsOf(actions: MediaButton?): List<IslandEvent.MediaCustomAction> {
        if (actions == null) return emptyList()
        return listOfNotNull(
            actions.custom0?.toCustomAction(CUSTOM_ACTION_0),
            actions.custom1?.toCustomAction(CUSTOM_ACTION_1),
        )
    }

    private fun MediaAction.toCustomAction(id: String): IslandEvent.MediaCustomAction? {
        if (action == null) return null
        val label = contentDescription?.toString()?.takeIf { it.isNotEmpty() } ?: return null
        return IslandEvent.MediaCustomAction(label = label, action = id, icon = icon)
    }

    private fun Icon.toDrawable(): Drawable? =
        when (this) {
            is Icon.Loaded -> drawable
            is Icon.Resource -> context.getDrawable(resId)
        }

    /** Everything the island renders apart from the moving parts of the seek bar. */
    private fun IslandEvent.Media.isSameExceptProgress(other: IslandEvent.Media): Boolean =
        track == other.track &&
            artist == other.artist &&
            isPlaying == other.isPlaying &&
            albumArt === other.albumArt &&
            appIcon === other.appIcon &&
            packageName == other.packageName &&
            mediaColor == other.mediaColor &&
            outputDeviceName == other.outputDeviceName &&
            duration == other.duration &&
            customActions == other.customActions

    fun togglePlayPause() {
        val playing = _mediaEvent.value?.isPlaying ?: return
        val action = transport?.playOrPause?.action ?: return
        action.run()
        // Optimistic flip so the button reacts before remedia republishes the session.
        _mediaEvent.value = _mediaEvent.value?.copy(isPlaying = !playing)
    }

    fun skipNext() {
        transport?.nextOrCustom?.action?.run()
    }

    fun skipPrev() {
        transport?.prevOrCustom?.action?.run()
    }

    fun seekTo(position: Long) {
        val key = primaryKey ?: return
        mediaRepository.seek(key, position)
    }

    fun sendCustomAction(action: String) {
        val button = transport ?: return
        when (action) {
            CUSTOM_ACTION_0 -> button.custom0?.action?.run()
            CUSTOM_ACTION_1 -> button.custom1?.action?.run()
            else -> Unit
        }
    }

    fun openMediaApp() {
        clickIntent?.let { intent ->
            try {
                intent.send()
                return
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "Media click intent cancelled", e)
            }
        }
        val pkg = _mediaEvent.value?.packageName?.takeIf { it.isNotEmpty() } ?: return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open media app: $pkg", e)
        }
    }

    fun openMediaOutputSwitcher() {
        val pkg = _mediaEvent.value?.packageName?.takeIf { it.isNotEmpty() } ?: return
        mainHandler.post {
            try {
                mediaOutputDialogManager.createAndShow(packageName = pkg, aboveStatusBar = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to open media output switcher", e)
            }
        }
    }

    private data class DismissToken(
        val key: InstanceId?,
        val track: String,
        val artist: String,
    ) {
        /** Released when the session changes, or when the same session moves to another track. */
        fun matches(otherKey: InstanceId?, otherTrack: String, otherArtist: String): Boolean =
            key == otherKey && track == otherTrack && artist == otherArtist
    }
}
