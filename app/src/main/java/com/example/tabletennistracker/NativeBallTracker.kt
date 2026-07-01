package com.example.tabletennistracker

import android.graphics.RectF

object NativeBallTracker {
    enum class BallProfile(val nativeValue: Int) {
        AUTO(0),
        ORANGE(1),
        WHITE(2),
    }

    private const val HEADER_SIZE = 21
    private const val CANDIDATE_STRIDE = 8

    private const val IDX_FOUND = 0
    private const val IDX_CANDIDATE_COUNT = 1
    private const val IDX_TOTAL_COMPONENTS = 2
    private const val IDX_REJECT_TOO_SMALL = 3
    private const val IDX_REJECT_TOO_LARGE = 4
    private const val IDX_REJECT_EDGE = 5
    private const val IDX_REJECT_ASPECT = 6
    private const val IDX_REJECT_FILL = 7
    private const val IDX_REJECT_LUMA = 8
    private const val IDX_REJECT_CONTRAST = 9
    private const val IDX_REJECT_CIRCULARITY = 10
    private const val IDX_REJECT_COLOR = 11
    private const val IDX_REJECT_CONFIDENCE = 12
    private const val IDX_BEST_LEFT = 13
    private const val IDX_BEST_TOP = 14
    private const val IDX_BEST_RIGHT = 15
    private const val IDX_BEST_BOTTOM = 16
    private const val IDX_BEST_CONFIDENCE = 17
    private const val IDX_BEST_COLOR = 18
    private const val IDX_BEST_CONTRAST = 19
    private const val IDX_BEST_CIRCULARITY = 20

    init {
        System.loadLibrary("table_tennis_tracker")
    }

    @Volatile
    var selectedProfile: BallProfile = BallProfile.AUTO

    fun detect(frameData: YuvFrameData): TrackingFrameResult {
        val raw = detectBallDebug(
            yPlane = frameData.yPlane,
            uPlane = frameData.uPlane,
            vPlane = frameData.vPlane,
            width = frameData.width,
            height = frameData.height,
            rowStrideY = frameData.rowStrideY,
            rowStrideUV = frameData.rowStrideUV,
            pixelStrideUV = frameData.pixelStrideUV,
            ballProfile = selectedProfile.nativeValue,
        )

        if (raw == null || raw.size < HEADER_SIZE) {
            return TrackingFrameResult(
                trackerResult = null,
                debugInfo = TrackingDebugInfo(
                    detectionFound = false,
                    detectorCalled = true,
                    detectionReason = "Native detector returned no debug payload.",
                    frameWidth = frameData.width,
                    frameHeight = frameData.height,
                    imageFormat = frameData.imageFormat,
                    timestampNs = frameData.timestampNs,
                    rowStrideY = frameData.rowStrideY,
                    rowStrideUV = frameData.rowStrideUV,
                    pixelStrideUV = frameData.pixelStrideUV,
                    candidateCount = 0,
                    totalComponents = 0,
                    rejectedTooSmall = 0,
                    rejectedTooLarge = 0,
                    rejectedEdge = 0,
                    rejectedAspect = 0,
                    rejectedFill = 0,
                    rejectedLuma = 0,
                    rejectedContrast = 0,
                    rejectedCircularity = 0,
                    rejectedColor = 0,
                    rejectedConfidence = 0,
                ),
            )
        }

        val candidates = parseCandidates(raw)
        val detectionFound = raw[IDX_FOUND] > 0.5f
        val trackerResult = if (detectionFound) {
            val imageRect = RectF(
                raw[IDX_BEST_LEFT],
                raw[IDX_BEST_TOP],
                raw[IDX_BEST_RIGHT],
                raw[IDX_BEST_BOTTOM],
            )
            TrackerResult(
                imageRect = imageRect,
                confidence = raw[IDX_BEST_CONFIDENCE],
                centerX = imageRect.centerX(),
                centerY = imageRect.centerY(),
                radius = maxOf(imageRect.width(), imageRect.height()) * 0.5f,
                candidates = candidates,
            )
        } else {
            null
        }

        val debugInfo = TrackingDebugInfo(
            detectionFound = detectionFound,
            detectorCalled = true,
            detectionReason = buildDetectionReason(raw, detectionFound, candidates),
            frameWidth = frameData.width,
            frameHeight = frameData.height,
            imageFormat = frameData.imageFormat,
            timestampNs = frameData.timestampNs,
            rowStrideY = frameData.rowStrideY,
            rowStrideUV = frameData.rowStrideUV,
            pixelStrideUV = frameData.pixelStrideUV,
            candidateCount = candidates.size,
            totalComponents = raw[IDX_TOTAL_COMPONENTS].toInt(),
            rejectedTooSmall = raw[IDX_REJECT_TOO_SMALL].toInt(),
            rejectedTooLarge = raw[IDX_REJECT_TOO_LARGE].toInt(),
            rejectedEdge = raw[IDX_REJECT_EDGE].toInt(),
            rejectedAspect = raw[IDX_REJECT_ASPECT].toInt(),
            rejectedFill = raw[IDX_REJECT_FILL].toInt(),
            rejectedLuma = raw[IDX_REJECT_LUMA].toInt(),
            rejectedContrast = raw[IDX_REJECT_CONTRAST].toInt(),
            rejectedCircularity = raw[IDX_REJECT_CIRCULARITY].toInt(),
            rejectedColor = raw[IDX_REJECT_COLOR].toInt(),
            rejectedConfidence = raw[IDX_REJECT_CONFIDENCE].toInt(),
        )

        return TrackingFrameResult(
            trackerResult = trackerResult,
            debugInfo = debugInfo,
            candidates = candidates,
        )
    }

