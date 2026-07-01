package com.example.tabletennistracker

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.min

class CameraCoordinateMapper {
    data class Config(
        val imageWidth: Int,
        val imageHeight: Int,
        val viewWidth: Int,
        val viewHeight: Int,
        val rotationDegrees: Int,
        val mirrorHorizontally: Boolean,
    )

    private var config: Config? = null
    private val imageToViewMatrix = Matrix()

    fun update(newConfig: Config): Matrix {
        config = newConfig
        imageToViewMatrix.reset()

        val imageRect = RectF(0f, 0f, newConfig.imageWidth.toFloat(), newConfig.imageHeight.toFloat())
        val imageCenterX = imageRect.centerX()
        val imageCenterY = imageRect.centerY()

        imageToViewMatrix.postTranslate(-imageCenterX, -imageCenterY)
        if (newConfig.mirrorHorizontally) {
            imageToViewMatrix.postScale(-1f, 1f)
        }
        imageToViewMatrix.postRotate(newConfig.rotationDegrees.toFloat())

        val rotatedBounds = RectF(imageRect)
        imageToViewMatrix.mapRect(rotatedBounds)
        imageToViewMatrix.postTranslate(-rotatedBounds.left, -rotatedBounds.top)

        val rotatedWidth = rotatedBounds.width()
        val rotatedHeight = rotatedBounds.height()
        val scale = min(
            newConfig.viewWidth / rotatedWidth,
            newConfig.viewHeight / rotatedHeight,
        )
        val scaledWidth = rotatedWidth * scale
        val scaledHeight = rotatedHeight * scale
        val dx = (newConfig.viewWidth - scaledWidth) * 0.5f
        val dy = (newConfig.viewHeight - scaledHeight) * 0.5f

        imageToViewMatrix.postScale(scale, scale)
        imageToViewMatrix.postTranslate(dx, dy)

        return Matrix(imageToViewMatrix)
    }

    fun hasConfig(): Boolean = config != null

    fun currentConfig(): Config? = config

    fun mapRect(imageRect: RectF): RectF {
        val mapped = RectF(imageRect)
        imageToViewMatrix.mapRect(mapped)
        return mapped
    }

    fun mapPoint(x: Float, y: Float): PointF {
        val points = floatArrayOf(x, y)
        imageToViewMatrix.mapPoints(points)
        return PointF(points[0], points[1])
    }
}
