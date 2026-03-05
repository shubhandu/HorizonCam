package com.horizoncam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Samsung-style record button.
 * Idle:      thin white outer ring + red filled circle
 * Recording: outer ring turns red, inner morphs to a rounded square
 */
class RecordButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var isRecording = false
        set(value) {
            if (field == value) return
            field = value
            animateMorph(value)
        }

    private var morphProgress = 0f

    private val morphAnimator = ValueAnimator().apply {
        duration = 250
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { morphProgress = it.animatedValue as Float; invalidate() }
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.FILL
    }

    private fun animateMorph(toRecording: Boolean) {
        morphAnimator.cancel()
        morphAnimator.setFloatValues(morphProgress, if (toRecording) 1f else 0f)
        morphAnimator.start()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerR = minOf(cx, cy) - ringPaint.strokeWidth

        ringPaint.color = blendColor(Color.WHITE, Color.parseColor("#FF3B30"), morphProgress)
        canvas.drawCircle(cx, cy, outerR, ringPaint)

        val idleR = outerR * 0.78f
        val recR = outerR * 0.32f
        val innerR = lerp(idleR, recR, morphProgress)
        val cornerR = lerp(idleR, 8f, morphProgress)

        canvas.drawRoundRect(
            cx - innerR, cy - innerR, cx + innerR, cy + innerR,
            cornerR, cornerR, fillPaint
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun blendColor(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1) * (1 - t) + Color.red(c2) * t).toInt()
        val g = (Color.green(c1) * (1 - t) + Color.green(c2) * t).toInt()
        val b = (Color.blue(c1) * (1 - t) + Color.blue(c2) * t).toInt()
        return Color.rgb(r, g, b)
    }
}

/**
 * Clean flip-camera button with rotate icon.
 */
class FlipButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val arcRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) - 2f

        canvas.drawCircle(cx, cy, r, bgPaint)

        val ar = r * 0.42f
        arcRect.set(cx - ar, cy - ar, cx + ar, cy + ar)

        canvas.drawArc(arcRect, -30f, -180f, false, iconPaint)
        val ax1 = cx + ar * Math.cos(Math.toRadians(-210.0)).toFloat()
        val ay1 = cy + ar * Math.sin(Math.toRadians(-210.0)).toFloat()
        val path1 = Path().apply {
            moveTo(ax1, ay1)
            lineTo(ax1 + 6f, ay1 - 4f)
            lineTo(ax1 + 2f, ay1 + 5f)
            close()
        }
        canvas.drawPath(path1, arrowFill)

        canvas.drawArc(arcRect, 150f, -180f, false, iconPaint)
        val ax2 = cx + ar * Math.cos(Math.toRadians(-30.0)).toFloat()
        val ay2 = cy + ar * Math.sin(Math.toRadians(-30.0)).toFloat()
        val path2 = Path().apply {
            moveTo(ax2, ay2)
            lineTo(ax2 - 6f, ay2 + 4f)
            lineTo(ax2 - 2f, ay2 - 5f)
            close()
        }
        canvas.drawPath(path2, arrowFill)
    }
}
