package app.malachite.core.soundid

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The "sound print" of a song in the form the AcoustID service understands (the Chromaprint
 * method, its standard variant): the sound, mono at 11025 Hz, is cut into overlapping pieces;
 * each piece is turned into the energy of the 12 notes of the scale; a row of such note pictures
 * is read by 16 small filters, each giving two bits – 32 bits for every ~0.12 s of sound.
 * Written for this player from the description of the method.
 */
object SoundPrint {
    const val SAMPLE_RATE = 11025
    private const val FRAME = 4096
    private const val STEP = FRAME / 3 // 1365: frames overlap by two thirds
    private const val MIN_FREQ = 28.0
    private const val MAX_FREQ = 3520.0
    private const val BANDS = 12
    private const val ALGORITHM = 1

    private val SMOOTHING = doubleArrayOf(0.25, 0.75, 1.0, 0.75, 0.25)

    /** One filter: its [kind] of shape, the [y]-th note band it starts at, [height] bands, [width] frames, and three steps. */
    private class Probe(val kind: Int, val y: Int, val height: Int, val width: Int, val t0: Double, val t1: Double, val t2: Double)

    private val PROBES = arrayOf(
        Probe(0, 4, 3, 15, 1.98215, 2.35817, 2.63523),
        Probe(4, 4, 6, 15, -1.03809, -0.651211, -0.282167),
        Probe(1, 0, 4, 16, -0.298702, 0.119262, 0.558497),
        Probe(3, 8, 2, 12, -0.105439, 0.0153946, 0.135898),
        Probe(3, 4, 4, 8, -0.142891, 0.0258736, 0.200632),
        Probe(4, 0, 3, 5, -0.826319, -0.590612, -0.368214),
        Probe(1, 2, 2, 9, -0.557409, -0.233035, 0.0534525),
        Probe(2, 7, 3, 4, -0.0646826, 0.00620476, 0.0784847),
        Probe(2, 6, 2, 16, -0.192387, -0.029699, 0.215855),
        Probe(2, 1, 3, 2, -0.0397818, -0.00568076, 0.0292026),
        Probe(5, 10, 1, 15, -0.53823, -0.369934, -0.190235),
        Probe(3, 6, 2, 10, -0.124877, 0.0296483, 0.139239),
        Probe(2, 1, 1, 14, -0.101475, 0.0225617, 0.231971),
        Probe(3, 5, 6, 4, -0.0799915, -0.00729616, 0.063262),
        Probe(1, 9, 2, 12, -0.272556, 0.019424, 0.302559),
        Probe(3, 4, 2, 14, -0.164292, -0.0321188, 0.0846339),
    )
    private val WIDEST = PROBES.maxOf { it.width }

    /** The raw print: one 32-bit number for every step of [samples] (16-bit mono at [SAMPLE_RATE]). */
    fun raw(samples: ShortArray, count: Int = samples.size): IntArray {
        val rows = notes(samples, count)
        val image = smoothAndNormalize(rows)
        return read(image)
    }

    /** The print packed and written the way AcoustID takes it. */
    fun encoded(samples: ShortArray, count: Int = samples.size): String = encode(raw(samples, count))

    // ------------------------------------------------------------------ any rate -> 11025 Hz

    private const val PHASES = 256
    private const val SIDE = 8 // taps on each side, counted at the new rate

