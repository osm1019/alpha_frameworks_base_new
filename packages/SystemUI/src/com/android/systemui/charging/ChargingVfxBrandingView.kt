/*
 * Copyright (C) 2026 The AlphaDroid Project
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

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import com.android.systemui.res.R

/**
 * 12R HUD over the GLES ring: white battery %, then bolt + SUPERVOOC™ and watt pill
 * (VOOC: bolt + "VOOC"; USB/AC: bolt + source). Matches ChargeLevelAndLogoView
 * (number in the hole, mark below). Long-press applies GradientColorTextView's
 * mint→white shader on the number only; % stays white.
 * Stock [tips_text] sits above the number ("Touch and hold to boost speed") on
 * VOOC/SuperVOOC while a cap is active and the pack is not full.
 * Night-mode cap or 100% replace the wordmark/source with a status label.
 */
class ChargingVfxBrandingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var logo: Drawable? = null
    private var bolt: Drawable? = null
    private var wattageBg: Drawable? = null
    private var ratedWatts = 0
    private var batteryLevel = 0
    private var showLogo = false
    private var statusLabel: String? = null
    private var sourceLabel: String? = null
    private var fadedIn = false
    private var speedUpGradient = false
    private var showBoostTip = false

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val logoFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
    }
    private val wattageTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-semibold", Typeface.NORMAL)
    }
    private val wattageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val wattageTextBounds = Rect()
    private val levelBounds = Rect()
    private val tipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
    }
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.04f
    }

    init {
        setWillNotDraw(false)
        alpha = 0f
        preloadRes()
    }

    fun setBatteryLevel(level: Int) {
        batteryLevel = level.coerceIn(0, 100)
        invalidate()
    }

    fun setRatedWatts(watts: Int) {
        ratedWatts = watts
        invalidate()
    }

    fun setShowLogo(show: Boolean) {
        showLogo = show
        invalidate()
    }

    /**
     * Centered status under the number (Night mode, Charged). Replaces wordmark,
     * watt pill, and source. Null clears it.
     */
    fun setStatusLabel(label: String?) {
        if (statusLabel == label) {
            return
        }
        statusLabel = label
        invalidate()
    }

    /** Bolt + source (USB, VOOC) when SuperVOOC branding is not shown. */
    fun setSourceLabel(label: String?) {
        if (sourceLabel == label) {
            return
        }
        sourceLabel = label
        invalidate()
    }

    /**
     * ColorOS [GradientColorTextView.setGradientColor]: LinearGradient from mint
     * `#8AFFA4` at the number's bottom-left to white at top-right. Digits only.
     */
    fun setSpeedUpGradient(enabled: Boolean) {
        if (speedUpGradient == enabled) {
            return
        }
        speedUpGradient = enabled
        invalidate()
    }

    /** Stock tips_text above the number. Cleared after a successful long-press. */
    fun setBoostTip(show: Boolean) {
        if (showBoostTip == show) {
            return
        }
        showBoostTip = show
        invalidate()
    }

    fun fadeIn() {
        if (fadedIn) {
            return
        }
        fadedIn = true
        visibility = VISIBLE
        ObjectAnimator.ofFloat(this, View.ALPHA, 0f, 1f).apply {
            duration = 500
            interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)
            start()
        }
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }

    private fun loadDrawable(id: Int): Drawable? {
        return try {
            resources.getDrawable(id, null)?.mutate()
        } catch (_: Exception) {
            null
        }
    }

    private fun preloadRes() {
        bolt = loadDrawable(R.drawable.charging_vfx_bolt)
        logo = loadDrawable(R.drawable.charging_vfx_supervooc_logo)
        wattageBg = loadDrawable(R.drawable.charging_vfx_wattage_bg)

        // Overlay arrays can replace these if present; do not depend on them.
        val logoTa = resources.obtainTypedArray(R.array.config_chargingAnimLogo)
        val overlayLogo = if (logoTa.length() > 0) logoTa.getResourceId(0, 0) else 0
        logoTa.recycle()
        if (overlayLogo != 0) {
            loadDrawable(overlayLogo)?.let { logo = it }
        }
        val wattTa = resources.obtainTypedArray(R.array.config_chargingAnimWattageBg)
        val overlayWatt = if (wattTa.length() > 0) wattTa.getResourceId(0, 0) else 0
        wattTa.recycle()
        if (overlayWatt != 0) {
            loadDrawable(overlayWatt)?.let { wattageBg = it }
        }
        Log.i(TAG, "hud bolt=" + (bolt != null) + " logo=" + (logo != null)
                + " wattBg=" + (wattageBg != null))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE || width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f

        val levelSize = dp(LEVEL_DP)
        val pctSize = dp(PERCENT_DP)
        levelPaint.shader = null
        levelPaint.color = Color.WHITE
        levelPaint.alpha = 255
        levelPaint.textSize = levelSize
        percentPaint.alpha = 255
        percentPaint.textSize = pctSize

        val levelText = batteryLevel.toString()
        levelPaint.getTextBounds(levelText, 0, levelText.length, levelBounds)
        val levelWidth = levelPaint.measureText(levelText)
        val pctWidth = percentPaint.measureText("%")
        val totalWidth = levelWidth + dp(2f) + pctWidth
        val levelX = cx - totalWidth / 2f + levelWidth / 2f
        val levelY = cy - (levelPaint.descent() + levelPaint.ascent()) / 2f
        if (speedUpGradient) {
            // Stock GradientColorTextView: LinearGradient(0, textSize, measureText, 0,
            // mint, white, CLAMP) in the number view's local space.
            val left = levelX - levelWidth / 2f
            val top = levelY + levelPaint.ascent()
            levelPaint.shader = LinearGradient(
                left,
                top + levelSize,
                left + levelWidth,
                top,
                SPEED_UP_MINT,
                Color.WHITE,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawText(levelText, levelX, levelY, levelPaint)

        val pctX = levelX + levelWidth / 2f + dp(2f)
        val pctY = levelY - levelSize * 0.22f + pctSize * 0.55f
        canvas.drawText("%", pctX, pctY, percentPaint)

        if (showBoostTip) {
            drawBoostTip(canvas, cx, levelY + levelPaint.ascent())
        }

        val rowTop = levelY + levelPaint.descent() + dp(ROW_GAP_DP)
        val status = statusLabel
        if (!status.isNullOrEmpty()) {
            drawStatusLabel(canvas, cx, rowTop, status)
            return
        }
        if (showLogo || ratedWatts > 0) {
            drawMarkAndWatts(canvas, cx, rowTop)
            return
        }
        val source = sourceLabel
        if (!source.isNullOrEmpty()) {
            drawSource(canvas, cx, rowTop, source)
        }
    }

    /**
     * Stock [tips_text]: 16dp white, 160dp wrap, centered above the number.
     * ChargeLevelAndLogoView lineSpacing 1.1.
     */
    private fun drawBoostTip(canvas: Canvas, cx: Float, numberTop: Float) {
        val text = resources.getString(R.string.charging_vfx_long_press_to_speed)
        if (text.isEmpty()) {
            return
        }
        tipPaint.textSize = dp(TIP_DP)
        tipPaint.alpha = 255
        val maxW = dp(TIP_MAX_W_DP).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tipPaint, maxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .build()
        val top = numberTop - dp(TIP_GAP_DP) - layout.height
        canvas.save()
        canvas.translate(cx - maxW / 2f, top)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Night mode / Charged: wordmark + pill become this label. */
    private fun drawStatusLabel(canvas: Canvas, cx: Float, rowTop: Float, text: String) {
        statusPaint.textSize = dp(STATUS_DP)
        statusPaint.alpha = 255
        val textY = rowTop - statusPaint.ascent()
        canvas.drawText(text, cx, textY, statusPaint)
    }

    /** Bolt + USB / VOOC when the adapter is not SuperVOOC. */
    private fun drawSource(canvas: Canvas, cx: Float, rowTop: Float, text: String) {
        val boltW = dp(BOLT_W_DP)
        val boltH = dp(BOLT_H_DP)
        val gap = dp(BOLT_GAP_DP)
        sourcePaint.textSize = dp(SOURCE_DP)
        sourcePaint.alpha = 255
        val textW = sourcePaint.measureText(text)
        val textH = sourcePaint.descent() - sourcePaint.ascent()
        val groupW = boltW + gap + textW
        val groupH = maxOf(boltH, textH)
        val groupLeft = cx - groupW / 2f
        val groupTop = rowTop
        val boltDrawable = bolt
        val boltTop = groupTop + (groupH - boltH) / 2f
        if (boltDrawable != null) {
            boltDrawable.alpha = 255
            boltDrawable.setBounds(
                groupLeft.toInt(),
                boltTop.toInt(),
                (groupLeft + boltW).toInt(),
                (boltTop + boltH).toInt()
            )
            boltDrawable.draw(canvas)
        }
        val textX = groupLeft + boltW + gap
        val textY = groupTop + (groupH - textH) / 2f - sourcePaint.ascent()
        canvas.drawText(text, textX, textY, sourcePaint)
    }

    /**
     * Stock row under the number: [bolt] [ SUPERVOOC™ ]
     *                                      [ 100W pill ]
     */
    private fun drawMarkAndWatts(canvas: Canvas, cx: Float, rowTop: Float) {
        val boltW = dp(BOLT_W_DP)
        val boltH = dp(BOLT_H_DP)
        val logoW = dp(LOGO_W_DP)
        val logoH = dp(LOGO_H_DP)
        val gap = dp(BOLT_GAP_DP)
        val watts = ratedWatts
        val showWatts = watts > 0
        val wattSize = dp(WATT_DP)
        wattageTextPaint.textSize = wattSize
        wattageTextPaint.alpha = 255
        val label = if (showWatts) "${watts}W" else ""
        if (showWatts) {
            wattageTextPaint.getTextBounds(label, 0, label.length, wattageTextBounds)
        }
        val pillH = if (showWatts) dp(PILL_H_DP) else 0f
        val pillW = if (showWatts) {
            (wattageTextBounds.width() + dp(18f)).coerceAtLeast(dp(48f))
        } else {
            0f
        }
        val colW = maxOf(logoW, pillW)
        val colH = logoH + if (showWatts) dp(WATT_GAP_DP) + pillH else 0f
        val groupW = boltW + gap + colW
        val groupH = maxOf(boltH, colH)
        val groupLeft = cx - groupW / 2f
        val groupTop = rowTop

        val boltDrawable = bolt
        val boltLeft = groupLeft
        val boltTop = groupTop + (groupH - boltH) / 2f
        if (boltDrawable != null) {
            boltDrawable.alpha = 255
            boltDrawable.setBounds(
                boltLeft.toInt(),
                boltTop.toInt(),
                (boltLeft + boltW).toInt(),
                (boltTop + boltH).toInt()
            )
            boltDrawable.draw(canvas)
        }

        val colLeft = groupLeft + boltW + gap
        val mark = logo
        if (mark != null) {
            mark.alpha = 255
            mark.setBounds(
                colLeft.toInt(),
                groupTop.toInt(),
                (colLeft + logoW).toInt(),
                (groupTop + logoH).toInt()
            )
            mark.draw(canvas)
        } else {
            logoFallbackPaint.textSize = logoH * 0.95f
            logoFallbackPaint.alpha = 255
            val textY = groupTop - logoFallbackPaint.ascent()
            canvas.drawText(LOGO_FALLBACK, colLeft, textY, logoFallbackPaint)
        }

        if (!showWatts) return
        val pillLeft = colLeft
        val pillTop = groupTop + logoH + dp(WATT_GAP_DP)
        val pill = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)
        val bg = wattageBg
        if (bg != null) {
            bg.alpha = 255
            bg.setBounds(
                pill.left.toInt(),
                pill.top.toInt(),
                pill.right.toInt(),
                pill.bottom.toInt()
            )
            bg.draw(canvas)
        } else {
            wattageBgPaint.alpha = 255
            canvas.drawRoundRect(pill, pillH * 0.28f, pillH * 0.28f, wattageBgPaint)
        }
        val textY = pill.centerY() - (wattageTextPaint.descent() + wattageTextPaint.ascent()) / 2f
        canvas.drawText(label, pill.centerX(), textY, wattageTextPaint)
    }

    companion object {
        private const val TAG = "ChargingVfxHud"
        private const val LOGO_FALLBACK = "SUPERVOOC™"
        // 12R oplus_charge_anim_* dimen / drawable sizes.
        private const val LEVEL_DP = 64f
        private const val PERCENT_DP = 22f
        private const val BOLT_W_DP = 22f
        private const val BOLT_H_DP = 40f
        private const val LOGO_W_DP = 78f
        private const val LOGO_H_DP = 12f
        private const val BOLT_GAP_DP = 8f
        private const val ROW_GAP_DP = 8f
        private const val WATT_DP = 12f
        private const val WATT_GAP_DP = 6f
        private const val PILL_H_DP = 16f
        private const val TIP_DP = 16f
        private const val TIP_MAX_W_DP = 160f
        private const val TIP_GAP_DP = 12f
        private const val STATUS_DP = 16f
        private const val SOURCE_DP = 16f
        // GradientColorTextView mint `#8AFFA4` / -7667804.
        private val SPEED_UP_MINT = Color.parseColor("#8AFFA4")
    }
}
