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
package com.android.systemui.statusbar.notification.stack.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.core.widgets.Optimizer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.VERTICAL
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel.HorizontalPosition
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel.HorizontalPosition.EdgeToMiddle
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel.HorizontalPosition.MiddleToEdge

/**
 * Container for the stack scroller, so that the bounds can be externally specified, such as from
 * the keyguard or shade scenes.
 */
class SharedNotificationContainer(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs) {

    private val baseConstraintSet = ConstraintSet()

    /**
     * Where the stack meets Quick Settings in the split shade. Must match the qs_frame guideline in
     * [com.android.systemui.shade.NotificationsQSContainerController], which reads the same
     * resource, or the two panes overlap.
     */
    private val splitShadeQsFraction: Float
        get() = resources.getFloat(R.dimen.split_shade_qs_fraction)

    init {
        optimizationLevel = optimizationLevel or Optimizer.OPTIMIZATION_GRAPH
        baseConstraintSet.apply {
            create(R.id.nssl_guideline, VERTICAL)
            setGuidelinePercent(R.id.nssl_guideline, splitShadeQsFraction)
        }
        baseConstraintSet.applyTo(this)
    }

    fun addNotificationStackScrollLayout(nssl: View) {
        addView(nssl)
    }

    fun updateConstraints(
        horizontalPosition: HorizontalPosition,
        marginStart: Int,
        marginTop: Int,
        marginEnd: Int,
        marginBottom: Int,
        nsslAlpha: Float,
    ) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(baseConstraintSet)

        val startConstraintId =
            if (horizontalPosition is MiddleToEdge) R.id.nssl_guideline else PARENT_ID

        val endConstraintId =
            if (SceneContainerFlag.isEnabled && horizontalPosition is EdgeToMiddle) {
                R.id.nssl_guideline
            } else {
                PARENT_ID
            }

        val nsslId = R.id.notification_stack_scroller
        constraintSet.apply {
            // Re-applied on every update: the base set is built once, but the fraction is
            // orientation-dependent and this runs again on configuration changes.
            setGuidelinePercent(R.id.nssl_guideline, splitShadeQsFraction)
            if (SceneContainerFlag.isEnabled) {
                when (horizontalPosition) {
                    is EdgeToMiddle -> {
                        // Mirrored case: the stack runs edge -> guideline, so it takes the share
                        // QS does not.
                        setGuidelinePercent(R.id.nssl_guideline, 1f - splitShadeQsFraction)
                        constrainMaxWidth(nsslId, horizontalPosition.maxWidth)
                        // Ensure START alignment in case the maxWidth is smaller than half the
                        // parent width.
                        constraintSet.setHorizontalBias(nsslId, /* bias= */ 0f)
                    }
                    is MiddleToEdge -> {
                        setGuidelinePercent(R.id.nssl_guideline, splitShadeQsFraction)
                        constrainMaxWidth(nsslId, horizontalPosition.maxWidth)
                        // Ensure END alignment in case the maxWidth is smaller than half the
                        // parent width.
                        constraintSet.setHorizontalBias(nsslId, /* bias= */ 1f)
                    }
                    else -> Unit
                }
            }

            // Constraint layout sets the alpha to 1 if it's not set explicitly in the constraint
            // set. Let's keep the current nssl alpha instead, otherwise this might interfere with
            // animations.
            setAlpha(nsslId, nsslAlpha)
            connect(nsslId, START, startConstraintId, START, marginStart)
            connect(nsslId, END, endConstraintId, END, marginEnd)
            connect(nsslId, BOTTOM, PARENT_ID, BOTTOM, marginBottom)
            connect(nsslId, TOP, PARENT_ID, TOP, marginTop)
        }
        constraintSet.applyTo(this)
    }
}
