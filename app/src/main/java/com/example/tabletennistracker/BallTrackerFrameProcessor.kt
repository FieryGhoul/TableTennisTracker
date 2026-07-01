package com.example.tabletennistracker

class BallTrackerFrameProcessor(
    private val onResult: (TrackingFrameResult) -> Unit,
) {
    fun process(frameData: YuvFrameData) {
        onResult(NativeBallTracker.detect(frameData))
    }

    fun reset() = Unit
}
