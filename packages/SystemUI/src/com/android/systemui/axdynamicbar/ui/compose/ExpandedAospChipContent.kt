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

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.android.systemui.axdynamicbar.model.IslandEvent
import com.android.systemui.axdynamicbar.shared.*
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import kotlinx.coroutines.delay

private val NoOpExpandable = object : Expandable {
    override fun activityTransitionController(
        launchCujType: Int?,
        cookie: ActivityTransitionAnimator.TransitionCookie?,
        component: android.content.ComponentName?,
        returnCujType: Int?,
        isEphemeral: Boolean,
    ): ActivityTransitionAnimator.Controller? = null

    override fun dialogTransitionController(
        cuj: DialogCuj?
    ): DialogTransitionAnimator.Controller? = null
}

@Composable
internal fun AospChipExpanded(
    event: IslandEvent.AospChip,
    interactor: IslandActions,
) {
    when (event.active.key) {
        "ScreenRecord" -> ScreenRecordChipExpanded(event, interactor)
        "CastToOtherDevice" -> CastChipExpanded(event, interactor)
        "ShareToApp" -> ShareToAppChipExpanded(event, interactor)
        else -> {
            if (event.active.key.startsWith("callChip-")) {
                CallChipExpanded(event, interactor)
            }
        }
    }
}

@Composable
private fun ScreenRecordChipExpanded(
    event: IslandEvent.AospChip,
    interactor: IslandActions,
) {
    val accent = RedAccent
    val content = event.active.content
    val isCountdown = content is OngoingActivityChipModel.Content.Countdown

    // Timer for active recording
    val timerContent = content as? OngoingActivityChipModel.Content.Timer
    var elapsedMs by remember(timerContent?.startTimeMs) {
        val start = timerContent?.startTimeMs ?: 0L
        mutableLongStateOf(
            if (start > 0L) (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L) else 0L
        )
    }
    LaunchedEffect(timerContent?.startTimeMs) {
        if (timerContent != null) {
            while (true) {
                delay(1000)
                elapsedMs = (SystemClock.elapsedRealtime() - timerContent.startTimeMs)
                    .coerceAtLeast(0L)
            }
        }
    }

    ExpandedCardLayout(
        accentColor = accent,
        icon = { glyph ->
            if (isCountdown) {
                val seconds = (content as OngoingActivityChipModel.Content.Countdown).secondsUntilStarted
                Text(
                    "$seconds",
                    color = glyph,
                    style = MaterialTheme.typography.headlineMedium,
                )
            } else {
                PulsingDot(color = glyph, size = 14.dp, durationMs = 550, minAlpha = AlphaTrack)
            }
        },
        title = {
            Text(
                stringResource(R.string.ax_dynamic_bar_screen_recording),
                color = SubtleGray,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCountdown) {
                Text(
                    stringResource(R.string.screenrecord_continue),
                    color = accent,
                    style = MaterialTheme.typography.titleSmall,
                )
            } else {
                Text(
                    formatElapsedTime(elapsedMs),
                    color = accent,
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        },
        trailing = {
            if (!isCountdown) {
                StatusChip(stringResource(R.string.ax_dynamic_bar_recording), accent)
            }
        },
        actions = if (!isCountdown) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SpaceLg),
                ) {
                    ActionChip(
                        label = stringResource(R.string.screenrecord_stop_label),
                        icon = Icons.Filled.Stop,
                        color = OnDestructiveText,
                        bg = DestructiveBg,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            interactor.stopScreenRecording()
                            interactor.collapseIsland()
                        },
                    )
                }
            }
        } else null,
    )
}

@Composable
private fun CastChipExpanded(
    event: IslandEvent.AospChip,
    interactor: IslandActions,
) {
    val accent = BlueAccent
    val text = (event.active.content as? OngoingActivityChipModel.Content.Text)?.text ?: ""

    ExpandedCardLayout(
        accentColor = accent,
        icon = { glyph -> Icon(Icons.Filled.Cast, null, tint = glyph, modifier = Modifier.size(26.dp)) },
        title = {
            Text(
                stringResource(R.string.quick_settings_cast_title),
                color = SubtleGray,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (text.isNotEmpty()) {
                Text(
                    text,
                    color = OnCardText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                ActionChip(
                    label = stringResource(R.string.screenrecord_stop_label),
                    icon = Icons.Filled.Stop,
                    color = OnDestructiveText,
                    bg = DestructiveBg,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val behavior = event.active.clickBehavior
                        if (behavior is OngoingActivityChipModel.ClickBehavior.ExpandAction) {
                            behavior.onClick(NoOpExpandable)
                        }
                        interactor.collapseIsland()
                    },
                )
            }
        },
    )
}

@Composable
private fun ShareToAppChipExpanded(
    event: IslandEvent.AospChip,
    interactor: IslandActions,
) {
    val accent = OrangeAccent
    val text = (event.active.content as? OngoingActivityChipModel.Content.Text)?.text ?: ""

    ExpandedCardLayout(
        accentColor = accent,
        icon = { glyph -> Icon(Icons.Filled.ScreenShare, null, tint = glyph, modifier = Modifier.size(26.dp)) },
        title = {
            Text(
                stringResource(R.string.quick_settings_cast_title),
                color = SubtleGray,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (text.isNotEmpty()) {
                Text(
                    text,
                    color = OnCardText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                ActionChip(
                    label = stringResource(R.string.screenrecord_stop_label),
                    icon = Icons.Filled.Stop,
                    color = OnDestructiveText,
                    bg = DestructiveBg,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val behavior = event.active.clickBehavior
                        if (behavior is OngoingActivityChipModel.ClickBehavior.ExpandAction) {
                            behavior.onClick(NoOpExpandable)
                        }
                        interactor.collapseIsland()
                    },
                )
            }
        },
    )
}

@Composable
private fun CallChipExpanded(
    event: IslandEvent.AospChip,
    interactor: IslandActions,
) {
    val accent = GreenAccent
    val content = event.active.content

    val timerContent = content as? OngoingActivityChipModel.Content.Timer
    var elapsedMs by remember(timerContent?.startTimeMs) {
        val start = timerContent?.startTimeMs ?: 0L
        mutableLongStateOf(
            if (start > 0L) (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L) else 0L
        )
    }
    LaunchedEffect(timerContent?.startTimeMs) {
        if (timerContent != null) {
            while (true) {
                delay(1000)
                elapsedMs = (SystemClock.elapsedRealtime() - timerContent.startTimeMs)
                    .coerceAtLeast(0L)
            }
        }
    }

    val text = (content as? OngoingActivityChipModel.Content.Text)?.text ?: ""

    ExpandedCardLayout(
        accentColor = accent,
        icon = { glyph ->
            Icon(
                imageVector = Icons.Filled.Cast, // phone icon handled by PillIslandContent
                contentDescription = null,
                tint = glyph,
                modifier = Modifier.size(26.dp),
            )
        },
        title = {
            Text(
                stringResource(R.string.ax_dynamic_bar_call),
                color = SubtleGray,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (timerContent != null) {
                Text(
                    formatElapsedTime(elapsedMs),
                    color = accent,
                    style = MaterialTheme.typography.headlineMedium,
                )
            } else if (text.isNotEmpty()) {
                Text(
                    text,
                    color = OnCardText,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailing = {
            if (timerContent != null) {
                StatusChip(stringResource(R.string.ax_dynamic_bar_call), accent)
            }
        },
    )
}