    private fun parseCandidates(raw: FloatArray): List<DetectionCandidate> {
        val available = ((raw.size - HEADER_SIZE).coerceAtLeast(0)) / CANDIDATE_STRIDE
        val requested = raw[IDX_CANDIDATE_COUNT].toInt().coerceAtLeast(0)
        val count = minOf(available, requested)
        return List(count) { index ->
            val offset = HEADER_SIZE + index * CANDIDATE_STRIDE
            DetectionCandidate(
                imageRect = RectF(
                    raw[offset],
                    raw[offset + 1],
                    raw[offset + 2],
                    raw[offset + 3],
                ),
                score = raw[offset + 4],
                colorScore = raw[offset + 5],
                contrast = raw[offset + 6],
                circularity = raw[offset + 7],
            )
        }
    }

    private fun buildDetectionReason(
        raw: FloatArray,
        detectionFound: Boolean,
        candidates: List<DetectionCandidate>,
    ): String {
        if (detectionFound) {
            return "Selected candidate score=${formatScore(raw[IDX_BEST_CONFIDENCE])} from ${candidates.size} debug candidates."
        }

        val totalComponents = raw[IDX_TOTAL_COMPONENTS].toInt()
        if (totalComponents == 0) {
            return "No color-matched components found in the full frame."
        }

        val rejectReasons = listOf(
            "too small" to raw[IDX_REJECT_TOO_SMALL].toInt(),
            "too large" to raw[IDX_REJECT_TOO_LARGE].toInt(),
            "edge clipped" to raw[IDX_REJECT_EDGE].toInt(),
            "bad aspect" to raw[IDX_REJECT_ASPECT].toInt(),
            "bad fill" to raw[IDX_REJECT_FILL].toInt(),
            "bad luma" to raw[IDX_REJECT_LUMA].toInt(),
            "low contrast" to raw[IDX_REJECT_CONTRAST].toInt(),
            "low circularity" to raw[IDX_REJECT_CIRCULARITY].toInt(),
            "wrong color" to raw[IDX_REJECT_COLOR].toInt(),
            "low confidence" to raw[IDX_REJECT_CONFIDENCE].toInt(),
        )

        val dominantReject = rejectReasons.maxByOrNull { it.second }
        return if (dominantReject == null || dominantReject.second <= 0) {
            "No candidate passed the final score threshold."
        } else {
            "No final ball selected. Dominant reject: ${dominantReject.first} (${dominantReject.second}/$totalComponents)."
        }
    }

    private fun formatScore(value: Float): String = String.format("%.2f", value)

    private external fun detectBallDebug(
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
