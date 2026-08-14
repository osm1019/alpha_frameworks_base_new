/*
 * Copyright (C) 2025-2026 AxionOS Project
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

package com.android.systemui.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.util.MathUtils
import android.util.TypedValue
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import com.android.app.animation.Interpolators
import com.android.systemui.res.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AXRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val TAG = "AXRippleView"
        private const val DECODE_BITMAP_MAX_THREAD_POOL = 2
        private const val FRAME_DURATION_MS = 50L
    }

    private val animator = ValueAnimator.ofInt(0, 40).apply {
        duration = 800L
        interpolator = Interpolators.LINEAR
    }

    private val darkOverlayAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 300L
        interpolator = PathInterpolator(0.4f, 0f, 0f, 1f)
    }

    private val darkOverlayReverseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
        duration = 800L
        interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
    private var rippleAnimatorListener: Animator.AnimatorListener? = null
    private var rippleAnimatorUpdateListener: ValueAnimator.AnimatorUpdateListener? = null
    private var darkOverlayUpdateListener: ValueAnimator.AnimatorUpdateListener? = null
    private var darkOverlayReverseUpdateListener: ValueAnimator.AnimatorUpdateListener? = null

    private var currentAlpha = 0f
    private var currentIndex = 0
    private var images: MutableList<Bitmap?> = ArrayList()
    private var glare: Bitmap? = null
    private var wattageBg: Bitmap? = null
    private var logo: Drawable? = null
    private var ratedWatts = 0
    private var showBranding = false
    private var dimEnabled = true
    private var frameScale = 1f
    private var holdMs = 0L
    private var holdEndRunnable: Runnable? = null
    private var activeFrameResIds: IntArray = IntArray(0)
    private var executorService: ExecutorService? = null
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val wattageTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-semibold", Typeface.NORMAL)
    }
    private val wattageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wattageTextBounds = Rect()

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
    }

    fun setRatedWatts(watts: Int) {
        ratedWatts = watts
    }

    fun preloadRes(useSvoocFrames: Boolean = false) {
        val frameArray = if (useSvoocFrames) {
            val svooc = resources.obtainTypedArray(R.array.config_chargingAnimSvoocFrames)
            if (svooc.length() > 0) svooc else {
                svooc.recycle()
                resources.obtainTypedArray(R.array.config_chargingAnimFrames)
            }
        } else {
            resources.obtainTypedArray(R.array.config_chargingAnimFrames)
        }
        val frameCount = frameArray.length()
        activeFrameResIds = IntArray(frameCount) { frameArray.getResourceId(it, 0) }
        frameArray.recycle()

        animator.setIntValues(0, (frameCount - 1).coerceAtLeast(0))
        val frameDurationMs = try {
            resources.getInteger(R.integer.config_chargingAnimFrameDurationMs)
        } catch (_: Exception) {
            0
        }
        holdMs = try {
            resources.getInteger(R.integer.config_chargingAnimHoldMs).toLong()
        } catch (_: Exception) {
            0L
        }
        animator.duration = if (frameDurationMs > 0 && frameCount > 0) {
            (frameCount * frameDurationMs.toLong()).coerceAtLeast(800L)
        } else if (useSvoocFrames) {
            (frameCount * FRAME_DURATION_MS).coerceAtLeast(800L)
        } else {
            800L
        }

        images = ArrayList<Bitmap?>(frameCount).apply {
            repeat(frameCount) { add(null) }
        }
        startLoadExecutor()

        val glareTa = resources.obtainTypedArray(R.array.config_chargingAnimGlare)
        val glareResId = if (glareTa.length() > 0) glareTa.getResourceId(0, 0) else 0
        glareTa.recycle()
        glare = if (glareResId != 0) BitmapFactory.decodeResource(resources, glareResId) else null

        val wattTa = resources.obtainTypedArray(R.array.config_chargingAnimWattageBg)
        val wattResId = if (wattTa.length() > 0) wattTa.getResourceId(0, 0) else 0
        wattTa.recycle()
        wattageBg = if (wattResId != 0) BitmapFactory.decodeResource(resources, wattResId) else null

        val logoTa = resources.obtainTypedArray(R.array.config_chargingAnimLogo)
        val logoResId = if (logoTa.length() > 0) logoTa.getResourceId(0, 0) else 0
        logoTa.recycle()
        logo = if (logoResId != 0) resources.getDrawable(logoResId, null)?.mutate() else null

        // Draw SUPERVOOC + 100W whenever this overlay ships a wordmark.
        showBranding = logo != null
        dimEnabled = try {
            resources.getBoolean(R.bool.config_chargingAnimDimEnabled)
        } catch (_: Exception) {
            true
        }
        val scalePct = try {
            resources.getInteger(R.integer.config_chargingAnimFrameScale)
        } catch (_: Exception) {
            100
        }
        frameScale = (if (scalePct > 0) scalePct else 100) / 100f
    }

    private fun releaseRes() {
        images.forEachIndexed { index, bitmap ->
            bitmap?.recycle()
            images[index] = null
        }
        images.clear()
        glare?.recycle()
        glare = null
        wattageBg?.recycle()
        wattageBg = null
        logo = null
        executorService?.shutdown()
        executorService = null
    }

    fun startRipple(onAnimationEnd: Runnable? = null) {
        if (animator.isRunning) return

        rippleAnimatorUpdateListener?.let(animator::removeUpdateListener)
        rippleAnimatorListener?.let(animator::removeListener)
        darkOverlayUpdateListener?.let(darkOverlayAnimator::removeUpdateListener)
        darkOverlayReverseUpdateListener?.let(darkOverlayReverseAnimator::removeUpdateListener)

        val updateListener = ValueAnimator.AnimatorUpdateListener {
            currentIndex = it.animatedValue as Int
            invalidate()
            if (currentIndex > 0) {
                images.getOrNull(currentIndex - 1)?.recycle()
            }
        }
        animator.addUpdateListener(updateListener)

        val endListener = object : AnimatorListenerAdapter() {
            private var callbackInvoked = false

            private fun runOnAnimationFinished() {
                if (callbackInvoked) {
                    return
                }
                callbackInvoked = true
                val finish = Runnable {
                    holdEndRunnable = null
                    onAnimationEnd?.run()
                    releaseRes()
                }
                if (holdMs > 0L) {
                    holdEndRunnable = finish
                    postDelayed(finish, holdMs)
                } else {
                    finish.run()
                }
            }

            override fun onAnimationEnd(animation: Animator) {
                runOnAnimationFinished()
            }

            override fun onAnimationCancel(animation: Animator) {
                holdEndRunnable?.let { removeCallbacks(it) }
                holdEndRunnable = null
                if (callbackInvoked) {
                    return
                }
                callbackInvoked = true
                onAnimationEnd?.run()
                releaseRes()
            }
        }
        animator.addListener(endListener)

        val alphaUpdateListener = ValueAnimator.AnimatorUpdateListener {
            currentAlpha = it.animatedValue as Float
            invalidate()
        }

        darkOverlayAnimator.addUpdateListener(alphaUpdateListener)
        darkOverlayReverseAnimator.addUpdateListener(alphaUpdateListener)
        rippleAnimatorUpdateListener = updateListener
        rippleAnimatorListener = endListener
        darkOverlayUpdateListener = alphaUpdateListener
        darkOverlayReverseUpdateListener = alphaUpdateListener

        currentIndex = 0
        if (dimEnabled) {
            currentAlpha = 0f
            darkOverlayReverseAnimator.duration = animator.duration
            AnimatorSet().apply {
                playSequentially(
                    darkOverlayAnimator,
                    AnimatorSet().apply { playTogether(animator, darkOverlayReverseAnimator) }
                )
                start()
            }
        } else {
            // Transparent SuperVOOC path: no fade-to-black, start the ring immediately.
            currentAlpha = 1f
            animator.start()
        }
    }

    fun rippleInProgress(): Boolean = animator.isRunning

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val min = MathUtils.min(width, height).toFloat()
        val max = MathUtils.max(width, height).toFloat()

        val display: Display? = windowManager.defaultDisplay
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Log.d(TAG, "rotate ${display?.rotation}")
            canvas.rotate(-90f, min / 2, min / 2)
            display?.rotation?.takeIf { it == 3 }?.let {
                canvas.scale(1f, -1f, height / 2f, width / 2f)
            }
        }

        if (dimEnabled) {
            canvas.drawARGB((currentAlpha * (255 * 0.2f)).toInt(), 0, 0, 0)
        }

        if (currentIndex < images.size) {
            images.getOrNull(currentIndex)?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    val scale = (min / bitmap.width) * frameScale
                    val destW = bitmap.width * scale
                    val destH = bitmap.height * scale
                    val destLeft = (min - destW) / 2f
                    val destTop = (max - destH) / 2f
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(destLeft, destTop, destLeft + destW, destTop + destH),
                        null
                    )
                }
            }
        }

        glare?.takeIf { !it.isRecycled }?.let {
            val alpha = (currentAlpha * 255).toInt()
            it.let { bmp ->
                val paint = android.graphics.Paint().apply { this.alpha = alpha }
                canvas.drawBitmap(
                    bmp,
                    (min - bmp.width) / 2,
                    max - (bmp.height / 2),
                    paint
                )
            }
        }

        if (showBranding && currentAlpha > 0f) {
            drawBranding(canvas, min, max)
        }
    }

    private fun drawBranding(canvas: Canvas, min: Float, max: Float) {
        val cx = min / 2f
        val cy = max / 2f
        val alpha = (currentAlpha * 255).toInt()

        val mark = logo
        if (mark != null) {
            val logoW = min * 0.34f * frameScale
            val logoH = logoW * (12f / 78f)
            mark.alpha = alpha
            mark.setBounds(
                (cx - logoW / 2f).toInt(),
                (cy - logoH / 2f - min * 0.012f).toInt(),
                (cx + logoW / 2f).toInt(),
                (cy + logoH / 2f - min * 0.012f).toInt()
            )
            mark.draw(canvas)
        }

        val watts = if (ratedWatts > 0) ratedWatts else 100
        val label = "${watts}W"
        val textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
        ) * frameScale
        wattageTextPaint.textSize = textSize
        wattageTextPaint.alpha = alpha
        wattageTextPaint.getTextBounds(label, 0, label.length, wattageTextBounds)

        val padX = textSize * 0.85f
        val textW = wattageTextBounds.width().toFloat()
        val pillCy = cy + min * 0.075f * frameScale

        val destW = textW + padX * 2.4f
        val destH = textSize * 1.65f
        val dest = RectF(
            cx - destW / 2f,
            pillCy - destH / 2f,
            cx + destW / 2f,
            pillCy + destH / 2f
        )
        val bg = wattageBg
        if (bg != null && !bg.isRecycled) {
            val bgH = destW * bg.height / bg.width
            val bgDest = RectF(
                cx - destW / 2f,
                pillCy - bgH / 2f,
                cx + destW / 2f,
                pillCy + bgH / 2f
            )
            wattageBgPaint.alpha = alpha
            canvas.drawBitmap(bg, null, bgDest, wattageBgPaint)
        } else {
            wattageBgPaint.color = Color.WHITE
            wattageBgPaint.alpha = alpha
            canvas.drawRoundRect(dest, destH * 0.22f, destH * 0.22f, wattageBgPaint)
        }

        val textY = pillCy - (wattageTextPaint.descent() + wattageTextPaint.ascent()) / 2f
        canvas.drawText(label, cx, textY, wattageTextPaint)
    }

    private fun startLoadExecutor() {
        if (executorService != null) return
        executorService = Executors.newFixedThreadPool(DECODE_BITMAP_MAX_THREAD_POOL)
        activeFrameResIds.indices.forEach { i ->
            executorService?.execute(DecodeBitmapTask(images, resources, i, activeFrameResIds[i]))
        }
    }

    private inner class DecodeBitmapTask(
        private val images: MutableList<Bitmap?>,
        private val resources: Resources,
        private val index: Int,
        private val resId: Int
    ) : Runnable {

        override fun run() {
            try {
                if (resId != 0) {
                    resources.openRawResource(resId).use { stream ->
                        images[index] = BitmapFactory.decodeStream(stream)
                    }
                }
            } catch (e: Exception) {}
        }
    }
}
