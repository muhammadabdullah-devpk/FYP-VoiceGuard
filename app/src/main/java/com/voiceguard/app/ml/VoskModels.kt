package com.voiceguard.app.ml

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Loads the offline Vosk speech models that are bundled in assets/ (proposal Table 2: keyword
 * spotting with Vosk, no internet needed). Two optional models are supported:
 *
 *   assets/model-en-us/   English model, e.g. vosk-model-small-en-us-0.15
 *   assets/model-hi/      Hindi/Urdu model, e.g. vosk-model-small-hi-0.22 (prints Devanagari)
 *
 * If no model is present, keyword spotting from speech is simply unavailable. The typed
 * transcript simulator still works.
 */
object VoskModels {

    class Loaded(val label: String, val model: Model)

    private const val TAG = "VoskModels"
    private val ASSET_DIRS = listOf("model-en-us" to "English", "model-hi" to "Hindi/Urdu")

    private val mutex = Mutex()
    private val _status = MutableStateFlow("Keyword model: not checked yet")
    val status: StateFlow<String> = _status

    @Volatile
    private var loaded: List<Loaded> = emptyList()

    @Volatile
    private var attempted = false

    fun models(): List<Loaded> = loaded

    /** Copies the model from assets to internal storage (first run only) and loads it. */
    suspend fun prepare(context: Context) {
        mutex.withLock {
            if (attempted) return
            _status.value = "Keyword model: preparing (first start can take a while)..."
            withContext(Dispatchers.IO) {
                val found = ArrayList<Loaded>()
                val problems = ArrayList<String>()
                for ((dir, label) in ASSET_DIRS) {
                    val present = try {
                        context.assets.list(dir)?.isNotEmpty() == true
                    } catch (e: Exception) {
                        false
                    }
                    if (!present) continue
                    try {
                        val target = File(context.filesDir, "vosk/$dir")
                        val marker = File(target, ".complete")
                        if (!marker.exists()) {
                            target.deleteRecursively()
                            copyAssetDir(context.assets, dir, target)
                            marker.writeText("ok")
                        }
                        found.add(Loaded(label, Model(target.absolutePath)))
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not load $dir", t)
                        problems.add("$label: ${t.message?.take(50)}")
                    }
                }
                loaded = found
                attempted = true
                _status.value = when {
                    found.isNotEmpty() -> "Keyword models ready: " + found.joinToString { it.label }
                    problems.isNotEmpty() -> "Keyword model failed: " + problems.joinToString("; ")
                    else -> "Keyword model not installed (typed-text scan only). See README."
                }
            }
        }
    }

    private fun copyAssetDir(assets: AssetManager, assetPath: String, target: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            try {
                target.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            } catch (e: FileNotFoundException) {
                target.mkdirs() // empty directory
            }
        } else {
            target.mkdirs()
            for (child in children) {
                copyAssetDir(assets, "$assetPath/$child", File(target, child))
            }
        }
    }
}

/**
 * One streaming recognizer per loaded model, created for one session.
 * Not thread-safe: call from one coroutine at a time (the pipeline does this).
 */
class KeywordSpotter {

    private val recognizers: List<Recognizer> =
        VoskModels.models().map { Recognizer(it.model, 16000.0f) }

    val available: Boolean get() = recognizers.isNotEmpty()

    /** Feeds 16 kHz mono PCM and returns the current text (final or partial) of all models. */
    fun accept(data: ShortArray): String {
        val sb = StringBuilder()
        for (r in recognizers) {
            val isFinal = r.acceptWaveForm(data, data.size)
            val text = if (isFinal) {
                JSONObject(r.result).optString("text")
            } else {
                JSONObject(r.partialResult).optString("partial")
            }
            if (text.isNotEmpty()) sb.append(text).append(' ')
        }
        return sb.toString().trim()
    }

    fun finalText(): String {
        val sb = StringBuilder()
        for (r in recognizers) {
            val text = JSONObject(r.finalResult).optString("text")
            if (text.isNotEmpty()) sb.append(text).append(' ')
        }
        return sb.toString().trim()
    }

    fun close() {
        for (r in recognizers) {
            try {
                r.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
