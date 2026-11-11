package com.voiceguard.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures 16 kHz mono PCM from the microphone (the model needs 16 kHz).
 *
 * Android does not give normal apps access to the call audio stream of the other speaker
 * (proposal reference [8]), so the live mode is "best effort": put the call on speakerphone
 * and the microphone hears the caller. If the preferred audio source cannot be opened,
 * the plain microphone source is used as a fallback.
 *
 * The capture loop only reads and forwards small chunks, so no audio is lost while the
 * model is busy. Scoring happens elsewhere.
 */
class AudioRecorderManager(
    private val preferredSource: Int,
    private val onChunk: (ShortArray) -> Unit,
    private val onError: (String) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var job: Job? = null

    /** The audio source that was actually opened. */
    var usedSource: Int = MediaRecorder.AudioSource.MIC
        private set

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording.get()) return true

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            onError("This phone does not support 16 kHz microphone capture.")
            return false
        }
        // At least 2 seconds of internal buffer, so short pauses never drop audio.
        val bufferBytes = maxOf(minBuffer * 4, SAMPLE_RATE * 2 * 2)

        var recorder: AudioRecord? = null
        val sources = linkedSetOf(preferredSource, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            val candidate = try {
                AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferBytes)
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source failed", e)
                null
            }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                recorder = candidate
                usedSource = source
                break
            }
            candidate?.release()
        }
        if (recorder == null) {
            onError("Could not open the microphone. Check the permission and close other recording apps.")
            return false
        }

        try {
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            recorder.release()
            onError("Could not start recording: ${e.message}")
            return false
        }

        audioRecord = recorder
        isRecording.set(true)
        job = scope.launch(Dispatchers.IO) {
            val shortBuffer = ShortArray(CHUNK_SAMPLES)
            try {
                while (isActive && isRecording.get()) {
                    val count = recorder.read(shortBuffer, 0, shortBuffer.size)
                    if (count > 0) {
                        onChunk(shortBuffer.copyOf(count))
                    } else if (count < 0) {
                        if (isRecording.get()) onError("Microphone read error ($count).")
                        break
                    }
                }
            } finally {
                try {
                    recorder.stop()
                } catch (e: Exception) {
                    // already stopped
                }
                recorder.release()
            }
        }
        return true
    }

    /** Stops capturing. The capture coroutine releases the microphone. */
    fun stop() {
        isRecording.set(false)
        try {
            audioRecord?.stop() // unblocks a pending read()
        } catch (e: Exception) {
            // already stopped
        }
        audioRecord = null
    }

    fun isCapturing(): Boolean = isRecording.get()

    companion object {
        private const val TAG = "AudioRecorderManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SAMPLES = 2048
    }
}
