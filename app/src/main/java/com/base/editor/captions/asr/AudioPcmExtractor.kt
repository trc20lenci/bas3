package com.base.editor.captions.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Декодирует участок звука файла (MediaExtractor + MediaCodec, аппаратно где возможно)
 * в поток моно PCM 16 бит / 16 кГц — формат, который ждёт распознавание речи.
 * Данные отдаются порциями, поэтому память не растёт с длиной ролика.
 */
class AudioPcmExtractor(private val context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) {

    /** @return false, если в файле нет звуковой дорожки. */
    suspend fun stream(uri: String, startMs: Long, endMs: Long, onChunk: (ShortArray, Int) -> Unit): Boolean = withContext(io) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext false
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val startUs = startMs * 1000; val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime).apply { configure(inFormat, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val resampler = Resampler { s, n -> onChunk(s, n) }
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t > endUs) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, t, 0); extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        resampler.configure(rate)
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            resampler.configure(rate)
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val frames = info.size / (channels * if (floatPcm) 4 else 2)
                            var skip = ((startUs - info.presentationTimeUs) * rate / 1_000_000L).coerceAtLeast(0).toInt()   // до точки In
                            val maxFrames = ((endUs - info.presentationTimeUs) * rate / 1_000_000L).coerceAtLeast(0)
                            var f = 0
                            while (f < frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += if (floatPcm) buf.float else buf.short / 32768f
                                if (skip > 0) skip-- else if (f < maxFrames) resampler.push(sum / channels)
                                f++
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.presentationTimeUs > endUs) outputDone = true
                    }
                }
            }
            resampler.flush()
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "не удалось декодировать звук $uri", e)
            false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
        }
    }

    /** Понижение частоты до 16 кГц усреднением (box-фильтр) — достаточно для речи. */
    private class Resampler(private val sink: (ShortArray, Int) -> Unit) {
        private val out = ShortArray(8000)
        private var n = 0
        private var ratio = 1.0
        private var acc = 0.0; private var cnt = 0
        private var inIndex = 0L; private var nextEdge = 1.0
        private var last = 0f
        private var configuredRate = -1

        fun configure(rate: Int) {
            if (rate == configuredRate) return
            configuredRate = rate; ratio = rate / TARGET_RATE.toDouble(); nextEdge = inIndex + ratio
        }

        fun push(x: Float) {
            acc += x; cnt++; inIndex++; last = x
            while (inIndex >= nextEdge) {
                emit(if (cnt > 0) (acc / cnt).toFloat() else last)
                acc = 0.0; cnt = 0; nextEdge += ratio
            }
        }

        private fun emit(v: Float) {
            out[n++] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            if (n == out.size) { sink(out, n); n = 0 }
        }

        fun flush() { if (n > 0) { sink(out, n); n = 0 } }
    }

    private companion object { const val TAG = "BaseAudioPcm"; const val TARGET_RATE = 16_000 }
}
