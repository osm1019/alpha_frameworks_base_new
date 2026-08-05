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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.VERTICAL
import com.android.systemui.res.R
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

class SplitShadeGuidelines
@Inject
constructor(@ShadeDisplayAware private val context: Context) : KeyguardSection() {
    override fun addViews(constraintLayout: ConstraintLayout) {}

    override fun bindData(constraintLayout: ConstraintLayout) {}

    override fun applyConstraints(constraintSet: ConstraintSet) {
        constraintSet.apply {
            // For use on large screens, it will provide a guideline vertically to enable items to
            // be aligned on the left or right sides. Shares its fraction with the shade's qs_frame
            // and notification stack guidelines so the keyguard and the shade line up.
            create(R.id.split_shade_guideline, VERTICAL)
            setGuidelinePercent(
                R.id.split_shade_guideline,
                context.resources.getFloat(R.dimen.split_shade_qs_fraction),
            )
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {}
}
