package com.borealnetwork.facecheck.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Visual target for the face and liveness progress, drawn above the camera preview. */
internal class FaceGuideOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val faceBounds = RectF()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 5, 10, 18) }
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(117, 224, 184)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 12f
    }

    private var presentation = CapturePresentation("Prepárate", "Preparando", 0f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
    }

    fun render(value: CapturePresentation) {
        presentation = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateFaceBounds()
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawOval(faceBounds, cutoutPaint)
        canvas.restoreToCount(layer)

        canvas.drawOval(faceBounds, borderPaint)
        canvas.drawArc(faceBounds, -90f, presentation.ringProgress * 360f, false, progressPaint)
    }

    private fun updateFaceBounds() {
        val frameWidth = min(width * 0.76f, height * 0.54f)
        val frameHeight = frameWidth * 1.28f
        val left = (width - frameWidth) / 2f
        val top = height * 0.16f
        faceBounds.set(left, top, left + frameWidth, min(height * 0.78f, top + frameHeight))
    }
}
