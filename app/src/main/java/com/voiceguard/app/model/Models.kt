package com.voiceguard.app.model

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The three capture modes of the proposal (Section 5). */
enum class CaptureMode(val label: String) {
    /** Speakerphone with the microphone during a live call (best effort). */
    SPEAKERPHONE("Speakerphone (live)"),

    /** Analysis of a call recording made with consent (the guaranteed path). */
    RECORDED_CALL("Recorded call"),

    /** In-app test call. */
    TEST_CALL("In-app test call")
}

enum class RiskLevel { IDLE, SAFE, CAUTION, ALERT }

enum class Trend(val label: String) {
    BASELINE("Baseline"),
    RISING("Rising ↑"),
    STABLE("Stable →"),
    FALLING("Falling ↓")
}

/** Everything the UI needs to draw the warning banner. */
data class MonitorState(
    val running: Boolean = false,
    val finished: Boolean = false,
    val mode: CaptureMode? = null,
    val level: RiskLevel = RiskLevel.IDLE,
    val rawScore: Float = 0f,
    /** Smoothed probability that the voice is AI-generated. */
    val spoofScore: Float = 0f,
    val trend: Trend = Trend.BASELINE,
    val lastLatencyMs: Long = 0,
    val avgLatencyMs: Float = 0f,
    val windowsScored: Int = 0,
    val modelReady: Boolean = false,
    val keywordHits: List<String> = emptyList(),
    val keywordWeight: Int = 0,
    /** 0..1 while a recording is analysed, -1 when unknown or not applicable. */
    val progress: Float = -1f,
    val message: String = "",
    val reason: String = "",
    val sessionId: Long = 0,
    val sourceLabel: String = ""
)

/** Raised by the pipeline when a warning must be shown to the user. */
data class AlertEvent(
    val confidence: Float,
    val trend: Trend,
    val reason: String
)

/** Single shared channel between the analysis code (service or file analysis) and the screens. */
object MonitorBus {
    val state = MutableStateFlow(MonitorState())

    /** Typed text from the transcript simulator, delivered to the running session. */
    val transcriptInput = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun isBusy(): Boolean = state.value.running

    fun update(transform: (MonitorState) -> MonitorState) {
        state.update(transform)
    }

    fun submitTranscript(text: String) {
        transcriptInput.tryEmit(text)
    }
}
