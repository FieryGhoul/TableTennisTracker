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

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 255, 122)
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val selectedCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 198, 59)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
    }

    private val candidateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 226, 150)
        textSize = 26f
    }

    private val testPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private var frameResult: TrackingFrameResult? = null
    private var coordinateMapper: CameraCoordinateMapper? = null
    private var showCandidateDots = false

    fun updateFrameResult(result: TrackingFrameResult?) {
        frameResult = result
        invalidate()
    }

    fun updateCoordinateMapper(mapper: CameraCoordinateMapper?) {
        coordinateMapper = mapper
        invalidate()
    }

    fun setShowCandidateDots(show: Boolean) {
        showCandidateDots = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val mapper = coordinateMapper ?: return
        if (!mapper.hasConfig()) {
            return
        }

        if (showCandidateDots) {
            drawCenterTestPoint(canvas, mapper)
        }

        val result = frameResult ?: return
        if (showCandidateDots) {
            result.candidates.forEachIndexed { index, candidate ->
                val mappedRect = mapper.mapRect(candidate.imageRect)
                val center = mapper.mapPoint(candidate.imageRect.centerX(), candidate.imageRect.centerY())
                canvas.drawCircle(center.x, center.y, 8f, candidatePaint)
                canvas.drawText(
                    "#${index + 1} ${candidate.score.format2()}",
                    mappedRect.left,
                    max(28f, mappedRect.top - 12f),
                    candidateTextPaint,
                )
            }
        }

        val trackerResult = result.trackerResult ?: return
        val mappedRect = mapper.mapRect(trackerResult.imageRect)
        val mappedCenter = mapper.mapPoint(trackerResult.centerX, trackerResult.centerY)
        canvas.drawRect(mappedRect, selectedPaint)
        canvas.drawCircle(mappedCenter.x, mappedCenter.y, 9f, selectedCenterPaint)
        canvas.drawText(
            "Ball ${(trackerResult.confidence * 100f).toInt()}%",
            mappedRect.left,
            max(36f, mappedRect.top - 18f),
            textPaint,
        )
    }

    private fun drawCenterTestPoint(canvas: Canvas, mapper: CameraCoordinateMapper) {
        val config = mapper.currentConfig() ?: return
        val mappedCenter = mapper.mapPoint(
            config.imageWidth * 0.5f,
            config.imageHeight * 0.5f,
        )
        canvas.drawCircle(mappedCenter.x, mappedCenter.y, 10f, testPointPaint)
        canvas.drawText(
            "Image center",
            mappedCenter.x + 14f,
            max(28f, mappedCenter.y - 14f),
            candidateTextPaint,
        )
    }

    private fun Float.format2(): String = String.format("%.2f", this)
}
