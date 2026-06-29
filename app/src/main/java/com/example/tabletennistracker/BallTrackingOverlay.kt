package com.example.tabletennistracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class BallTrackingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 255, 122)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
    }

    private var trackerResult: TrackerResult? = null

    fun updateResult(result: TrackerResult?) {
        trackerResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val result = trackerResult ?: return
        val mapped = RectF(
            result.normalizedBox.left * width,
            result.normalizedBox.top * height,
            result.normalizedBox.right * width,
            result.normalizedBox.bottom * height,
        )

        canvas.drawRect(mapped, boxPaint)
        canvas.drawCircle(mapped.centerX(), mapped.centerY(), max(6f, mapped.width() * 0.06f), centerPaint)
        canvas.drawText(
            "Ball ${(result.confidence * 100f).toInt()}%",
            mapped.left,
            max(36f, mapped.top - 18f),
            textPaint,
        )
    }
}
