package com.voiceguard.app.ml

import com.voiceguard.app.model.RiskLevel

data class RiskDecision(val level: RiskLevel, val reason: String)

/**
 * Combines the two signals of the proposal.
 *
 *  1. Voice score: smoothed probability that the voice is AI-generated (main signal).
 *  2. Scam keywords: second, independent signal. A keyword is only a hint and never proves
 *     fraud, so keywords alone NEVER raise an alert. They only add to the alert together
 *     with an elevated voice score.
 */
object RiskEngine {

    /** Keyword weight (sum over distinct keywords heard recently) that counts as a strong hint. */
    const val STRONG_KEYWORD_WEIGHT = 3

    /** Fraction of the alert threshold above which keywords may tip the decision. */
    private const val ELEVATED_FRACTION = 0.7f

    fun evaluate(
        smoothedScore: Float,
        persistentlyHigh: Boolean,
        keywordWeight: Int,
        threshold: Float
    ): RiskDecision {
        val elevated = smoothedScore >= threshold * ELEVATED_FRACTION
        val high = smoothedScore >= threshold
        val strongKeywords = keywordWeight >= STRONG_KEYWORD_WEIGHT

        return when {
            high && persistentlyHigh ->
                RiskDecision(RiskLevel.ALERT, "The voice score stays high. The speaker may be an AI clone.")
            elevated && strongKeywords ->
                RiskDecision(
                    RiskLevel.ALERT,
                    "Raised voice score together with scam keywords. Treat this call as suspicious."
                )
            high ->
                RiskDecision(RiskLevel.CAUTION, "The voice score is high. Waiting to see if it stays high.")
            elevated ->
                RiskDecision(RiskLevel.CAUTION, "The voice score is raised. Stay careful.")
            else ->
                RiskDecision(RiskLevel.SAFE, "The voice looks genuine so far.")
        }
    }
}
