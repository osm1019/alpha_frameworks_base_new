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

package com.android.systemui.axdynamicbar.ui.compose

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import com.android.axion.quicklook.SportsShape
import com.android.compose.modifiers.thenIf
import com.android.systemui.axdynamicbar.shared.IslandActions
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.res.R
import com.android.systemui.alpha.theme.AlphaColors

@Composable
internal fun SportsExpanded(event: IslandEvent.Sports, interactor: IslandActions) {
    val accent = accentColorFor(event)

    // Gate: only render the 3-column scoreboard layout when teams actually look like teams
    // and scores look like scores. Notification-sourced sports (ESPN/theScore) and some
    // Smartspace shapes (live-stats snapshots, player matchups) fail this check; without
    // it we end up with "54 min, 2 shots …" / VS / "See more st…" which is the bug this
    // guard is eliminating. Everything else falls through to a NowPlaying-style generic
    // card that (1) never shows garbage columns and (2) is visually consistent with the
    // rest of the DB stack.
    if (SportsShape.isScoreboard(event.team1Name, event.team2Name, event.score1, event.score2)) {
        ScoreboardSportsLayout(event, interactor, accent)
    } else {
        GenericSportsFallback(event, interactor, accent)
    }
}

/**
 * The existing 3-column scoreboard layout. Only reachable when teams/scores validated clean.
 * Kept structurally close to the original so real scoreboards keep working; only polish
 * applied (tap-to-open via contentIntent when available).
 */
