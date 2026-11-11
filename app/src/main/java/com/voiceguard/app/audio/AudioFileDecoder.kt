package com.voiceguard.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/**
 * Decodes a recorded call (wav, mp3, m4a/aac, amr, 3gp, ogg, flac ...) with the Android
 * media codecs, converts it to 16 kHz mono 16-bit PCM and hands it on in chunks.
 * This is the "guaranteed path" of the proposal: analysis of a recording made with consent.
 */
object AudioFileDecoder {

    private const val OUT_RATE = 16000
    private const val CHUNK = 4096

    suspend fun decode(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
        onChunk: suspend (ShortArray) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw IllegalArgumentException("No audio track was found in this file.")
            }
            extractor.selectTrack(trackIndex)

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs =
                if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) trackFormat.getLong(MediaFormat.KEY_DURATION) else 0L

            var sampleRate = intOr(trackFormat, MediaFormat.KEY_SAMPLE_RATE, OUT_RATE)
            var channels = intOr(trackFormat, MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var pcmFloat = false
            var resampler = Resampler(sampleRate)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(trackFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var mono = FloatArray(8192)
            var resampled = FloatArray(8192)
            val pack = ShortArray(CHUNK)
            var packLen = 0

            while (!outputDone) {
                currentCoroutineContext().ensureActive()

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    sampleRate = intOr(newFormat, MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = intOr(newFormat, MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                    pcmFloat = newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = Resampler(sampleRate)
                } else if (outIndex >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        outBuf.order(ByteOrder.nativeOrder())

                        // 1) interleaved PCM -> mono float
                        val frames: Int
                        if (pcmFloat) {
                            val fb = outBuf.asFloatBuffer()
                            frames = fb.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += fb.get()
                                mono[f] = sum / channels
                            }
                        } else {
                            val sb = outBuf.asShortBuffer()
                            frames = sb.remaining() / channels
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (f in 0 until frames) {
                                var sum = 0
                                for (c in 0 until channels) sum += sb.get()
                                mono[f] = sum / (32768f * channels)
                            }
                        }

                        // 2) resample to 16 kHz
                        val needed = resampler.maxOutput(frames)
                        if (resampled.size < needed) resampled = FloatArray(needed)
                        val produced = resampler.process(mono, frames, resampled)

                        // 3) float -> 16-bit PCM and hand on in fixed chunks
                        for (k in 0 until produced) {
                            val v = (resampled[k] * 32767f).coerceIn(-32768f, 32767f)
                            pack[packLen++] = v.toInt().toShort()
                            if (packLen == CHUNK) {
                                onChunk(pack.copyOf())
                                packLen = 0
                            }
                        }

                        if (durationUs > 0) {
                            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            if (packLen > 0) onChunk(pack.copyOf(packLen))
            onProgress(1f)
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                // ignore
            }
            extractor.release()
        }
    }

    private fun intOr(format: MediaFormat, key: String, default: Int): Int =
        if (format.containsKey(key)) format.getInteger(key) else default

    /** Streaming linear-interpolation resampler to 16 kHz. */
    private class Resampler(inRate: Int) {
        private val passthrough = inRate == OUT_RATE
        private val step = inRate.toDouble() / OUT_RATE
        private var inBase = 0L
        private var nextPos = 0.0
        private var last = 0f

        fun maxOutput(frames: Int): Int = if (passthrough) frames else (frames / step).toInt() + 3

        fun process(input: FloatArray, n: Int, out: FloatArray): Int {
            if (passthrough) {
                System.arraycopy(input, 0, out, 0, n)
                return n
            }
            var count = 0
            while (true) {
                val i0 = nextPos.toLong()
                if (i0 + 1 >= inBase + n) break
                val frac = (nextPos - i0).toFloat()
                val a = if (i0 < inBase) last else input[(i0 - inBase).toInt()]
                val b = input[(i0 + 1 - inBase).toInt()]
                out[count++] = a + (b - a) * frac
                nextPos += step
            }
            if (n > 0) last = input[n - 1]
            inBase += n
            return count
        }
    }
}
