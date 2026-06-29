package com.example.tabletennistracker

import android.graphics.RectF

data class TrackerResult(
    val normalizedBox: RectF,
    val confidence: Float,
)
