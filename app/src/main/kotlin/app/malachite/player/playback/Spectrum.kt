package app.malachite.player.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The sound being played as bands (0…1) for the visualizer, and the level of each channel for the
 * mixer's meter. It is taken from the player's own decoded sound on its way to the speaker – no
 * microphone, no permission. Nothing is computed unless something on the screen shows it ([watching]).
 *
 * How a band gets its height (the ideas of good analyzers, written anew here):
 *  - the energy of the whole band is taken, and higher bands get 3 dB more per octave: music has far
 *    more energy in the bass, and without this only the first bars ever move;
 *  - the sensitivity follows the music: the loudest band sets the top at once, and the top comes down
 *    slowly when the music gets quieter, so a quiet song and a loud one both use the height, with a
 *    little room left above;
 *  - a beat – a band suddenly louder than a moment ago – kicks the bar up.
 *
 * The sound is decoded well before it is heard (the phone keeps a fraction of a second of it in its
 * output buffer, more or less on every phone). So every window is stamped with its place in the
 * stream (the time the player gave its sound), and the audio output says which place is coming out
 * of the speaker now ([heard]): the screen shows that window, and a beat is seen when it is heard.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Spectrum : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 32
    /** 2048 samples (about 46 ms) for every picture, a new picture every 1024: bass needs the long window. */
    private const val SIZE = 2048
    private const val HOP = 1024
    private const val KEEP = 64

    /** How much room is left above the loudest band, and how many dB the height spans. */
    private const val HEADROOM_DB = 8f
    private const val RANGE_DB = 36f

    /** How many points of the sound wave each window keeps (for the oscilloscope). */
    const val WAVE_POINTS = 96

    /** How many things on the screen show the sound now (the visualizer, the mixer's meter). */
    private val watchers = java.util.concurrent.atomic.AtomicInteger()

    /** Something on the screen shows the sound: only then is it computed. */
    val watching get() = watchers.get() > 0

    /** Call when a view of the sound appears; [stopWatching] when it goes. */
    fun startWatching() { watchers.incrementAndGet() }

    fun stopWatching() { watchers.updateAndGet { max(0, it - 1) } }

    /** The playback speed: at 2x a window is heard in half the time. */
    @Volatile var speed = 1f

    private var rate = 44_100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private val window = FloatArray(SIZE)
    private var filled = 0
    private var mono = 0f
    private var monoCount = 0
    // the channels' energy and peaks over the newest HOP samples (for the meter)
    private var sumL = 0.0
    private var sumR = 0.0
    private var peakL = 0f
    private var peakR = 0f
    private var counted = 0

    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)
    private val hann = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }

    // the bands as bins of the transform and their tilt (made again when the sample rate changes)
    private var edgesRate = 0
    private val edges = IntArray(BANDS + 1)
    private val tilt = FloatArray(BANDS)
    // each band's power, smoothed a little, and its level in the previous picture (for the beats)
    private val smooth = FloatArray(BANDS)
    private val previous = FloatArray(BANDS)
    private var fresh = true
    /** The level (dB) the top of the picture stands for: follows the music. */
    private var top = -20f

    // the computed windows: their place in the stream (µs, the middle of the window), bands,
    // channel levels and the shape of the sound wave
    private val times = LongArray(KEEP)
    private val bands = Array(KEEP) { FloatArray(BANDS) }
    /** Per window: RMS left, RMS right, peak left, peak right (0…1 of the meter). */
    private val meters = Array(KEEP) { FloatArray(4) }
    /** Per window: the sound wave of its newest part, [WAVE_POINTS] values -1…1. */
    private val waves = Array(KEEP) { FloatArray(WAVE_POINTS) }
    @Volatile private var written = 0L

    /** The place in the stream (µs) of the first sample of the buffer being read, and how many came since. */
    private var startUs = Long.MIN_VALUE
    private var framesSince = 0L
    /** Set by [inputAt] before the player hands over a buffer: its first sample is at this place. */
    @Volatile private var inputUs = Long.MIN_VALUE

    /**
     * What the audio output plays now: its place in the stream (µs), when it last told it and when
     * that place last moved (uptime ms; a place that stands still is a pause).
     */
    @Volatile private var heardUs = Long.MIN_VALUE
    @Volatile private var heardAt = 0L
    @Volatile private var movedAt = 0L

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(this) {
            rate = sampleRateHz.coerceAtLeast(8000)
            channels = channelCount.coerceAtLeast(1)
            this.encoding = encoding
            filled = 0
            monoCount = 0
            fresh = true
            written = 0L
            startUs = Long.MIN_VALUE
            framesSince = 0L
            sumL = 0.0; sumR = 0.0; peakL = 0f; peakR = 0f; counted = 0
        }
    }

    /** Called by the audio output before it takes a buffer whose first sample is at [presentationUs]. */
    fun inputAt(presentationUs: Long) {
        inputUs = presentationUs
    }

    /** Called by the audio output with the place of the stream it plays now. */
    fun heard(positionUs: Long) {
        val now = SystemClock.uptimeMillis()
        if (positionUs != heardUs) {
            heardUs = positionUs
            movedAt = now
        }
        heardAt = now
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        // the place of this buffer's first sample (taken even when nothing is shown, to stay in step)
        val start = inputUs
        if (start != Long.MIN_VALUE) {
            inputUs = Long.MIN_VALUE
            synchronized(this) { startUs = start; framesSince = 0L }
        }
        if (!watching) {
            synchronized(this) { advance(buffer.remaining()) }
            return
        }
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
        // the meter: the first channel is left, the second right (mono: both)
        val a = kotlin.math.abs(sample)
        if (monoCount == 0) { sumL += sample * sample; if (a > peakL) peakL = a }
        if (monoCount == 1 || channels == 1) { sumR += sample * sample; if (a > peakR) peakR = a }
        mono += sample
        if (++monoCount < channels) return
        window[filled++] = mono / channels
        mono = 0f
        monoCount = 0
        counted++
        framesSince++
        if (filled == SIZE) {
            compute()
            // the next picture overlaps this one by half
            System.arraycopy(window, HOP, window, 0, SIZE - HOP)
            filled = SIZE - HOP
        }
    }

    /** Moves the place in the stream past [bytes] of sound that is not looked at. */
    private fun advance(bytes: Int) {
        val frame = channels * (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2)
        framesSince += bytes / frame
    }

    /** Log-spaced bands from 40 Hz to 12 kHz; each has at least one bin of its own (no doubled bass bars). */
    private fun makeEdges() {
        if (edgesRate == rate) return
        edgesRate = rate
        val binHz = rate.toDouble() / SIZE
        val lo = 40.0 / binHz
        val hi = min(12_000.0, rate / 2.0 - binHz) / binHz
        var prev = 0
        for (k in 0..BANDS) {
            var b = Math.round(lo * Math.pow(hi / lo, k.toDouble() / BANDS)).toInt()
            b = if (k == 0) max(1, b) else max(b, prev + 1)
            edges[k] = min(b, SIZE / 2)
            prev = edges[k]
        }
        for (k in 0 until BANDS) {
            val centre = (edges[k] + max(edges[k] + 1, edges[k + 1])) / 2.0 * binHz
            tilt[k] = (3.0 * log2(max(centre, 40.0) / 1000.0)).toFloat()
        }
    }

    private fun db(power: Float): Float = 10f * ln(power.coerceAtLeast(1e-12f)) / ln(10f)

    private fun compute() {
        makeEdges()
        for (i in 0 until SIZE) { re[i] = window[i] * hann[i]; im[i] = 0f }
        fft(re, im)
        val slot = (written % KEEP).toInt()
        val out = bands[slot]
        // the power of a full-scale sine in one bin
        val ref = (SIZE / 4f) * (SIZE / 4f)
        val level = FloatArray(BANDS)
        var loudest = -120f
        for (k in 0 until BANDS) {
            val from = edges[k]
            val to = max(from + 1, edges[k + 1])
            var sum = 0f
            for (i in from until to) sum += re[i] * re[i] + im[i] * im[i]
            val power = sum / ref
            smooth[k] = if (fresh) power else 0.5f * power + 0.5f * smooth[k]
            level[k] = db(smooth[k]) + tilt[k]
            if (level[k] > loudest) loudest = level[k]
        }
        // the top follows the loudest band up at once and comes down about 6 dB in 3 s
        val secs = HOP.toFloat() / rate
        top = if (loudest > top) loudest else top - 6f * secs / 3f
        top = max(top, -45f)
        val floor = top + HEADROOM_DB - RANGE_DB
        for (k in 0 until BANDS) {
            val v = ((level[k] - floor) / RANGE_DB).coerceIn(0f, 1f)
            val beat = if (fresh) 0f else ((level[k] - previous[k] - 2f) / 10f).coerceAtLeast(0f)
            previous[k] = level[k]
            val value = if (level[k] < -70f) 0f else (Math.pow(v.toDouble(), 1.6).toFloat() + beat).coerceIn(0f, 1f)
            // broken samples must not turn into NaN bars
            out[k] = if (value.isNaN()) 0f else value
        }
        fresh = false
        // the meter: -48 dB … 0 dB as 0 … 1, the RMS and the peak of each channel
        val m = meters[slot]
        val n = counted.coerceAtLeast(1)
        fun meter(x: Float) = ((x + 48f) / 48f).coerceIn(0f, 1f)
        m[0] = meter(db((sumL / n).toFloat()))
        m[1] = meter(db((sumR / n).toFloat()))
        m[2] = meter(db(peakL * peakL))
        m[3] = meter(db(peakR * peakR))
        sumL = 0.0; sumR = 0.0; peakL = 0f; peakR = 0f; counted = 0

        // the sound wave of the newest half of the window, for the oscilloscope
        val wave = waves[slot]
        val from = SIZE - HOP
        val per = HOP / WAVE_POINTS
        for (p in 0 until WAVE_POINTS) {
            // the sample of the largest swing in each slice keeps the shape (peaks are not averaged away)
            var v = 0f
            val at = from + p * per
            for (i in at until at + per) if (kotlin.math.abs(window[i]) > kotlin.math.abs(v)) v = window[i]
            wave[p] = v
        }
        // its place in the stream: the middle of the window (its last sample came just now)
        times[slot] = if (startUs == Long.MIN_VALUE) Long.MIN_VALUE else startUs + (framesSince - SIZE / 2) * 1_000_000L / rate
        written++
    }

    /** The window heard now; -1 when nothing plays. Call inside synchronized(this). */
    private fun heardNow(): Int {
        val n = written
        if (n == 0L || heardUs == Long.MIN_VALUE) return -1
        val now = SystemClock.uptimeMillis()
        // the output tells its place many times a second while it plays; silence or a place that
        // stands still means paused
        if (now - heardAt > 300 || now - movedAt > 250) return -1
        val nowUs = heardUs + ((now - movedAt) * 1000 * speed.coerceAtLeast(0.25f)).toLong()
        var best = -1
        var bestUs = Long.MIN_VALUE
        val from = max(0L, n - KEEP)
        for (w in from until n) {
            val s = (w % KEEP).toInt()
            val t = times[s]
            if (t != Long.MIN_VALUE && t <= nowUs && t > bestUs) { bestUs = t; best = s }
        }
        // nothing of the place heard now was computed (just after a seek): nothing to show yet
        return if (best < 0 || nowUs - bestUs > 400_000) -1 else best
    }

    /** The bands heard now (into [into]); false when there is nothing fresh. */
    fun now(into: FloatArray): Boolean = synchronized(this) {
        val best = heardNow()
        if (best < 0) return false
        bands[best].copyInto(into)
        true
    }

    /** The sound wave heard now ([WAVE_POINTS] values -1…1, into [into]); false when nothing plays. */
    fun wave(into: FloatArray): Boolean = synchronized(this) {
        val best = heardNow()
        if (best < 0) return false
        waves[best].copyInto(into)
        true
    }

    /** The channel levels heard now (RMS left, RMS right, peak left, peak right; 0…1); false when nothing plays. */
    fun meter(into: FloatArray): Boolean = synchronized(this) {
        val best = heardNow()
        if (best < 0) return false
        meters[best].copyInto(into)
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
