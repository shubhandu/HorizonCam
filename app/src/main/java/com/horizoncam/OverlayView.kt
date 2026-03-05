package com.horizoncam

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var isActionMode = false
        set(value) { field = value; invalidate() }

    var rollAngle = 0f
        set(value) { field = value; if (isActionMode) invalidate() }

    var translationDx = 0f
        set(value) { field = value; if (isActionMode) invalidate() }

    var translationDy = 0f
        set(value) { field = value; if (isActionMode) invalidate() }

    /** Current aspect ratio — controls the crop guide shape. */
    var aspectRatio: CameraHelper.AspectRatio = CameraHelper.AspectRatio.RATIO_16_9
        set(value) { field = value; invalidate() }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0); style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val horizonActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 200, 120)
        style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); style = Paint.Style.FILL
    }
    private val dotActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 200, 120); style = Paint.Style.FILL
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 255, 255)
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = 24f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    private val cropRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isActionMode) return

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        // Compute crop guide rectangle based on selected aspect ratio.
        // The guide shows what the recorder will capture.
        // Use 90% of screen width as max, constrain by aspect and height.
        val guideW: Float
        val guideH: Float
        val ar = aspectRatio.w.toFloat() / aspectRatio.h.toFloat() // portrait W/H

        val maxW = w * 0.90f
        val maxH = h * 0.85f

        if (maxW / ar <= maxH) {
            guideW = maxW
            guideH = maxW / ar
        } else {
            guideH = maxH
            guideW = maxH * ar
        }

        cropRect.set(cx - guideW / 2f, cy - guideH / 2f, cx + guideW / 2f, cy + guideH / 2f)

        // Dark mask outside crop
        canvas.drawRect(0f, 0f, w, cropRect.top, maskPaint)
        canvas.drawRect(0f, cropRect.bottom, w, h, maskPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, maskPaint)
        canvas.drawRect(cropRect.right, cropRect.top, w, cropRect.bottom, maskPaint)

        // Crop border
        canvas.drawRoundRect(cropRect, 4f, 4f, borderPaint)

        // Horizon line + level indicator
        val degrees = Math.toDegrees(rollAngle.toDouble()).toFloat()
        val isLevel = Math.abs(degrees) < 1.0f
        val lPaint = if (isLevel) horizonActivePaint else horizonPaint
        val dPaint = if (isLevel) dotActivePaint else dotPaint

        val lineLen = guideW * 0.28f
        val cropCx = cropRect.centerX()
        val cropCy = cropRect.centerY()

        canvas.save()
        canvas.rotate(degrees, cropCx, cropCy)
        canvas.drawLine(cropCx - lineLen, cropCy, cropCx + lineLen, cropCy, lPaint)
        canvas.drawCircle(cropCx - lineLen, cropCy, 3.5f, dPaint)
        canvas.drawCircle(cropCx + lineLen, cropCy, 3.5f, dPaint)
        canvas.restore()

        // Center crosshair
        val cs = 10f
        canvas.drawLine(cropCx - cs, cropCy, cropCx + cs, cropCy, crossPaint)
        canvas.drawLine(cropCx, cropCy - cs, cropCx, cropCy + cs, crossPaint)

        // Angle readout
        val angleText = String.format("%.1f°", degrees)
        textPaint.color = if (isLevel) Color.argb(200, 0, 200, 120) else Color.argb(140, 255, 255, 255)
        canvas.drawText(angleText, cropCx, cropRect.bottom - 14f, textPaint)
    }
}
