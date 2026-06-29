package com.example.tabletennistracker

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object NativeBallTracker {
    enum class BallProfile(val nativeValue: Int) {
        AUTO(0),
        ORANGE(1),
        WHITE(2),
    }

    init {
        System.loadLibrary("table_tennis_tracker")
    }

    @Volatile
    var selectedProfile: BallProfile = BallProfile.AUTO

    fun detect(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        width: Int,
        height: Int,
        rowStrideY: Int,
        rowStrideUV: Int,
        pixelStrideUV: Int,
        rotationDegrees: Int,
    ): TrackerResult? {
        val raw = detectBall(
            yPlane = yPlane,
            uPlane = uPlane,
            vPlane = vPlane,
            width = width,
            height = height,
            rowStrideY = rowStrideY,
            rowStrideUV = rowStrideUV,
            pixelStrideUV = pixelStrideUV,
            ballProfile = selectedProfile.nativeValue,
        ) ?: return null

        if (raw.size < 5) {
            return null
        }

        val rect = mapRawRect(
            left = raw[0],
            top = raw[1],
            right = raw[2],
            bottom = raw[3],
            width = width.toFloat(),
            height = height.toFloat(),
            rotationDegrees = rotationDegrees,
        )

        return TrackerResult(rect, raw[4])
    }

    private fun mapRawRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        width: Float,
        height: Float,
        rotationDegrees: Int,
    ): RectF {
        val points = listOf(
            rotatePoint(left, top, width, height, rotationDegrees),
            rotatePoint(right, top, width, height, rotationDegrees),
            rotatePoint(left, bottom, width, height, rotationDegrees),
            rotatePoint(right, bottom, width, height, rotationDegrees),
        )

        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height

        val minX = points.minOf { it.x } / rotatedWidth
        val minY = points.minOf { it.y } / rotatedHeight
        val maxX = points.maxOf { it.x } / rotatedWidth
        val maxY = points.maxOf { it.y } / rotatedHeight

        return RectF(
            min(1f, max(0f, minX)),
            min(1f, max(0f, minY)),
            min(1f, max(0f, maxX)),
            min(1f, max(0f, maxY)),
        )
    }

    private fun rotatePoint(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDegrees: Int,
    ): PointF = when (rotationDegrees) {
        90 -> PointF(height - y, x)
        180 -> PointF(width - x, height - y)
        270 -> PointF(y, width - x)
        else -> PointF(x, y)
    }

    private external fun detectBall(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        width: Int,
        height: Int,
        rowStrideY: Int,
        rowStrideUV: Int,
        pixelStrideUV: Int,
        ballProfile: Int,
    ): FloatArray?
}
