package com.borealnetwork.facecheck.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View

/** Visual target for the face and liveness progress, drawn above the camera preview. */
internal class FaceGuideOverlay @JvmOverloads constructor(
    context: Context,
    private val geometry: FaceGuideGeometry,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 5, 10, 18) }
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = 0f
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

    fun setLighting(value: FaceGuideLighting) {
        when (value) {
            FaceGuideLighting.Normal -> {
                dimPaint.color = Color.argb(178, 5, 10, 18)
                borderPaint.color = Color.argb(210, 255, 255, 255)
                borderPaint.strokeWidth = 4f
                progressPaint.color = Color.rgb(117, 224, 184)
                progressPaint.strokeWidth = 12f
                haloPaint.color = Color.TRANSPARENT
                haloPaint.strokeWidth = 0f
            }
            FaceGuideLighting.LowLight -> {
                dimPaint.color = Color.WHITE
                borderPaint.color = Color.WHITE
                borderPaint.strokeWidth = 7f
                progressPaint.color = Color.WHITE
                progressPaint.strokeWidth = 14f
                haloPaint.color = Color.argb(96, 255, 255, 255)
                haloPaint.strokeWidth = 28f
            }
        }
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        geometry.updateForViewport(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val faceBounds = geometry.ovalBounds() ?: return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawOval(faceBounds, cutoutPaint)
        canvas.restoreToCount(layer)

        canvas.drawOval(faceBounds, haloPaint)
        canvas.drawOval(faceBounds, borderPaint)
        canvas.drawArc(faceBounds, -90f, presentation.ringProgress * 360f, false, progressPaint)
    }
}
