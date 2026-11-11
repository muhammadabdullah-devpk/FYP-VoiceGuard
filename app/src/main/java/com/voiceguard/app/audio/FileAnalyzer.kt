package com.voiceguard.app.audio

import android.net.Uri
import com.voiceguard.app.VoiceGuardApp
import com.voiceguard.app.ml.AnalysisPipeline
import com.voiceguard.app.model.CaptureMode
import com.voiceguard.app.model.MonitorBus
import com.voiceguard.app.model.MonitorState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capture mode 2: analysis of a recorded call (made with consent).
 * Runs in the application scope, so it survives screen rotation.
 */
object FileAnalyzer {

    @Volatile
    private var job: Job? = null

    fun start(app: VoiceGuardApp, uri: Uri, label: String) {
        if (MonitorBus.isBusy()) return
        MonitorBus.state.value = MonitorState(
            running = true,
            mode = CaptureMode.RECORDED_CALL,
            modelReady = app.classifier.isReady(),
            sourceLabel = label,
            progress = 0f,
            message = "Decoding the recording..."
        )
        job = app.appScope.launch(Dispatchers.Default) {
            val pipeline = AnalysisPipeline(app, CaptureMode.RECORDED_CALL, label)
            var error: String? = null
            var cancelled = false
            try {
                pipeline.begin()
                AudioFileDecoder.decode(
                    context = app,
                    uri = uri,
                    onProgress = { pipeline.setProgress(it) },
                    onChunk = { chunk -> pipeline.feed(chunk) }
                )
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            withContext(NonCancellable) {
                pipeline.finish(cancelled = cancelled, error = error)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
