package app.malachite.core

import app.malachite.core.soundid.SoundPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The print must be the one AcoustID knows. The reference files were made from the same test
 * sound by the Chromaprint reference tool (fpcalc 1.5, "-raw" and the packed form).
 */
class SoundPrintTest {
    /** 15 s of noise and two triangle waves that change their pitch every second, in whole numbers only. */
    private fun testSound(): ShortArray {
        val rate = SoundPrint.SAMPLE_RATE
        val n = rate * 15
        val periods = intArrayOf(50, 63, 75, 84, 100, 112, 126, 150)
        var seed = 12345L
        return ShortArray(n) { i ->
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val noise = ((seed shr 16) and 0x7fff).toInt() - 16384
            val sec = i / rate
            fun tri(p: Int, a: Int): Int {
                val ph = i % p
                return if (ph < p / 2) Math.floorDiv(ph * 4 * a, p) - a else 3 * a - Math.floorDiv(ph * 4 * a, p)
            }
            val s = Math.floorDiv(noise, 8) + tri(periods[sec % periods.size], 9000) + Math.floorDiv(tri(periods[(sec * 3 + 1) % periods.size], 5000), 2)
            s.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun resource(name: String) = javaClass.getResource("/soundprint/$name")!!.readText().trim()

    @Test
    fun theSameAsTheReferenceTool() {
        val reference = resource("reference-raw.txt").split(',').map { it.trim().toLong().toInt() }
        val mine = SoundPrint.raw(testSound())
        assertEquals(reference.size, mine.size)
        val differing = mine.indices.sumOf { Integer.bitCount(mine[it] xor reference[it]) }
        // rounding may flip a rare bit; the service matches prints with far more differences
        assertTrue(differing * 100 < mine.size * 32, "$differing of ${mine.size * 32} bits differ")
    }

    @Test
    fun packedLikeTheReferenceTool() {
        val reference = resource("reference-raw.txt").split(',').map { it.trim().toLong().toInt() }.toIntArray()
        assertEquals(resource("reference-encoded.txt"), SoundPrint.encode(reference))
    }

    @Test
    fun tooShortGivesNothing() {
        assertTrue(SoundPrint.raw(ShortArray(SoundPrint.SAMPLE_RATE)).isEmpty())
    }
}

class SoundRateTest {
    private fun sine(freq: Double, rate: Int, seconds: Int) = ShortArray(rate * seconds) { i -> (10000 * kotlin.math.sin(2 * Math.PI * freq * i / rate)).toInt().toShort() }
    private fun rms(s: ShortArray) = kotlin.math.sqrt(s.drop(2000).dropLast(2000).sumOf { it.toDouble() * it } / (s.size - 4000))

    @Test
    fun aToneKeepsItsLoudnessAndHighOnesAreCutOff() {
        for (rate in listOf(44100, 48000, 22050)) {
            val low = SoundPrint.toPrintRate(sine(440.0, rate, 2).let { it }, rate * 2, rate)
            assertTrue(kotlin.math.abs(low.size - SoundPrint.SAMPLE_RATE * 2) <= 1)
            assertTrue(kotlin.math.abs(rms(low) - 10000 / kotlin.math.sqrt(2.0)) < 300, "440 Hz at $rate: ${rms(low)}")
            // above half of 11025 Hz: it must not come back as a false low note
            val high = SoundPrint.toPrintRate(sine(8000.0, rate, 2), rate * 2, rate)
            assertTrue(rms(high) < 300, "8 kHz at $rate: ${rms(high)}")
        }
    }
}
