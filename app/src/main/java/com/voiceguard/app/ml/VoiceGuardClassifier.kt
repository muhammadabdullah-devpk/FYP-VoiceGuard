package com.voiceguard.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.random.Random

/** Output of one window. spoofProb + bonaFideProb = 1. */
data class RawScore(
    val spoofProb: Float,
    val bonaFideProb: Float,
    val latencyMs: Long
)

data class ModelStatus(val loaded: Boolean, val message: String)

data class BenchmarkResult(
    val runs: Int,
    val meanMs: Double,
    val medianMs: Double,
    val p95Ms: Double,
    val maxMs: Double
) {
    val meetsTarget: Boolean get() = p95Ms < TARGET_LATENCY_MS

    companion object {
        const val TARGET_LATENCY_MS = 300.0
    }
}

/**
 * Runs the AASIST-L anti-spoofing model on the phone with ONNX Runtime Mobile.
 *
 * Model contract: input  = raw waveform, float32, shape [1, 64600] (16 kHz, about 4.03 s)
 *                 output = logits, float32, shape [1, 2] = [spoof, bona fide]
 *
 * No audio ever leaves the phone. If no valid model file is found in assets/, the classifier
 * reports "not loaded" and the app does NOT invent a score.
 */
class VoiceGuardClassifier(private val context: Context) {

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "audio_waveform"

    private val _status = MutableStateFlow(ModelStatus(false, "Voice model: loading..."))
    val status: StateFlow<ModelStatus> = _status

    fun isReady(): Boolean = session != null

    /** Blocking. Call from a background thread. Tries each candidate file name in order. */
    fun load() {
        synchronized(lock) {
            if (session != null) return
            val errors = ArrayList<String>()
            for (name in MODEL_CANDIDATES) {
                try {
                    val bytes = context.assets.open(name).use { it.readBytes() }
                    val environment = OrtEnvironment.getEnvironment()
                    val created = OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2)
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        environment.createSession(bytes, options)
                    }
                    env = environment
                    session = created
                    inputName = created.inputNames.first()
                    _status.value = ModelStatus(
                        true,
                        "Voice model loaded: $name (${bytes.size / 1024} KB)"
                    )
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not load $name", t)
                    errors.add("$name: ${t.message?.take(60) ?: t.javaClass.simpleName}")
                }
            }
            _status.value = ModelStatus(
                false,
                "Voice model NOT loaded. Put a valid AASIST-L .onnx file in app/src/main/assets/ " +
                    "(${errors.joinToString("; ")})"
            )
        }
    }

    /**
     * Scores one window of 16 kHz mono audio in [-1, 1].
     * Shorter windows (2 or 3 s) are tiled to 64600 samples, as the AASIST reference code does.
     * Returns null when no model is loaded. Throws if inference itself fails.
     */
    fun score(audio: FloatArray): RawScore? {
        val s = session ?: return null
        val e = env ?: return null
        val start = System.nanoTime()
        val input = padOrTrim(audio)
        val logits: FloatArray = synchronized(lock) {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(input), longArrayOf(1, MODEL_SAMPLES.toLong())).use { tensor ->
                s.run(mapOf(inputName to tensor)).use { result ->
                    when (val value = result[0].value) {
                        is Array<*> -> value[0] as FloatArray
                        is FloatArray -> value
                        else -> throw IllegalStateException("Unexpected model output: ${value?.javaClass}")
                    }
                }
            }
        }
        check(logits.size >= 2) { "Model output must contain [spoof, bona fide] logits" }

        // Softmax over [spoof, bona fide]
        val maxLogit = maxOf(logits[0], logits[1])
        val eSpoof = exp(logits[0] - maxLogit)
        val eReal = exp(logits[1] - maxLogit)
        val sum = eSpoof + eReal
        val latencyMs = (System.nanoTime() - start) / 1_000_000
        return RawScore(eSpoof / sum, eReal / sum, latencyMs)
    }

    /** Measures on-device latency with random audio. Used for the benchmark report. */
    fun benchmark(runs: Int = 30): BenchmarkResult? {
        if (!isReady()) return null
        val rnd = Random(42)
        val noise = FloatArray(MODEL_SAMPLES) { (rnd.nextFloat() * 2f - 1f) * 0.1f }
        repeat(3) { score(noise) } // warm-up
        val times = ArrayList<Double>(runs)
        repeat(runs) {
            val t0 = System.nanoTime()
            score(noise)
            times.add((System.nanoTime() - t0) / 1_000_000.0)
        }
        times.sort()
        return BenchmarkResult(
            runs = runs,
            meanMs = times.average(),
            medianMs = times[times.size / 2],
            p95Ms = times[((times.size * 95) / 100).coerceAtMost(times.size - 1)],
            maxMs = times.last()
        )
    }

    private fun padOrTrim(x: FloatArray): FloatArray {
        if (x.size == MODEL_SAMPLES) return x
        if (x.size > MODEL_SAMPLES) return x.copyOf(MODEL_SAMPLES)
        val out = FloatArray(MODEL_SAMPLES)
        if (x.isEmpty()) return out
        var i = 0
        while (i < MODEL_SAMPLES) {
            val n = minOf(x.size, MODEL_SAMPLES - i)
            System.arraycopy(x, 0, out, i, n)
            i += n
        }
        return out
    }

    companion object {
        private const val TAG = "VoiceGuardClassifier"
        const val MODEL_SAMPLES = 64600
        const val SAMPLE_RATE = 16000

        /** The INT8 file is tried first, then the FP32 file. */
        val MODEL_CANDIDATES = listOf("voiceguard_aasist_l_int8.onnx", "voiceguard_aasist_l.onnx")
    }
}