@Composable
private fun ScoreboardSportsLayout(
    event: IslandEvent.Sports,
    interactor: IslandActions,
    accent: Color,
) {
    val context = LocalContext.current
    val tapIntent = event.sbn?.notification?.contentIntent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .thenIf(tapIntent != null) {
                Modifier.clickable {
                    try { tapIntent?.sendWithBal(context) } catch (_: Exception) {}
                    interactor.collapseIsland()
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        if (event.league.isNotEmpty()) {
            Text(
                event.league,
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        StatusBadge(event.status, accent)

        if (event.team2Name.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeamColumn(event.team1Name, event.team1Icon?.toScaledBitmap(48.dp))
                if (event.score1.isNotEmpty() && event.score2.isNotEmpty()) {
                    ScoreDisplay(event.score1, event.score2, accent)
                } else {
                    Text(
                        stringResource(R.string.ax_dynamic_bar_sports_vs),
                        color = SubtleGray,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Light,
                    )
                }
                TeamColumn(event.team2Name, event.team2Icon?.toScaledBitmap(48.dp))
            }
        } else {
            Text(
                event.team1Name,
                color = OnCardText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (event.statusDetail.isNotEmpty()) {
            Text(
                event.statusDetail,
                color = OnCardSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (event.commentary.isNotEmpty()) {
            Text(
                event.commentary,
                color = OnCardSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Clean fallback for any sports card we can't prove is a scoreboard.
 *
 * Follows the NowPlayingExpanded visual pattern: (1) league+status header, (2) a tinted
 * sectionSurface Row containing a glyph + primary+secondary text, (3) commentary/big-text
 * body below. This means the card always reads correctly on both surfaces (DB stack +
 * keyguard chip expansion) even when the provider data is a live-stats snapshot, matchup
 * preview, or raw notification from a 3rd-party app.
 */
@Composable
private fun GenericSportsFallback(
    event: IslandEvent.Sports,
    interactor: IslandActions,
    accent: Color,
) {
    val context = LocalContext.current
    val tapIntent = event.sbn?.notification?.contentIntent

    val (t1, t2) = event.team1Name to event.team2Name
    // The title slot holds an identity — who is playing, or failing that what competition.
    // A measurement never goes here: promoting "Possession 58-42 · Corners 3-1" to titleLarge
    // Bold is how a stats snapshot ends up shouting a scoreline it does not have.
    val headline = when {
        SportsShape.isLikelyTeamName(t1) && SportsShape.isLikelyTeamName(t2) -> "$t1 vs $t2"
        SportsShape.isLikelyTeamName(t1) -> t1
        SportsShape.isLikelyTeamName(t2) -> t2
        event.league.isNotBlank() -> event.league
        else -> stringResource(R.string.ax_dynamic_bar_sports_live_event)
    }
    // Everything measured is body text, in the order it was measured.
    val body = listOf(event.statusDetail, event.commentary)
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
    // Shown above the card unless it was promoted into the title, which would read as a stutter.
    val header = event.league.takeIf { it.isNotBlank() && it != headline }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .thenIf(tapIntent != null) {
                Modifier.clickable {
                    try { tapIntent?.sendWithBal(context) } catch (_: Exception) {}
                    interactor.collapseIsland()
                }
            },
        verticalArrangement = Arrangement.spacedBy(SpaceLg),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            Arrangement.SpaceBetween,
            Alignment.CenterVertically,
        ) {
            if (header.isNotEmpty()) {
                Text(
                    header,
                    color = SubtleGray,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(SpaceMd))
            } else {
                // SpaceBetween puts a lone child at the start; the badge belongs on the right
                // whether or not the league earned a line of its own.
                Spacer(Modifier.weight(1f))
            }
            StatusBadge(event.status, accent)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShapeLg)
                .background(AlphaColors.DbStackCard.sectionSurface(accent))
                .padding(SpaceLg),
            horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading glyph: first available team icon → else a trophy sports glyph.
            // This matches the NowPlaying cover / Charging CPRBatteryIcon visual anchor.
            val icon1 = event.team1Icon
            val icon2 = event.team2Icon
            val icon = icon1 ?: icon2
            if (icon != null) {
                Image(
                    bitmap = icon.toScaledBitmap(44.dp),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedTrophyIcon(AlphaColors.onAccentColor)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpaceXxs),
            ) {
                Text(
                    headline,
                    color = OnCardText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (body.isNotBlank() && body != headline) {
                    Text(
                        body,
                        color = OnCardSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Scoring/teams summary when we DO have partial data but not enough for 3-col layout.
        val canShowMiniScore =
            SportsShape.isLikelyTeamName(t1) &&
            (SportsShape.isLikelyTeamName(t2) || t2.isEmpty()) &&
            event.score1.isNotBlank() &&
            (event.score2.isNotBlank() || t2.isEmpty())
        if (canShowMiniScore) {
            Row(
                Modifier.fillMaxWidth(),
                Arrangement.Center,
                Alignment.CenterVertically,
            ) {
                if (SportsShape.isLikelyTeamName(t1)) CompactTeamBadge(t1, event.team1Icon, accent)
                Spacer(Modifier.width(SpaceMd))
                Text(
                    buildString {
                        append(event.score1)
                        if (event.score2.isNotBlank()) {
                            append(" - ")
                            append(event.score2)
                        }
                    },
                    color = accent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (SportsShape.isLikelyTeamName(t2)) {
                    Spacer(Modifier.width(SpaceMd))
                    CompactTeamBadge(t2, event.team2Icon, accent)
                }
            }
        }
    }
}

@Composable
internal fun RowScope.CompactSportsRow(event: IslandEvent.Sports) {
    val accent = accentColorFor(event)

    val t1Clean = SportsShape.isLikelyTeamName(event.team1Name)
    val t2Clean = SportsShape.isLikelyTeamName(event.team2Name)

    // The same question the expanded card asks, so a chip and the card it opens never
    // disagree about whether this is a scoreboard. Two teams are guaranteed inside.
    if (SportsShape.isScoreboard(event.team1Name, event.team2Name, event.score1, event.score2)) {
        CompactTeamBadge(event.team1Name, event.team1Icon, accent)
        Spacer(Modifier.width(SpaceSm))
        if (event.score1.isNotEmpty()) {
            Text(
                "${event.score1} - ${event.score2}",
                color = accent,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        } else {
            Text(
                stringResource(R.string.ax_dynamic_bar_sports_vs),
                color = SubtleGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(SpaceSm))
        CompactTeamBadge(event.team2Name, event.team2Icon, accent)
        Spacer(Modifier.width(SpaceSm))
        StatusBadge(event.status, accent)
        return
    }

    // Generic shape (live-stats, preview with no clean teams, notification raw text).
    // Render a trophy/sports glyph + best single line rather than 3 columns of garbage.
    CompactTeamBadge(
        name = if (t1Clean) event.team1Name else "",
        icon = event.team1Icon ?: event.team2Icon ?: event.appIcon,
        accent = accent,
    )
    Spacer(Modifier.width(SpaceSm))
    val compactLine = when {
        t1Clean && t2Clean && event.score1.isBlank() ->
            "${event.team1Name.take(8)} vs ${event.team2Name.take(8)}"
        event.statusDetail.isNotBlank() -> event.statusDetail.take(32)
        event.league.isNotBlank() -> event.league.take(24)
        t1Clean -> event.team1Name.take(16)
        else -> stringResource(R.string.ax_dynamic_bar_sports_live_event)
    }
    Text(
        compactLine,
        color = OnCardText,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(SpaceSm))
    StatusBadge(event.status, accent)
}

@Composable
private fun CompactTeamBadge(name: String, icon: Drawable?, accent: Color) {
    icon?.let {
        Image(
            bitmap = it.toScaledBitmap(SizeCompactIcon),
            contentDescription = name,
            modifier = Modifier.size(SizeCompactIcon).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } ?: Box(
        modifier = Modifier.size(SizeCompactIcon).clip(CircleShape).background(accent),
        contentAlignment = Alignment.Center,
    ) {
        // No name to initialise on the generic path — fall back to the same trophy the
        // expanded card uses rather than an empty coloured disc.
        if (name.isBlank()) {
            AnimatedTrophyIcon(AlphaColors.onAccentColor)
        } else {
            Text(
                name.take(3).uppercase(),
                color = AlphaColors.onAccentColor,
                style = TsBadge,
            )
        }
    }
}

@Composable
private fun TeamColumn(
    name: String,
    icon: ImageBitmap?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceMd),
        modifier = Modifier.width(80.dp),
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = name,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.take(3).uppercase(),
                    color = OnCardText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            name,
            color = OnCardText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScoreDisplay(score1: String, score2: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Text(
            score1,
            color = OnCardText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "-",
            color = SubtleGray,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
        )
        Text(
            score2,
            color = OnCardText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusBadge(status: IslandEvent.GameStatus, accent: Color) {
    val label = when (status) {
        IslandEvent.GameStatus.LIVE -> stringResource(R.string.ax_dynamic_bar_sports_live)
        IslandEvent.GameStatus.FINAL -> stringResource(R.string.ax_dynamic_bar_sports_final)
        IslandEvent.GameStatus.HALFTIME -> stringResource(R.string.ax_dynamic_bar_sports_halftime)
        IslandEvent.GameStatus.PRE_GAME -> stringResource(R.string.ax_dynamic_bar_sports_upcoming)
    }
    Surface(
        shape = ShapeChip,
        color = accent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            if (status == IslandEvent.GameStatus.LIVE) {
                PulsingDot(color = AlphaColors.onAccentColor, size = 6.dp)
            }
            Text(
                label,
                color = AlphaColors.onAccentColor,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
