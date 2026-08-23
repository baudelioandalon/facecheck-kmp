package com.borealnetwork.facecheck.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View

/** Visual target for the INE front and back, drawn above the camera preview. */
internal class DocumentGuideOverlay @JvmOverloads constructor(
    context: Context,
    private val geometry: DocumentGuideGeometry,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 5, 10, 18) }
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
    }

    fun setLighting(value: FaceGuideLighting) {
        when (value) {
            FaceGuideLighting.Normal -> {
                dimPaint.color = Color.argb(178, 5, 10, 18)
                borderPaint.color = Color.argb(220, 255, 255, 255)
                borderPaint.strokeWidth = 5f
                haloPaint.color = Color.TRANSPARENT
                haloPaint.strokeWidth = 0f
            }
            FaceGuideLighting.LowLight -> {
                dimPaint.color = Color.WHITE
                borderPaint.color = Color.WHITE
                borderPaint.strokeWidth = 7f
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
        val documentBounds = geometry.rectangleBounds() ?: return
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(documentBounds, cutoutPaint)
        canvas.restoreToCount(layer)

        canvas.drawRect(documentBounds, haloPaint)
        canvas.drawRect(documentBounds, borderPaint)
    }
}
