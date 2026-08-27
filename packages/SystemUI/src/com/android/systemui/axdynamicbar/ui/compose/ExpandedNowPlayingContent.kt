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

package com.android.systemui.axdynamicbar.ui.compose

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.ActionChip
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.shared.ActionBg
import com.android.systemui.axdynamicbar.shared.OnActionText
import com.android.systemui.axdynamicbar.shared.OnCardSecondary
import com.android.systemui.axdynamicbar.shared.OnCardText
import com.android.systemui.axdynamicbar.shared.rememberIslandColors
import com.android.systemui.axdynamicbar.shared.ShapeAlbum
import com.android.systemui.axdynamicbar.shared.ShapeChip
import com.android.systemui.axdynamicbar.shared.ShapeLg
import com.android.systemui.axdynamicbar.shared.SpaceLg
import com.android.systemui.axdynamicbar.shared.SpaceMd
import com.android.systemui.axdynamicbar.shared.SpaceXs
import com.android.systemui.axdynamicbar.shared.sendWithBal
import com.android.systemui.axdynamicbar.shared.toScaledBitmap
import com.android.systemui.alpha.theme.AlphaColors
import com.android.systemui.res.R
import java.net.URLEncoder
import java.util.Date

@Composable
internal fun NowPlayingExpanded(event: IslandEvent.NowPlaying, interactor: IslandActions) {
    // Follows the mapper rather than naming a colour here, so the event's accent is set in one
    // place. Contrast-adjusted on light themes, which the tinted label below depends on.
    val accent = rememberIslandColors(event).accent
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeLg)
                .background(AlphaColors.DbStackCard.sectionSurface(accent))
                .padding(SpaceLg),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
        ) {
            NowPlayingCover(event, accent)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXs),
            ) {
                NowPlayingBadge(accent)
                Text(
                    event.songTitle,
                    color = OnCardText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.artist.isNotEmpty()) {
                    Text(
                        event.artist,
                        color = OnCardSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FavoriteHeart(event, accent)
        }
        if (event.actions.isNotEmpty()) {
            val context = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                event.actions.forEach { notifAction ->
                    ActionChip(
                        label = notifAction.label.toString(),
                        color = OnActionText,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            try {
                                notifAction.action.actionIntent?.sendWithBal(context)
                            } catch (_: Exception) {}
                            interactor.collapseIsland()
                        },
                    )
                }
            }
        } else {
            NowPlayingFooter(event, interactor)
        }
    }
}

