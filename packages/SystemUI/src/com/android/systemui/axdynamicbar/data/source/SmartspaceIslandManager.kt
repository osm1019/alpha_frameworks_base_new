/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.axdynamicbar.data.source

import android.app.PendingIntent
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.android.axion.quicklook.SportsData
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.quicklook.QuickLookClient
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SysUISingleton
class SmartspaceIslandManager
@Inject
constructor(
    private val quickLookClient: QuickLookClient,
    @Application private val context: Context,
    @Application private val applicationScope: CoroutineScope,
) {
    private val _sportsEvents = MutableStateFlow<List<IslandEvent.Sports>>(emptyList())
    val sportsEvents: StateFlow<List<IslandEvent.Sports>> = _sportsEvents.asStateFlow()

    private val _nowPlayingEvent = MutableStateFlow<IslandEvent.NowPlaying?>(null)
    val nowPlayingEvent: StateFlow<IslandEvent.NowPlaying?> = _nowPlayingEvent.asStateFlow()

    private var listening = false
    private var artJob: Job? = null
    private var artGeneration = 0

    private val callback = object : QuickLookClient.Callback {
        override fun onSportsUpdate(sports: List<SportsData>) {
            _sportsEvents.value = sports.mapIndexed { index, data ->
                val status = parseGameStatus(data.status, data.statusDetail)
                IslandEvent.Sports(
                    team1Name = data.team1Name,
                    team2Name = data.team2Name,
                    score1 = data.score1,
                    score2 = data.score2,
                    team1Icon = bytesToDrawable(data.team1IconBytes),
                    team2Icon = bytesToDrawable(data.team2IconBytes),
                    status = status,
                    statusDetail = data.statusDetail,
                    league = data.league,
                    key = "ql_sports_$index",
                )
            }
        }

        override fun onNowPlayingUpdate(
            text: String,
            artistName: String?,
            tapAction: PendingIntent?,
            albumArtUri: String?,
            status: String?,
            favoritingIntent: PendingIntent?,
            isFavorite: Boolean,
        ) {
            if (text.isBlank()) {
                artJob?.cancel()
                artGeneration++
                _nowPlayingEvent.value = null
                return
            }
            val songTitle: String
            val artist: String
            if (!artistName.isNullOrBlank()) {
                songTitle = text
                artist = artistName
            } else {
                val byMatch =
                    Regex("""(.+?)\s+by\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
                val dashParts =
                    if (byMatch == null) text.split(" - ", " – ", limit = 2) else null
                val bulletIdx = text.indexOf(" • ")
                when {
                    byMatch != null -> {
                        songTitle = byMatch.groupValues[1].trim()
                        artist = byMatch.groupValues[2].trim()
                    }
                    dashParts != null && dashParts.size == 2 -> {
                        songTitle = dashParts[0].trim()
                        artist = dashParts[1].trim()
                    }
                    bulletIdx > 0 -> {
                        songTitle = text.substring(0, bulletIdx).trim()
                        artist = text.substring(bulletIdx + 3).trim()
                    }
                    else -> {
                        songTitle = text
                        artist = ""
                    }
                }
            }
            val previous = _nowPlayingEvent.value
            val sameSong = previous != null &&
                previous.songTitle == songTitle &&
                previous.artist == artist
            val keepArt = if (sameSong) previous?.albumArt else null
            // ASI re-SHOWs an unchanged song about every two minutes. Stamping each one would
            // walk the time forward and hide exactly the staleness it is there to show.
            val recognizedAt =
                if (sameSong && previous != null && previous.recognizedAtMillis > 0L) {
                    previous.recognizedAtMillis
                } else {
                    System.currentTimeMillis()
                }
            _nowPlayingEvent.value = IslandEvent.NowPlaying(
                songTitle = songTitle,
                artist = artist,
                key = "ql_now_playing",
                albumArt = keepArt,
                openIntent = tapAction,
                favoritingIntent = favoritingIntent,
                isFavorite = isFavorite,
                recognizedAtMillis = recognizedAt,
            )
            // Same-song re-SHOW with no new URI: keep the cover already resolved.
            if (keepArt != null && albumArtUri.isNullOrBlank()) {
                Log.d(TAG, "art: keeping resolved cover for $songTitle")
                return
            }
            val generation = ++artGeneration
            artJob?.cancel()
            artJob = applicationScope.launch {
                val art = withContext(Dispatchers.IO) {
                    NowPlayingAlbumArt.load(context, albumArtUri, songTitle, artist)
                }
                if (generation != artGeneration) {
                    Log.d(TAG, "art: superseded for $songTitle ($generation/$artGeneration)")
                    return@launch
                }
                val current = _nowPlayingEvent.value
                if (current == null) {
                    Log.d(TAG, "art: no event left for $songTitle")
                    return@launch
                }
                if (current.songTitle != songTitle || current.artist != artist) {
                    Log.d(TAG, "art: event moved on, dropping cover for $songTitle " +
                            "(now '${current.songTitle}'/'${current.artist}')")
                    return@launch
                }
                if (art == null) {
                    Log.d(TAG, "art: no cover resolved for $songTitle")
                    return@launch
                }
                Log.i(TAG, "art: applied cover for $songTitle")
                _nowPlayingEvent.value = current.copy(albumArt = art)
            }
        }
    }

    fun startListening() {
        if (listening) return
        listening = true
        quickLookClient.addCallback(callback)
    }

    fun stopListening() {
        if (!listening) return
        listening = false
        artJob?.cancel()
        artGeneration++
        quickLookClient.removeCallback(callback)
        _sportsEvents.value = emptyList()
        _nowPlayingEvent.value = null
    }

    fun clearSportsEvent(key: String) {
        _sportsEvents.value = _sportsEvents.value.filter { it.key != key }
    }

    private fun parseGameStatus(status: String, detail: String): IslandEvent.GameStatus {
        val combined = "$status $detail".lowercase()
        return when {
            combined.contains("final") -> IslandEvent.GameStatus.FINAL
            combined.contains("half") -> IslandEvent.GameStatus.HALFTIME
            combined.contains("live") || combined.contains("q") ||
                combined.contains("inning") || combined.contains("set") ||
                combined.contains("period") -> IslandEvent.GameStatus.LIVE
            combined.contains("pre") || combined.contains("upcoming") ||
                combined.contains("tip") || combined.contains("kick") -> IslandEvent.GameStatus.PRE_GAME
            else -> IslandEvent.GameStatus.LIVE
        }
    }

    private fun bytesToDrawable(bytes: ByteArray?): Drawable? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            BitmapDrawable(null, bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "SmartspaceIslandManager"
    }
}
