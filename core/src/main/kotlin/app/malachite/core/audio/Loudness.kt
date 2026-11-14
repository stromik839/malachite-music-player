package app.malachite.core.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * How loud a song sounds, in LUFS – the measure of ITU-R BS.1770 / EBU R128 that streaming
 * services use to play songs at one level: the sound goes through the "K-weighting" (the ear is
 * less sensitive to low and very high tones), is measured in 400 ms blocks every 100 ms, and
 * silent and quiet passages are left out (absolute gate −70 LUFS, relative gate −10 LU).
 *
 * The sound may be fed in separate pieces (a song is sampled in several places to be quick):
 * call [newPiece] between them.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
    }

    // the K-weighting for any sample rate (the formulas of libebur128)
    private val shelf = Array(channels) { shelfFilter() }
    private val highPass = Array(channels) { highPassFilter() }

    private fun shelfFilter(): Biquad {
        val f0 = 1681.974450955533; val g = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
            2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0,
        )
    }

    private fun highPassFilter(): Biquad {
        val f0 = 38.13547087602444; val q = 0.5003270373238773
        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private val hop = sampleRate / 10            // 100 ms
    private val window = DoubleArray(4)          // sums of the last four 100 ms parts
    private var filled = 0
    private var partSum = 0.0
    private var partLen = 0
    private val blocks = ArrayList<Double>()     // mean square of every 400 ms block

    /** Interleaved samples in -1..1, [frames] frames. */
    fun feed(samples: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val y = highPass[c].process(shelf[c].process(samples[i++].toDouble()))
                // the surround channels would weigh 1.41; music here is mono or stereo
                sum += y * y
            }
            partSum += sum
            if (++partLen == hop) {
                for (k in 0 until 3) window[k] = window[k + 1]
                window[3] = partSum
                partSum = 0.0
                partLen = 0
                if (++filled >= 4) blocks += window.sum() / (hop * 4)
            }
        }
    }

    /** The next samples are not continuous with the previous ones (another place of the song). */
    fun newPiece() {
        shelf.forEach { it.reset() }
        highPass.forEach { it.reset() }
        window.fill(0.0)
        filled = 0
        partSum = 0.0
        partLen = 0
    }

    /** The integrated loudness in LUFS, or null if there was (almost) nothing but silence. */
    fun integrated(): Double? {
        fun lufs(ms: Double) = -0.691 + 10.0 * log10(ms)
        val loud = blocks.filter { it > 0 && lufs(it) > -70.0 }
        if (loud.isEmpty()) return null
        val gate = lufs(loud.average()) - 10.0
        val kept = loud.filter { lufs(it) > gate }
        return if (kept.isEmpty()) null else lufs(kept.average())
    }

    companion object {
        /** What songs are brought to: the level of the big streaming services. */
        const val TARGET_LUFS = -14.0

        /** The gain in dB that brings [lufs] to the target, kept within reasonable bounds. */
        fun gainFor(lufs: Double, target: Double = TARGET_LUFS): Double = (target - lufs).coerceIn(-12.0, 9.0)
    }
}
