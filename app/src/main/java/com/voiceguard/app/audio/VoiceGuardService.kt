package com.voiceguard.app.audio

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.voiceguard.app.R
import com.voiceguard.app.VoiceGuardApp
import com.voiceguard.app.ml.AnalysisPipeline
import com.voiceguard.app.model.CaptureMode
import com.voiceguard.app.model.MonitorBus
import com.voiceguard.app.model.MonitorState
import com.voiceguard.app.model.RiskLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service for capture modes 1 (speakerphone, live) and 3 (in-app test call).
 *
 * microphone -> AudioRecorderManager -> channel -> AnalysisPipeline (AASIST-L + keywords)
 *            -> MonitorBus (screen) + AlertNotifier (notification, vibration, floating banner)
 */
class VoiceGuardService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recorder: AudioRecorderManager? = null
    private var channel: Channel<ShortArray>? = null
    private var consumerJob: Job? = null
    private var stateJob: Job? = null
    private var transcriptJob: Job? = null
    private var running = false

    @Volatile
    private var recorderError: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service started with startForegroundService() must call startForeground() quickly,
        // for every start command. A running service keeps its current notification on stop.
        if (intent?.action != ACTION_STOP || !running) {
            enterForeground(getString(R.string.notif_starting))
        }

        when (intent?.action) {
            ACTION_START -> {
                val mode = try {
                    CaptureMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: CaptureMode.SPEAKERPHONE.name)
                } catch (e: IllegalArgumentException) {
                    CaptureMode.SPEAKERPHONE
                }
                startMonitoring(mode)
            }
            ACTION_STOP -> stopMonitoring()
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(text: String) {
        val notification = AlertNotifier.buildOngoing(this, text)
        ServiceCompat.startForeground(
            this,
            AlertNotifier.ID_LIVE,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun startMonitoring(mode: CaptureMode) {
        if (running) return
        val app = application as VoiceGuardApp
        running = true

        val label = if (mode == CaptureMode.TEST_CALL) "In-app test call" else "Speakerphone call"
        val pipeline = AnalysisPipeline(app, mode, label) { event ->
            mainHandler.post { AlertNotifier.showAlert(applicationContext, event, app.settings) }
        }
        val audioChannel = Channel<ShortArray>(capacity = 300, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        channel = audioChannel

        MonitorBus.state.value = MonitorState(
            running = true,
            mode = mode,
            modelReady = app.classifier.isReady(),
            sourceLabel = label,
            message = getString(R.string.msg_starting)
        )

        consumerJob = serviceScope.launch {
            var error: String? = null
            try {
                pipeline.begin()
                for (chunk in audioChannel) pipeline.feed(chunk)
            } catch (e: CancellationException) {
                // service destroyed
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            withContext(NonCancellable) { pipeline.finish(error = error ?: recorderError) }
            mainHandler.post { shutDown() }
        }

        transcriptJob = serviceScope.launch {
            MonitorBus.transcriptInput.collect { text -> pipeline.submitText(text) }
        }

        stateJob = serviceScope.launch {
            MonitorBus.state.collect { s ->
                if (s.running) updateNotification(s)
            }
        }

        val rec = AudioRecorderManager(
            preferredSource = app.settings.androidAudioSource(),
            onChunk = { chunk -> audioChannel.trySend(chunk) },
            onError = { message ->
                recorderError = message
                MonitorBus.update { it.copy(message = message) }
                audioChannel.close()
            }
        )
        recorder = rec
        if (!rec.start(serviceScope)) {
            // onError already closed the channel; the consumer finishes the session with a note.
            audioChannel.close()
        }
    }

    /** Stop button: stop the microphone, let the pipeline drain and save the session. */
    private fun stopMonitoring() {
        if (!running) {
            stopSelfSafely()
            return
        }
        MonitorBus.update { it.copy(message = getString(R.string.msg_saving)) }
        recorder?.stop()
        channel?.close()
    }

    private fun updateNotification(s: MonitorState) {
        val text = when {
            !s.modelReady && s.windowsScored == 0 -> getString(R.string.notif_no_model)
            s.level == RiskLevel.ALERT -> getString(R.string.notif_alert, (s.spoofScore * 100).toInt())
            s.windowsScored == 0 -> getString(R.string.notif_collecting)
            else -> getString(R.string.notif_score, (s.spoofScore * 100).toInt(), s.trend.label)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(AlertNotifier.ID_LIVE, AlertNotifier.buildOngoing(this, text))
    }

    private fun shutDown() {
        running = false
        transcriptJob?.cancel()
        stateJob?.cancel()
        OverlayBanner.hide()
        stopSelfSafely()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder?.stop()
        channel?.close()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        if (MonitorBus.state.value.running && MonitorBus.state.value.mode != CaptureMode.RECORDED_CALL) {
            MonitorBus.update { it.copy(running = false) }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.voiceguard.app.action.START"
        const val ACTION_STOP = "com.voiceguard.app.action.STOP"
        const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: CaptureMode) {
            val intent = Intent(context, VoiceGuardService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceGuardService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
