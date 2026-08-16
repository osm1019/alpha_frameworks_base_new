/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView

class KeyguardIndicationArea(context: Context, private val attrs: AttributeSet?) :
    LinearLayout(context, attrs) {

    init {
        setId(R.id.keyguard_indication_area)
        orientation = LinearLayout.VERTICAL

        addView(
            nowPlayingPill(),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = R.dimen.keyguard_now_playing_pill_margin_bottom.dp()
            },
        )
        addView(indicationTopRow(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(
            indicationBottomRow(),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)

        if (alpha == 0f) {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    /** Now Playing gets its own row so the song never rotates with charging messages. */
    private fun nowPlayingPill(): LinearLayout {
        return LinearLayout(context).apply {
            id = R.id.keyguard_now_playing_pill
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundResource(R.drawable.keyguard_now_playing_pill)

            val padH = R.dimen.keyguard_now_playing_pill_padding_horizontal.dp()
            val padV = R.dimen.keyguard_now_playing_pill_padding_vertical.dp()
            setPaddingRelative(padH, padV, padH, padV)

            val artSize = R.dimen.keyguard_now_playing_art_size.dp()
            addView(
                ImageView(context).apply {
                    id = R.id.keyguard_now_playing_art
                    scaleType = ImageView.ScaleType.CENTER_CROP
                },
                LinearLayout.LayoutParams(artSize, artSize).apply {
                    marginEnd = R.dimen.keyguard_now_playing_pill_spacing.dp()
                },
            )
            addView(
                TextView(context).apply {
                    id = R.id.keyguard_now_playing_text
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextAppearance(R.style.TextAppearance_Keyguard_BottomArea)
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        }
    }

    private fun indicationTopRow(): KeyguardIndicationTextView {
        return KeyguardIndicationTextView(context, attrs).apply {
            id = R.id.keyguard_indication_text
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextAppearance(R.style.TextAppearance_Keyguard_BottomArea)

            val padding = R.dimen.keyguard_indication_text_padding.dp()
            setPaddingRelative(padding, 0, padding, 0)
        }
    }

    private fun indicationBottomRow(): KeyguardIndicationTextView {
        return KeyguardIndicationTextView(context, attrs).apply {
            id = R.id.keyguard_indication_text_bottom
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

            setTextAppearance(R.style.TextAppearance_Keyguard_BottomArea)
            setEllipsize(TextUtils.TruncateAt.END)
            setAlpha(0.8f)
            setMinHeight(R.dimen.keyguard_indication_text_min_height.dp())
            setMaxLines(3)
            setVisibility(View.GONE)

            val padding = R.dimen.keyguard_indication_text_padding.dp()
            setPaddingRelative(padding, 0, padding, 0)
        }
    }

    private fun Int.dp(): Int {
        return context.resources.getDimensionPixelSize(this)
    }
}
