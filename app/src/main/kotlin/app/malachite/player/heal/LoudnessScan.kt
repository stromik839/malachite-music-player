package app.malachite.player.heal

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.malachite.core.audio.LoudnessMeter
import java.io.File
import java.nio.ByteOrder

/**
 * Measures how loud a song sounds. The song is decoded the way Android plays it, but not whole:
 * eight pieces of four seconds spread over it (a short song is taken whole) – close to the full
 * measurement and about thirty times quicker.
 */
object LoudnessScan {
    private const val PIECES = 8
    private const val PIECE_MS = 4000L

    fun measure(file: File, durationMs: Long, cancelled: () -> Boolean = { false }): Double? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path)
            val index = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            val format = ex.getTrackFormat(index)
            ex.selectTrack(index)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var meter: LoudnessMeter? = null
            val pieces = if (durationMs in 1..(PIECES * PIECE_MS * 5 / 4)) listOf(0L to Long.MAX_VALUE / 4)
            else (0 until PIECES).map { k -> ((durationMs * (2 * k + 1) / (2 * PIECES)) - PIECE_MS / 2).coerceAtLeast(0) to PIECE_MS }
            val info = MediaCodec.BufferInfo()
            var samples = FloatArray(0)
            for ((startMs, lengthMs) in pieces) {
                if (cancelled()) return null
                ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                dec.flush()
                meter?.newPiece()
                val from = ex.sampleTime
                if (from < 0) break
                val until = if (lengthMs >= Long.MAX_VALUE / 8) Long.MAX_VALUE else from + lengthMs * 1000
                var inputDone = false
                var idle = 0
                while (true) {
                    if (!inputDone) {
                        val i = dec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = dec.getInputBuffer(i)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0 || ex.sampleTime > until) {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = dec.outputFormat
                            rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            idle = 0
                            if (info.size > 0) {
                                val m = meter ?: LoudnessMeter(rate, channels).also { meter = it }
                                val out = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val count = if (floatPcm) info.size / 4 else info.size / 2
                                if (samples.size < count) samples = FloatArray(count)
                                if (floatPcm) {
                                    val fb = out.asFloatBuffer()
                                    for (k in 0 until count) samples[k] = fb.get(k)
                                } else {
                                    val sb = out.asShortBuffer()
                                    for (k in 0 until count) samples[k] = sb.get(k) / 32768f
                                }
                                m.feed(samples, count / channels)
                            }
                            dec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        // a decoder that stops answering is not waited for forever
                        else -> if (inputDone && ++idle > 300) break
                    }
                }
            }
            return meter?.integrated()
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
