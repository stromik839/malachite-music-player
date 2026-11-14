package app.malachite.player.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import app.malachite.core.soundid.AcoustId
import app.malachite.core.soundid.SoundPrint
import java.io.File
import java.nio.ByteOrder

/**
 * The user's own AcoustID key. Kept in the app's private preferences only: not in the settings
 * file (which goes into backups), never in the log.
 */
object SoundIdKey {
    private const val PREFS = "sound_id"
    private const val KEY = "acoustid_key"
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) { prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var value: String
        get() = prefs?.getString(KEY, null).orEmpty()
        set(v) { prefs?.edit()?.putString(KEY, v.trim())?.apply() }

    val present get() = value.isNotBlank()

    /**
     * Asks the service with a made-up print: a working key gets an answer ("nothing known"), a
     * wrong one gets the service's complaint.
     */
    fun check(service: AcoustId, key: String = value): AcoustId.Answer {
        val tone = ShortArray(SoundPrint.SAMPLE_RATE * 20) { i -> (8000 * kotlin.math.sin(2 * Math.PI * (220 + i / 2000) * i / SoundPrint.SAMPLE_RATE)).toInt().toShort() }
        return service.lookup(key, SoundPrint.encoded(tone), 20)
    }
}

/** The sound of a file brought into a sound print: decoded by the phone, mono, 11025 Hz, the first two minutes. */
object SoundDecoder {
    class Print(val print: String, val durationSec: Int)

    private const val SECONDS = 120
    private const val TIMEOUT_MS = 45_000L

    fun print(file: File, knownDurationMs: Long): Print? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            val format = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else knownDurationMs * 1000
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var mono = ShortArray(rate * 20)
            var n = 0
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (!done && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i) ?: return null
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    floatPcm = Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                        if (n + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, n + frames))
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (ch in 0 until channels) sum += fb.get()
                                mono[n++] = (sum / channels * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            for (f in 0 until frames) {
                                var sum = 0
                                for (ch in 0 until channels) sum += sb.get()
                                mono[n++] = (sum / channels).toShort()
                            }
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || n >= rate * SECONDS) done = true
                }
            }
            val sound = SoundPrint.toPrintRate(mono, minOf(n, rate * SECONDS), rate)
            val raw = SoundPrint.raw(sound)
            if (raw.isEmpty()) return null
            val seconds = (if (durationUs > 0) durationUs / 1_000_000 else (n / rate).toLong()).toInt()
            return Print(SoundPrint.encode(raw), seconds)
        } catch (e: Exception) {
            AppLog.w("sound", "${file.name}: не прочитался звук: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }
}