/** 92dp so the art is the card's anchor; the note keeps the same footprint when none resolved. */
@Composable
private fun NowPlayingCover(event: IslandEvent.NowPlaying, accent: Color) {
    val art = event.albumArt
    if (art != null) {
        Image(
            bitmap = art.toScaledBitmap(CoverSize),
            contentDescription = null,
            modifier = Modifier.size(CoverSize).clip(ShapeAlbum),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = Modifier
                .size(CoverSize)
                .clip(ShapeAlbum)
                // Stacks on the header's own accent wash; the section surface alone would
                // vanish into the row behind it.
                .background(accent.copy(alpha = BadgeAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                null,
                tint = accent,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun NowPlayingBadge(accent: Color) {
    Surface(shape = ShapeChip, color = accent.copy(alpha = BadgeAlpha)) {
        Text(
            stringResource(R.string.ax_dynamic_bar_now_playing).uppercase(),
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.09.em,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = SpaceMd, vertical = 3.dp),
        )
    }
}

/**
 * Info / History / Play, plus the favourite toggle.
 *
 * Every launch goes through [IslandActions.launchDismissingKeyguard] rather than sending the
 * intent directly: on the keyguard the target parks on the bouncer, and a card still sitting on
 * top of it would swallow the tap. Favouriting is not a launch — it stays on the card.
 */
@Composable
private fun NowPlayingFooter(event: IslandEvent.NowPlaying, interactor: IslandActions) {
    val context = LocalContext.current
    val playIntent = remember(event.songTitle, event.artist) {
        resolveSearchIntent(context, event.songTitle, event.artist)
    }
    val infoIntent = remember(event.songTitle, event.artist) {
        songInfoIntent(context, event.songTitle, event.artist)
    }
    Row(
        // Starts where the badge and title do: past the header's own padding and the cover.
        modifier = Modifier.fillMaxWidth().padding(start = SpaceLg + CoverSize + SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        // Nothing expires a match, so the card can outlive the music. Saying when it was heard
        // turns a stale title into a true one. Short and bounded even translated, which is what
        // leaves the rest of the row for the controls.
        Text(
            if (event.recognizedAtMillis > 0L) {
                stringResource(
                    R.string.ax_dynamic_bar_now_playing_heard_at,
                    DateFormat.getTimeFormat(context).format(Date(event.recognizedAtMillis)),
                )
            } else {
                ""
            },
            color = OnCardSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        infoIntent?.let { info ->
            FooterButton(
                ImageVector.vectorResource(R.drawable.ic_ax_info),
                R.string.ax_dynamic_bar_now_playing_info,
            ) {
                activityPendingIntent(context, info)?.let {
                    interactor.launchDismissingKeyguard(it)
                    interactor.collapseIsland()
                }
            }
        }
        FooterButton(Icons.Filled.History, R.string.ax_dynamic_bar_now_playing_history) {
            // ASI's own OPEN_INTENT targets HistoryActivity and carries the song id, so it lands
            // on this track rather than the top of the list. The bare action is the fallback for
            // a match that arrived without one.
            val pi = event.openIntent
                ?: activityPendingIntent(context, Intent(ACTION_NOW_PLAYING_HISTORY))
            pi?.let {
                interactor.launchDismissingKeyguard(it)
                interactor.collapseIsland()
            }
        }
        playIntent?.let { play ->
            FooterButton(Icons.Filled.PlayArrow, R.string.ax_dynamic_bar_now_playing_play) {
                activityPendingIntent(context, play)?.let {
                    interactor.launchDismissingKeyguard(it)
                    interactor.collapseIsland()
                }
            }
        }
    }
}

@Composable
private fun FooterButton(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ShapeChip,
        color = ActionBg,
        modifier = Modifier.size(FooterButtonSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = stringResource(labelRes),
                tint = OnActionText,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * Icon only, matching the heart in ASI's own History list.
 *
 * ASI does not re-announce an unchanged song, so waiting for the next SHOW's ICON_OVERRIDE would
 * leave the heart looking dead. Flip locally and let that SHOW reconcile it.
 */
@Composable
private fun FavoriteHeart(event: IslandEvent.NowPlaying, accent: Color) {
    val fav = event.favoritingIntent ?: return
    val context = LocalContext.current
    var favorited by remember(event.songTitle, event.artist) { mutableStateOf(event.isFavorite) }
    Icon(
        if (favorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = null,
        tint = if (favorited) accent else OnCardSecondary,
        modifier = Modifier
            .size(20.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                try {
                    fav.sendWithBal(context)
                    favorited = !favorited
                } catch (_: Exception) {}
            },
    )
}

/**
 * A search deep link the installed player claims, or null when nothing answers it.
 *
 * Preferred packages come from an overlay so a maintainer shipping a player can point at it
 * without a code change; the first installed one that resolves gets the launch outright, and
 * with none configured the user's own default (or a chooser) handles it.
 */
private fun resolveSearchIntent(context: Context, title: String, artist: String): Intent? {
    if (title.isBlank()) return null
    val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    val uri = try {
        Uri.parse(SEARCH_URL + URLEncoder.encode(query, "UTF-8"))
    } catch (_: Exception) {
        return null
    }
    val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pm = context.packageManager
    val preferred = context.resources.getStringArray(R.array.config_nowPlayingSearchPackages)
    for (pkg in preferred) {
        val scoped = Intent(base).setPackage(pkg)
        if (pm.resolveActivity(scoped, PackageManager.MATCH_DEFAULT_ONLY) != null) return scoped
    }
    if (pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) return null
    return base
}

/**
 * The result page Google's own sound search lands on: the song card with album, artist and
 * "Behind the music", not a plain web search. `aus_src=aga.sound` is what selects it.
 *
 * Pinned to the Google app when it is there — a browser also claims the URL and would otherwise
 * raise a chooser, or open the page without the card.
 */
private fun songInfoIntent(context: Context, title: String, artist: String): Intent? {
    if (title.isBlank()) return null
    val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
    val locale = context.resources.configuration.locales[0]?.toLanguageTag() ?: "en"
    val uri = try {
        Uri.parse(
            INFO_URL +
                "?aus_src=aga.sound&cs=1&hl=" + URLEncoder.encode(locale, "UTF-8") +
                "&q=" + URLEncoder.encode(query, "UTF-8")
        )
    } catch (_: Exception) {
        return null
    }
    val base = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pm = context.packageManager
    val pinned = Intent(base).setPackage(PKG_GOOGLE_APP)
    if (pm.resolveActivity(pinned, PackageManager.MATCH_DEFAULT_ONLY) != null) return pinned
    if (pm.queryIntentActivities(base, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) return null
    return base
}

private fun activityPendingIntent(context: Context, intent: Intent): PendingIntent? =
    try {
        PendingIntent.getActivity(
            context,
            0,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    } catch (_: Exception) {
        null
    }

private val CoverSize = 92.dp
private val FooterButtonSize = 38.dp
/** The badge is a wash of the accent, not a fixed luminance: the card follows the theme. */
private const val BadgeAlpha = 0.18f

private const val ACTION_NOW_PLAYING_HISTORY =
    "com.google.intelligence.sense.NOW_PLAYING_HISTORY"
private const val SEARCH_URL = "https://music.youtube.com/search?q="
private const val INFO_URL = "https://www.google.com/gasearch"
private const val PKG_GOOGLE_APP = "com.google.android.googlequicksearchbox"

