package app.malachite.player.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The sound being played as a few bands (0…1), for the visualizer on the song screen. It is taken
 * from the player's own decoded sound on its way to the speaker – no microphone, no permission.
 * Nothing is computed unless the visualizer is on the screen ([watching]).
 *
 * The sound is decoded a little before it is heard; every window of it is stamped with the moment
 * it will be heard (one window after another from now on), and the screen shows the window whose
 * moment has come, so the bars move with the music, not ahead of it.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Spectrum : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 24
    /** 2048 samples (about 46 ms) for every picture, a new picture every 1024: bass needs the long window. */
    private const val SIZE = 2048
    private const val HOP = 1024
    private const val KEEP = 64

    /** The screen shows the visualizer (set from the main thread). */
    @Volatile var watching = false

    /** The playback speed: at 2x a window is heard in half the time. */
    @Volatile var speed = 1f

    private var rate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val window = FloatArray(SIZE)
    private var filled = 0
    private var mono = 0f
    private var monoCount = 0

    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val hann = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }

    // the bands as bins of the transform (made again when the sample rate changes)
    private var edgesRate = 0
    private val edges = IntArray(BANDS + 1)
    // each band's power, smoothed a little, and its level in the previous picture (for the beats)
    private val smooth = FloatArray(BANDS)
    private val previous = FloatArray(BANDS)
    private var fresh = true

    // the computed windows: when each is heard (uptime ms) and its bands
    private val times = LongArray(KEEP)
    private val bands = Array(KEEP) { FloatArray(BANDS) }
    @Volatile private var written = 0L
    private var nextAt = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(this) {
            rate = sampleRateHz.coerceAtLeast(8000)
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            filled = 0
            monoCount = 0
            nextAt = 0L
            fresh = true
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!watching) return
        synchronized(this) {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            when (encoding) {
                C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2) add(b.short / 32768f)
                C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4) add(b.float)
                else -> return
            }
        }
    }

    private fun add(sample: Float) {
        mono += sample
        if (++monoCount < channels) return
        window[filled++] = mono / channels
        mono = 0f
        monoCount = 0
        if (filled == SIZE) {
            compute()
            // the next picture overlaps this one by half
            System.arraycopy(window, HOP, window, 0, SIZE - HOP)
            filled = SIZE - HOP
        }
    }

    /** Log-spaced bands from 45 Hz to 14 kHz; each has at least one bin of its own (no doubled bass bars). */
    private fun makeEdges() {
        if (edgesRate == rate) return
        edgesRate = rate
        val binHz = rate.toDouble() / SIZE
        val lo = 45.0 / binHz
        val hi = min(14_000.0, rate / 2.0 - binHz) / binHz
        var prev = 0
        for (k in 0..BANDS) {
            var b = Math.round(lo * Math.pow(hi / lo, k.toDouble() / BANDS)).toInt()
            b = if (k == 0) max(1, b) else max(b, prev + 1)
            edges[k] = min(b, SIZE / 2)
            prev = edges[k]
        }
    }

    /**
     * The height of a band is mostly how loud it is (a quiet song stays low, a loud one high), and
     * a beat – the band suddenly louder than a moment ago – kicks it up: drums and bass make the
     * bars jump, long calm notes do not.
     */
    private fun compute() {
        makeEdges()
        for (i in 0 until SIZE) { re[i] = window[i] * hann[i]; im[i] = 0f }
        fft(re, im)
        val slot = (written % KEEP).toInt()
        val out = bands[slot]
        // the power of a full-scale sine in one bin
        val ref = (SIZE / 4f) * (SIZE / 4f)
        for (k in 0 until BANDS) {
            val from = edges[k]
            val to = max(from + 1, edges[k + 1])
            var sum = 0f
            for (i in from until to) sum += re[i] * re[i] + im[i] * im[i]
            val power = sum / (to - from) / ref
            smooth[k] = if (fresh) power else 0.5f * power + 0.5f * smooth[k]
            val db = 10f * ln(smooth[k].coerceAtLeast(1e-9f)) / ln(10f)
            val loud = ((db + 48f) / 36f).coerceIn(0f, 1f)
            val beat = if (fresh) 0f else ((db - previous[k] - 2f) / 8f).coerceAtLeast(0f)
            previous[k] = db
            out[k] = if (db < -65f) 0f else (0.8f * Math.pow(loud.toDouble(), 1.8).toFloat() + beat).coerceIn(0f, 1f)
        }
        fresh = false
        val now = SystemClock.uptimeMillis()
        val length = (HOP * 1000L / rate / speed.coerceAtLeast(0.25f)).toLong()
        nextAt = if (nextAt < now) now else nextAt
        times[slot] = nextAt
        nextAt += length
        written++
    }

    /** The bands heard now (into [into]); false when there is nothing fresh. */
    fun now(into: FloatArray): Boolean = synchronized(this) {
        val now = SystemClock.uptimeMillis()
        val n = written
        if (n == 0L) return false
        var best = -1
        var bestTime = Long.MIN_VALUE
        val from = max(0L, n - KEEP)
        for (w in from until n) {
            val s = (w % KEEP).toInt()
            val t = times[s]
            if (t <= now && t > bestTime) { bestTime = t; best = s }
        }
        if (best < 0 || now - bestTime > 400) return false
        bands[best].copyInto(into)
        true
    }

    /** In-place radix-2 FFT. */
    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tx = x[i]; x[i] = x[j]; x[j] = tx
                val ty = y[i]; y[i] = y[j]; y[j] = ty
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tr = x[b] * cr - y[b] * ci
                    val ti = x[b] * ci + y[b] * cr
                    x[b] = x[a] - tr; y[b] = y[a] - ti
                    x[a] += tr; y[a] += ti
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
