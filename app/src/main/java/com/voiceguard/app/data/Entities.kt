package com.voiceguard.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Part A of the VoiceGuard ER model (proposal Section 6.2).
 * Only scores, alerts and keyword hits are stored. Call audio is never stored.
 */

/** One monitored call or one analysed recording. */
@Entity(tableName = "call_sessions")
data class CallSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long = 0,
    /** SPEAKERPHONE, RECORDED_CALL or TEST_CALL */
    val captureMode: String,
    val sourceLabel: String,
    val windowCount: Int = 0,
    val avgSpoofScore: Float = 0f,
    val maxSpoofScore: Float = 0f,
    val alertCount: Int = 0,
    val avgLatencyMs: Float = 0f,
    /** IN_PROGRESS, GENUINE, SUSPICIOUS, NO_DATA, CANCELLED, ERROR or INTERRUPTED */
    val verdict: String = "IN_PROGRESS",
    val modelLoaded: Boolean = false
)

/** One 2 to 4 second window that was scored by the model. */
@Entity(
    tableName = "window_scores",
    foreignKeys = [ForeignKey(
        entity = CallSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class WindowScore(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val windowIndex: Int,
    val startMs: Long,
    /** Probability that the window is spoofed (single window, not smoothed). */
    val rawScore: Float,
    /** Moving average of the last windows. */
    val smoothedScore: Float,
    val trend: String,
    val latencyMs: Long,
    val createdAt: Long
)

/** A warning that was shown to the user. */
@Entity(
    tableName = "alerts",
    foreignKeys = [ForeignKey(
        entity = CallSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class AlertRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val createdAt: Long,
    val confidence: Float,
    val trend: String,
    val level: String,
    val reason: String,
    /** Set by the user in the session detail screen. Used for the false alarm rate. */
    val falseAlarm: Boolean = false
)

/** Entry of the scam keyword list (proposal Table 4). */
@Entity(tableName = "scam_keywords")
data class ScamKeyword(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val keyword: String,
    /** EN = English, UR = Urdu in Roman script, HI = Urdu/Hindi in Devanagari script */
    val language: String,
    /** 1 = weak hint (for example "bank"), 2 = medium, 3 = strong */
    val weight: Int,
    val isCustom: Boolean = false
)

/** A keyword that was heard during a session. */
@Entity(
    tableName = "keyword_hits",
    foreignKeys = [
        ForeignKey(
            entity = CallSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ScamKeyword::class,
            parentColumns = ["id"],
            childColumns = ["keywordId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("sessionId"), Index("keywordId")]
)
data class KeywordHitRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val keywordId: Long?,
    val matchedWord: String,
    val category: String,
    val createdAt: Long
)