    /**
     * [count] samples of mono sound at [rate] Hz brought to [SAMPLE_RATE]: a windowed-sinc
     * low-pass (nothing above the new half rate may fold back as false notes) read at the new
     * sample positions, from a table of 256 fractional positions.
     */
    fun toPrintRate(src: ShortArray, count: Int, rate: Int): ShortArray {
        if (rate == SAMPLE_RATE) return src.copyOf(count)
        val step = rate.toDouble() / SAMPLE_RATE
        val cutoff = minOf(1.0, 1.0 / step) * 0.92
        val half = kotlin.math.ceil(SIDE * maxOf(1.0, step)).toInt()
        val taps = 2 * half
        // table[phase][k]: weight of the source sample at (floor(position) - half + 1 + k)
        val table = Array(PHASES) { ph ->
            val frac = ph.toDouble() / PHASES
            val w = DoubleArray(taps) { k ->
                val d = frac - (k - half + 1) // distance from the wanted position
                val x = d * cutoff
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val win = if (kotlin.math.abs(d) >= half) 0.0 else 0.5 * (1 + cos(PI * d / half))
                sinc * win * cutoff
            }
            val sum = w.sum()
            if (sum != 0.0) for (k in w.indices) w[k] /= sum
            w
        }
        val outCount = (count / step).toInt()
        val out = ShortArray(outCount)
        for (j in 0 until outCount) {
            val pos = j * step
            val base = floor(pos).toInt()
            val w = table[((pos - base) * PHASES).toInt().coerceIn(0, PHASES - 1)]
            var acc = 0.0
            val first = base - half + 1
            for (k in 0 until taps) {
                val i = first + k
                if (i in 0 until count) acc += src[i] * w[k]
            }
            out[j] = Math.round(acc).coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ------------------------------------------------------------------ sound -> notes

    private val window = DoubleArray(FRAME) { i -> (0.54 - 0.46 * cos(2 * PI * i / (FRAME - 1))) / Short.MAX_VALUE }

    // which note band each spectrum line belongs to
    private val firstLine = maxOf(1, round(FRAME * MIN_FREQ / SAMPLE_RATE).toInt())
    private val lastLine = minOf(FRAME / 2, round(FRAME * MAX_FREQ / SAMPLE_RATE).toInt())
    private val noteOf = IntArray(FRAME / 2 + 1) { i ->
        if (i < firstLine || i >= lastLine) -1 else {
            val freq = i.toDouble() * SAMPLE_RATE / FRAME
            val octave = ln(freq / (440.0 / 16.0)) / ln(2.0)
            (BANDS * (octave - floor(octave))).toInt()
        }
    }

    private fun notes(samples: ShortArray, count: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        var start = 0
        while (start + FRAME <= count) {
            for (i in 0 until FRAME) { re[i] = samples[start + i] * window[i]; im[i] = 0.0 }
            fft(re, im)
            val bands = DoubleArray(BANDS)
            for (i in firstLine until lastLine) {
                val power = re[i] * re[i] + im[i] * im[i]
                bands[noteOf[i]] += power
            }
            out += bands
            start += STEP
        }
        return out
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ------------------------------------------------------------------ notes -> picture

    /** Each row smoothed over five frames, then scaled to length 1 (silence stays 0). */
    private fun smoothAndNormalize(rows: List<DoubleArray>): List<DoubleArray> {
        val n = SMOOTHING.size
        if (rows.size < n) return emptyList()
        return (0..rows.size - n).map { start ->
            val r = DoubleArray(BANDS)
            for (b in 0 until BANDS) {
                var sum = 0.0
                for (k in 0 until n) sum += rows[start + k][b] * SMOOTHING[k]
                r[b] = sum
            }
            val norm = sqrt(r.sumOf { it * it })
            if (norm < 0.01) r.fill(0.0) else for (b in 0 until BANDS) r[b] /= norm
            r
        }
    }

    // ------------------------------------------------------------------ picture -> bits

    private fun read(image: List<DoubleArray>): IntArray {
        val length = image.size - WIDEST + 1
        if (length <= 0) return IntArray(0)
        // running sums: area[t][b] = sum of image[0..t][0..b]
        val area = Array(image.size) { DoubleArray(BANDS) }
        for (t in image.indices) {
            var row = 0.0
            for (b in 0 until BANDS) {
                row += image[t][b]
                area[t][b] = row + (if (t > 0) area[t - 1][b] else 0.0)
            }
        }
        fun sum(t1: Int, b1: Int, t2: Int, b2: Int): Double {
            // frames t1 until t2, bands b1 until b2
            if (t1 == t2 || b1 == b2) return 0.0
            val d = area[t2 - 1][b2 - 1]
            val left = if (b1 > 0) area[t2 - 1][b1 - 1] else 0.0
            val up = if (t1 > 0) area[t1 - 1][b2 - 1] else 0.0
            val corner = if (t1 > 0 && b1 > 0) area[t1 - 1][b1 - 1] else 0.0
            return d - left - up + corner
        }
        fun cmp(a: Double, b: Double) = ln(1.0 + a) - ln(1.0 + b)
        fun probe(p: Probe, x: Int): Double {
            val y = p.y
            val w = p.width
            val h = p.height
            return when (p.kind) {
                0 -> cmp(sum(x, y, x + w, y + h), 0.0)
                1 -> { val h2 = h / 2; cmp(sum(x, y + h2, x + w, y + h), sum(x, y, x + w, y + h2)) }
                2 -> { val w2 = w / 2; cmp(sum(x + w2, y, x + w, y + h), sum(x, y, x + w2, y + h)) }
                3 -> {
                    val w2 = w / 2
                    val h2 = h / 2
                    cmp(
                        sum(x, y + h2, x + w2, y + h) + sum(x + w2, y, x + w, y + h2),
                        sum(x, y, x + w2, y + h2) + sum(x + w2, y + h2, x + w, y + h),
                    )
                }
                4 -> { val h3 = h / 3; cmp(sum(x, y + h3, x + w, y + 2 * h3), sum(x, y, x + w, y + h3) + sum(x, y + 2 * h3, x + w, y + h)) }
                else -> { val w3 = w / 3; cmp(sum(x + w3, y, x + 2 * w3, y + h), sum(x, y, x + w3, y + h) + sum(x + 2 * w3, y, x + w, y + h)) }
            }
        }
        val gray = intArrayOf(0, 1, 3, 2)
        return IntArray(length) { x ->
            var bits = 0
            for (p in PROBES) {
                val v = probe(p, x)
                val q = if (v < p.t1) (if (v < p.t0) 0 else 1) else (if (v < p.t2) 2 else 3)
                bits = (bits shl 2) or gray[q]
            }
            bits
        }
    }

    // ------------------------------------------------------------------ packing

    /**
     * Packed print: the variant, the count, then the differences between neighbouring numbers
     * written as the distances between their set bits (3 bits each, larger ones continued in 5
     * bits), in URL-safe base64 without padding.
     */
    fun encode(print: IntArray): String {
        val gaps = ArrayList<Int>()
        var prev = 0
        for ((i, value) in print.withIndex()) {
            var x = if (i == 0) value else value xor prev
            prev = value
            var bit = 1
            var lastBit = 0
            while (x != 0) {
                if (x and 1 != 0) { gaps += bit - lastBit; lastBit = bit }
                x = x ushr 1
                bit++
            }
            gaps += 0
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(ALGORITHM)
        out.write((print.size shr 16) and 0xFF)
        out.write((print.size shr 8) and 0xFF)
        out.write(print.size and 0xFF)
        packBits(out, gaps.map { minOf(it, 7) }, 3)
        packBits(out, gaps.filter { it >= 7 }.map { it - 7 }, 5)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun packBits(out: java.io.ByteArrayOutputStream, values: List<Int>, width: Int) {
        var acc = 0
        var have = 0
        for (v in values) {
            acc = acc or ((v and ((1 shl width) - 1)) shl have)
            have += width
            while (have >= 8) { out.write(acc and 0xFF); acc = acc ushr 8; have -= 8 }
        }
        if (have > 0) out.write(acc and 0xFF)
    }
}
