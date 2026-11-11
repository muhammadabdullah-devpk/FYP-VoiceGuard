package com.voiceguard.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: CallSession): Long

    @Update
    suspend fun update(session: CallSession)

    @Query("SELECT * FROM call_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<CallSession>>

    @Query("SELECT * FROM call_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<CallSession>

    @Query("SELECT * FROM call_sessions WHERE id = :id")
    suspend fun get(id: Long): CallSession?

    @Query("DELETE FROM call_sessions")
    suspend fun deleteAll()

    /** Sessions left open by a crash or a killed process are closed at the next app start. */
    @Query("UPDATE call_sessions SET verdict = 'INTERRUPTED' WHERE verdict = 'IN_PROGRESS'")
    suspend fun closeStaleSessions()
}

@Dao
interface WindowDao {
    @Insert
    suspend fun insert(score: WindowScore): Long

    @Query("SELECT * FROM window_scores WHERE sessionId = :sessionId ORDER BY windowIndex")
    suspend fun forSession(sessionId: Long): List<WindowScore>

    @Query("SELECT * FROM window_scores ORDER BY sessionId, windowIndex")
    suspend fun getAll(): List<WindowScore>
}

@Dao
interface AlertDao {
    @Insert
    suspend fun insert(alert: AlertRecord): Long

    @Query("SELECT * FROM alerts WHERE sessionId = :sessionId ORDER BY createdAt")
    suspend fun forSession(sessionId: Long): List<AlertRecord>

    @Query("UPDATE alerts SET falseAlarm = :flag WHERE id = :id")
    suspend fun setFalseAlarm(id: Long, flag: Boolean)

    @Query("SELECT COUNT(*) FROM alerts")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM alerts WHERE falseAlarm = 1")
    suspend fun countFalse(): Int

    @Query("SELECT sessionId, COUNT(*) AS total, SUM(falseAlarm) AS falseCount FROM alerts GROUP BY sessionId")
    suspend fun countsPerSession(): List<AlertCount>
}

data class AlertCount(val sessionId: Long, val total: Int, val falseCount: Int)

@Dao
interface KeywordHitDao {
    @Insert
    suspend fun insert(hit: KeywordHitRecord): Long

    @Query("SELECT * FROM keyword_hits WHERE sessionId = :sessionId ORDER BY createdAt")
    suspend fun forSession(sessionId: Long): List<KeywordHitRecord>
}

@Dao
interface KeywordDao {
    @Query("SELECT * FROM scam_keywords ORDER BY category, keyword")
    suspend fun getAll(): List<ScamKeyword>

    @Query("SELECT * FROM scam_keywords ORDER BY category, keyword")
    fun observeAll(): Flow<List<ScamKeyword>>

    @Query("SELECT COUNT(*) FROM scam_keywords")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(items: List<ScamKeyword>)

    @Insert
    suspend fun insert(item: ScamKeyword): Long

    @Query("DELETE FROM scam_keywords WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustom(id: Long)
}
