package com.example.tabletennistracker

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class BallTrackerAnalyzer(
    private val onResult: (TrackerResult?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)
    private var previousLuma: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0
    private var lastAcceptedResult: TrackerResult? = null
    private var pendingCandidate: TrackerResult? = null
    private var pendingFrames = 0
    private var consecutiveAccepts = 0
    private var consecutiveMisses = 0

    override fun analyze(image: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            image.close()
            return
        }

        try {
            val yPlane = image.planes[0].buffer.toByteArray()
            val uPlane = image.planes[1].buffer.toByteArray()
            val vPlane = image.planes[2].buffer.toByteArray()

            val result = NativeBallTracker.detect(
                yPlane = yPlane,
                uPlane = uPlane,
                vPlane = vPlane,
                width = image.width,
                height = image.height,
                rowStrideY = image.planes[0].rowStride,
                rowStrideUV = image.planes[1].rowStride,
                pixelStrideUV = image.planes[1].pixelStride,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
            val filteredResult = filterFalsePositives(
                candidate = result,
                currentLuma = yPlane,
                width = image.width,
                height = image.height,
                rowStrideY = image.planes[0].rowStride,
            )
            onResult(filteredResult)
            previousLuma = yPlane
            previousWidth = image.width
            previousHeight = image.height
        } finally {
            isProcessing.set(false)
            image.close()
        }
    }

    private fun filterFalsePositives(
        candidate: TrackerResult?,
        currentLuma: ByteArray,
        width: Int,
        height: Int,
        rowStrideY: Int,
    ): TrackerResult? {
        if (candidate == null) {
            consecutiveAccepts = 0
            pendingCandidate = null
            pendingFrames = 0
            consecutiveMisses += 1
            if (consecutiveMisses >= 3) {
                lastAcceptedResult = null
            }
            return null
        }

        val motionScore = computeMotionScore(candidate, currentLuma, width, height, rowStrideY)
        val nearPreviousTrack = isNearPreviousTrack(candidate)
        val profileSelected = NativeBallTracker.selectedProfile != NativeBallTracker.BallProfile.AUTO
        val activeMotion = motionScore >= 6f
        val continuingTrack = nearPreviousTrack && motionScore >= 2f
        val stableConfirmedTrack = nearPreviousTrack &&
            consecutiveAccepts >= 1 &&
            candidate.confidence >= if (profileSelected) 0.46f else 0.62f
        val startupLock = canStartTracking(candidate, nearPreviousTrack, motionScore, profileSelected)

        if (!(activeMotion || continuingTrack || stableConfirmedTrack || startupLock)) {
            consecutiveAccepts = 0
            consecutiveMisses += 1
            if (consecutiveMisses >= 3) {
                lastAcceptedResult = null
            }
            return null
        }

        consecutiveAccepts += 1
        consecutiveMisses = 0
        lastAcceptedResult = candidate
        pendingCandidate = candidate
        pendingFrames = min(pendingFrames + 1, 8)
        return candidate
    }

    private fun canStartTracking(
        candidate: TrackerResult,
        nearPreviousTrack: Boolean,
        motionScore: Float,
        profileSelected: Boolean,
    ): Boolean {
        if (nearPreviousTrack && candidate.confidence >= if (profileSelected) 0.50f else 0.66f) {
            return true
        }

        val previousPending = pendingCandidate
        if (previousPending != null && isNear(previousPending, candidate)) {
            pendingFrames += 1
        } else {
            pendingCandidate = candidate
            pendingFrames = 1
        }

        val confidentEnough = candidate.confidence >= if (profileSelected) 0.42f else 0.60f
        val repeatedEnough = pendingFrames >= if (profileSelected) 1 else 2
        val someMotion = motionScore >= if (profileSelected) 0.8f else 2f
        val veryConfident = candidate.confidence >= if (profileSelected) 0.60f else 0.78f

        return (confidentEnough && repeatedEnough) || (veryConfident && someMotion)
    }

    private fun computeMotionScore(
        candidate: TrackerResult,
        currentLuma: ByteArray,
        width: Int,
        height: Int,
        rowStrideY: Int,
    ): Float {
        val previous = previousLuma ?: return 0f
        if (previousWidth != width || previousHeight != height) {
            return 0f
        }

        val rect = candidate.normalizedBox
        val left = max(0, (rect.left * width).toInt())
        val top = max(0, (rect.top * height).toInt())
        val right = min(width - 1, (rect.right * width).toInt())
        val bottom = min(height - 1, (rect.bottom * height).toInt())
        if (right <= left || bottom <= top) {
            return 0f
        }

        val sampleStep = max(2, min(right - left, bottom - top) / 8)
        var diffSum = 0f
        var count = 0

        var y = top
        while (y <= bottom) {
            var x = left
            while (x <= right) {
                val index = y * rowStrideY + x
                if (index < currentLuma.size && index < previous.size) {
                    diffSum += abs((currentLuma[index].toInt() and 0xFF) - (previous[index].toInt() and 0xFF))
                    count += 1
                }
                x += sampleStep
            }
            y += sampleStep
        }

        return if (count == 0) 0f else diffSum / count
    }

    private fun isNearPreviousTrack(candidate: TrackerResult): Boolean {
        val previous = lastAcceptedResult ?: return false
        return isNear(previous, candidate)
    }

    private fun isNear(previous: TrackerResult, candidate: TrackerResult): Boolean {
        val currentBox = candidate.normalizedBox
        val previousBox = previous.normalizedBox
        val currentCenterX = (currentBox.left + currentBox.right) * 0.5f
        val currentCenterY = (currentBox.top + currentBox.bottom) * 0.5f
        val previousCenterX = (previousBox.left + previousBox.right) * 0.5f
        val previousCenterY = (previousBox.top + previousBox.bottom) * 0.5f
        val centerDistance = hypot(currentCenterX - previousCenterX, currentCenterY - previousCenterY)
        val sizeAllowance = max(currentBox.width(), currentBox.height()) * 2.2f
        return centerDistance <= max(0.08f, sizeAllowance)
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        val bytes = ByteArray(remaining())
        get(bytes)
        return bytes
    }
}
