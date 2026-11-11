package com.voiceguard.app.ml

import com.voiceguard.app.model.Trend

/**
 * Smooths window scores with a moving average and reports whether the smoothed
 * confidence is rising, stable or falling (proposal Section 5).
 */
class ScoreSmoother(private val size: Int = 5) {

    private val recent = ArrayDeque<Float>()
    private val smoothedHistory = ArrayList<Float>()

    fun add(score: Float): Float {
        recent.addLast(score)
        while (recent.size > size) recent.removeFirst()
        val smoothed = recent.average().toFloat()
        smoothedHistory.add(smoothed)
        return smoothed
    }

    /** Compares the newest smoothed value with the one three windows earlier. */
    fun trend(): Trend {
        if (smoothedHistory.size < 2) return Trend.BASELINE
        val current = smoothedHistory.last()
        val reference = smoothedHistory[maxOf(0, smoothedHistory.size - 4)]
        val delta = current - reference
        return when {
            delta > TREND_DELTA -> Trend.RISING
            delta < -TREND_DELTA -> Trend.FALLING
            else -> Trend.STABLE
        }
    }

    companion object {
        private const val TREND_DELTA = 0.05f
    }
}
