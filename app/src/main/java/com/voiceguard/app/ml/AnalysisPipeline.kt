package com.voiceguard.app.ml

import android.util.Log
import com.voiceguard.app.VoiceGuardApp
import com.voiceguard.app.data.AlertRecord
import com.voiceguard.app.data.CallSession
import com.voiceguard.app.data.KeywordHitRecord
import com.voiceguard.app.data.ScamKeyword
import com.voiceguard.app.data.WindowScore
import com.voiceguard.app.model.AlertEvent
import com.voiceguard.app.model.CaptureMode
import com.voiceguard.app.model.MonitorBus
import com.voiceguard.app.model.MonitorState
import com.voiceguard.app.model.RiskLevel
import com.voiceguard.app.model.Trend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The common analysis path used by all three capture modes (proposal Figure 2):
 *
 *   audio chunks -> 2 to 4 s windows (50% overlap) -> AASIST-L on the phone -> smoothing
 *   -> risk decision (voice score + scam keywords) -> warning banner, alert, database row.
 *
 * Audio chunks (16 kHz, mono, 16-bit, at most [MAX_CHUNK] samples each) are passed to [feed].
 * Only scores, alerts and matched keywords are written to the database, never audio.
 */
class AnalysisPipeline(
    private val app: VoiceGuardApp,
    private val mode: CaptureMode,
    private val sourceLabel: String,
    private val onAlert: (AlertEvent) -> Unit = {}
) {
    private val settings = app.settings
    private val db = app.db
    private val classifier = app.classifier
    private val keywordEngine = app.keywordEngine
    private val mutex = Mutex()

    // Settings are read once so that one session stays consistent.
    private val threshold = settings.threshold
    private val windowSamples =
        if (settings.windowSeconds >= 4) VoiceGuardClassifier.MODEL_SAMPLES
        else settings.windowSeconds * SAMPLE_RATE
    private val hopSamples = windowSamples / 2

    private val buffer = FloatArray(windowSamples + MAX_CHUNK)
    private var filled = 0
    private val smoother = ScoreSmoother(5)

    private var session = CallSession(startedAt = 0, captureMode = mode.name, sourceLabel = sourceLabel)
    private var sessionId = 0L
    private var windowIndex = 0
    private var scoredCount = 0
    private var consecutiveHigh = 0
    private var latencySum = 0L
    private var scoreSum = 0.0
    private var maxScore = 0f
    private var alertCount = 0
    private var lastLevel = RiskLevel.SAFE
    private var lastAlertAt = 0L
    private var lastRaw = 0f
    private var lastSmoothed = 0f
    private var lastTrend = Trend.BASELINE
    private var lastLatency = 0L

    private var spotter: KeywordSpotter? = null
    private val keywordInfo = HashMap<Long, ScamKeyword>()
    private val keywordLastSeen = HashMap<Long, Long>()
    private val keywordLastRecorded = HashMap<Long, Long>()
    private val sessionHitLabels = LinkedHashSet<String>()

    suspend fun begin() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val ready = classifier.isReady()
            session = CallSession(
                startedAt = now,
                captureMode = mode.name,
                sourceLabel = sourceLabel,
                modelLoaded = ready
            )
            sessionId = db.sessions().insert(session)
            session = session.copy(id = sessionId)

            if (settings.keywordsEnabled) {
                VoskModels.prepare(app)
                val sp = KeywordSpotter()
                if (sp.available) spotter = sp else sp.close()
            }

            MonitorBus.update {
                MonitorState(
                    running = true,
                    mode = mode,
                    modelReady = ready,
                    sessionId = sessionId,
                    sourceLabel = sourceLabel,
                    progress = if (mode == CaptureMode.RECORDED_CALL) 0f else -1f,
                    message = if (ready) {
                        "Collecting the first window of audio..."
                    } else {
                        "Voice model not loaded. Only scam keyword spotting is active."
                    }
                )
            }
        }
    }

    /** Feeds one chunk of 16 kHz mono PCM. The chunk must have at most [MAX_CHUNK] samples. */
    suspend fun feed(data: ShortArray) {
        require(data.size <= MAX_CHUNK) { "Chunk too large" }
        mutex.withLock {
            val sp = spotter
            if (sp != null) {
                val text = try {
                    sp.accept(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Keyword spotting failed", e)
                    ""
                }
                if (text.isNotEmpty()) handleTranscriptLocked(text)
            }

            for (i in data.indices) buffer[filled + i] = data[i] / 32768.0f
            filled += data.size

            while (filled >= windowSamples) {
                val window = buffer.copyOf(windowSamples)
                System.arraycopy(buffer, hopSamples, buffer, 0, filled - hopSamples)
                filled -= hopSamples
                scoreWindowLocked(window)
            }
        }
    }

    /** Text typed in the transcript simulator, or text from another source. */
    suspend fun submitText(text: String) {
        mutex.withLock { handleTranscriptLocked(text) }
    }

    fun setProgress(progress: Float) {
        MonitorBus.update { it.copy(progress = progress) }
    }

    suspend fun finish(cancelled: Boolean = false, error: String? = null) {
        mutex.withLock {
            val sp = spotter
            spotter = null
            if (sp != null) {
                if (!cancelled && error == null) {
                    try {
                        val text = sp.finalText()
                        if (text.isNotEmpty()) handleTranscriptLocked(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "Final keyword result failed", e)
                    }
                }
                sp.close()
            }

            if (!cancelled && error == null) {
                // Very short recording: score what we have, padded to one window.
                if (scoredCount == 0 && filled >= SAMPLE_RATE) {
                    scoreWindowLocked(buffer.copyOf(filled))
                }
                if (scoredCount > 0) evaluateLocked(true)
            }

            val verdict = when {
                error != null -> "ERROR"
                cancelled -> "CANCELLED"
                scoredCount == 0 -> "NO_DATA"
                alertCount > 0 -> "SUSPICIOUS"
                else -> "GENUINE"
            }
            val avgLatency = if (scoredCount > 0) latencySum.toFloat() / scoredCount else 0f
            val avgScore = if (scoredCount > 0) (scoreSum / scoredCount).toFloat() else 0f
            session = session.copy(
                endedAt = System.currentTimeMillis(),
                windowCount = scoredCount,
                avgSpoofScore = avgScore,
                maxSpoofScore = maxScore,
                alertCount = alertCount,
                avgLatencyMs = avgLatency,
                verdict = verdict
            )
            try {
                db.sessions().update(session)
            } catch (e: Exception) {
                Log.e(TAG, "Could not save session", e)
            }

            val showResult = mode == CaptureMode.RECORDED_CALL && scoredCount > 0 && error == null && !cancelled
            val finalLevel = when {
                !showResult -> RiskLevel.IDLE
                alertCount > 0 -> RiskLevel.ALERT
                else -> RiskLevel.SAFE
            }
            val message = when {
                error != null -> "Analysis failed: $error"
                cancelled -> "Stopped. Partial results were saved to History."
                scoredCount == 0 && !classifier.isReady() ->
                    "No voice score: the voice model is not loaded. Keywords were still checked."
                scoredCount == 0 -> "Not enough audio for a score (at least 1 second is needed)."
                alertCount > 0 -> "Finished: suspicious. $alertCount alert(s) were raised. Saved to History."
                else -> "Finished: the voice looked genuine. Saved to History."
            }
            MonitorBus.update {
                it.copy(
                    running = false,
                    finished = true,
                    level = finalLevel,
                    progress = -1f,
                    message = message,
                    reason = message
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Everything below must be called with the mutex held.
    // ---------------------------------------------------------------------------------------

    private suspend fun scoreWindowLocked(window: FloatArray) {
        val index = windowIndex++
        val score = try {
            classifier.score(window)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            MonitorBus.update { it.copy(message = "Inference error: ${e.message}") }
            return
        }
        if (score == null) {
            MonitorBus.update {
                it.copy(modelReady = false, message = "Voice model not loaded. Only keyword spotting is active.")
            }
            return
        }

        scoredCount++
        latencySum += score.latencyMs
        scoreSum += score.spoofProb
        if (score.spoofProb > maxScore) maxScore = score.spoofProb

        lastRaw = score.spoofProb
        lastSmoothed = smoother.add(score.spoofProb)
        lastTrend = smoother.trend()
        lastLatency = score.latencyMs
        consecutiveHigh = if (lastSmoothed >= threshold) consecutiveHigh + 1 else 0

        try {
            db.windows().insert(
                WindowScore(
                    sessionId = sessionId,
                    windowIndex = index,
                    startMs = index.toLong() * hopSamples * 1000L / SAMPLE_RATE,
                    rawScore = lastRaw,
                    smoothedScore = lastSmoothed,
                    trend = lastTrend.name,
                    latencyMs = lastLatency,
                    createdAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not save window score", e)
        }
        evaluateLocked(false)
    }

    private suspend fun handleTranscriptLocked(text: String) {
        val hits = keywordEngine.scan(text)
        if (hits.isEmpty()) return
        val now = System.currentTimeMillis()
        var changed = false
        for (k in hits) {
            keywordInfo[k.id] = k
            keywordLastSeen[k.id] = now
            val lastRecorded = keywordLastRecorded[k.id]
            if (lastRecorded == null || now - lastRecorded > KEYWORD_COOLDOWN_MS) {
                keywordLastRecorded[k.id] = now
                try {
                    db.keywordHits().insert(
                        KeywordHitRecord(
                            sessionId = sessionId,
                            keywordId = k.id,
                            matchedWord = k.keyword,
                            category = k.category,
                            createdAt = now
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not save keyword hit", e)
                }
                changed = true
            }
            if (sessionHitLabels.add("${k.keyword} (${k.category})")) changed = true
        }
        if (!changed) return
        if (scoredCount > 0) {
            evaluateLocked(false)
        } else {
            val weight = keywordWeightNow(now)
            MonitorBus.update {
                it.copy(keywordHits = sessionHitLabels.toList(), keywordWeight = weight)
            }
        }
    }

    private fun keywordWeightNow(now: Long): Int {
        var sum = 0
        for ((id, seen) in keywordLastSeen) {
            if (now - seen <= KEYWORD_WINDOW_MS) sum += keywordInfo[id]?.weight ?: 0
        }
        return sum
    }

    private suspend fun evaluateLocked(finalPass: Boolean) {
        val now = System.currentTimeMillis()
        val keywordWeight = keywordWeightNow(now)
        val persistent =
            consecutiveHigh >= REQUIRED_CONSECUTIVE || (finalPass && lastSmoothed >= threshold)
        val decision = RiskEngine.evaluate(lastSmoothed, persistent, keywordWeight, threshold)

        if (decision.level == RiskLevel.ALERT &&
            (lastLevel != RiskLevel.ALERT || now - lastAlertAt > ALERT_COOLDOWN_MS)
        ) {
            alertCount++
            lastAlertAt = now
            try {
                db.alerts().insert(
                    AlertRecord(
                        sessionId = sessionId,
                        createdAt = now,
                        confidence = lastSmoothed,
                        trend = lastTrend.label,
                        level = decision.level.name,
                        reason = decision.reason
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Could not save alert", e)
            }
            onAlert(AlertEvent(lastSmoothed, lastTrend, decision.reason))
        }
        lastLevel = decision.level

        val avgLatency = if (scoredCount > 0) latencySum.toFloat() / scoredCount else 0f
        MonitorBus.update {
            it.copy(
                running = true,
                finished = false,
                mode = mode,
                level = decision.level,
                rawScore = lastRaw,
                spoofScore = lastSmoothed,
                trend = lastTrend,
                lastLatencyMs = lastLatency,
                avgLatencyMs = avgLatency,
                windowsScored = scoredCount,
                modelReady = true,
                keywordHits = sessionHitLabels.toList(),
                keywordWeight = keywordWeight,
                reason = decision.reason,
                message = decision.reason,
                sessionId = sessionId
            )
        }
    }

    companion object {
        private const val TAG = "AnalysisPipeline"
        const val SAMPLE_RATE = 16000
        const val MAX_CHUNK = 4096

        /** The smoothed score must stay high for this many windows before the warning is shown. */
        private const val REQUIRED_CONSECUTIVE = 2
        private const val ALERT_COOLDOWN_MS = 30_000L
        private const val KEYWORD_COOLDOWN_MS = 30_000L
        private const val KEYWORD_WINDOW_MS = 90_000L
    }
}
