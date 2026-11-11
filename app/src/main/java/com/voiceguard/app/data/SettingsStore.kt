package com.voiceguard.app.data

import android.content.Context
import android.media.MediaRecorder
import com.voiceguard.app.model.CaptureMode

/** Small wrapper around SharedPreferences. Everything stays on the phone. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("voiceguard_prefs", Context.MODE_PRIVATE)

    /** Smoothed AI-voice score at or above which a warning is raised (0.30 to 0.90). */
    var threshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, 0.50f)
        set(value) = prefs.edit().putFloat(KEY_THRESHOLD, value.coerceIn(0.30f, 0.90f)).apply()

    /** Window length in seconds: 2, 3 or 4 (proposal: 2 to 4 second windows). */
    var windowSeconds: Int
        get() = prefs.getInt(KEY_WINDOW, 4).coerceIn(2, 4)
        set(value) = prefs.edit().putInt(KEY_WINDOW, value.coerceIn(2, 4)).apply()

    /** 0 = MIC, 1 = VOICE_RECOGNITION, 2 = UNPROCESSED (used for the capture-mode study). */
    var audioSourceChoice: Int
        get() = prefs.getInt(KEY_SOURCE, 0)
        set(value) = prefs.edit().putInt(KEY_SOURCE, value).apply()

    var familyReminder: String
        get() = prefs.getString(KEY_FAMILY, DEFAULT_FAMILY) ?: DEFAULT_FAMILY
        set(value) = prefs.edit().putString(KEY_FAMILY, value.trim().ifEmpty { DEFAULT_FAMILY }).apply()

    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    var keywordsEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYWORDS, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYWORDS, value).apply()

    var lastMode: CaptureMode
        get() = try {
            CaptureMode.valueOf(prefs.getString(KEY_MODE, CaptureMode.SPEAKERPHONE.name)!!)
        } catch (e: Exception) {
            CaptureMode.SPEAKERPHONE
        }
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** Maps the saved choice to an Android AudioSource constant. */
    fun androidAudioSource(): Int = when (audioSourceChoice) {
        1 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        2 -> MediaRecorder.AudioSource.UNPROCESSED
        else -> MediaRecorder.AudioSource.MIC
    }

    companion object {
        const val DEFAULT_FAMILY =
            "Ask the caller for your private family code word before you send money or share any code."

        private const val KEY_THRESHOLD = "threshold"
        private const val KEY_WINDOW = "window_seconds"
        private const val KEY_SOURCE = "audio_source"
        private const val KEY_FAMILY = "family_reminder"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_OVERLAY = "overlay"
        private const val KEY_KEYWORDS = "keywords_enabled"
        private const val KEY_MODE = "last_mode"
    }
}
