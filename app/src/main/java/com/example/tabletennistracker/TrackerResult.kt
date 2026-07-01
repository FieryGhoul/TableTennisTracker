package com.example.tabletennistracker

import android.graphics.RectF

data class DetectionCandidate(
    val imageRect: RectF,
    val score: Float,
    val colorScore: Float,
    val contrast: Float,
    val circularity: Float,
)

data class TrackerResult(
    val imageRect: RectF,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val candidates: List<DetectionCandidate> = emptyList(),
)

data class TrackingDebugInfo(
    val detectionFound: Boolean,
    val detectorCalled: Boolean,
    val detectionReason: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val imageFormat: Int,
    val timestampNs: Long,
    val rowStrideY: Int,
    val rowStrideUV: Int,
    val pixelStrideUV: Int,
    val candidateCount: Int,
    val totalComponents: Int,
    val rejectedTooSmall: Int,
    val rejectedTooLarge: Int,
    val rejectedEdge: Int,
    val rejectedAspect: Int,
    val rejectedFill: Int,
    val rejectedLuma: Int,
    val rejectedContrast: Int,
    val rejectedCircularity: Int,
    val rejectedColor: Int,
    val rejectedConfidence: Int,
)

data class TrackingFrameResult(
    val trackerResult: TrackerResult?,
    val debugInfo: TrackingDebugInfo,
    val candidates: List<DetectionCandidate> = emptyList(),
)

data class YuvFrameData(
    val yPlane: ByteArray,
    val uPlane: ByteArray,
    val vPlane: ByteArray,
    val width: Int,
    val height: Int,
    val imageFormat: Int,
    val timestampNs: Long,
    val rowStrideY: Int,
    val rowStrideUV: Int,
    val pixelStrideUV: Int,
    val rotationDegrees: Int,
)
