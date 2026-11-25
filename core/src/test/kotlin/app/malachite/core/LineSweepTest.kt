package app.malachite.core

import app.malachite.core.lyrics.LineSweep
import app.malachite.core.lyrics.LyricLine
import app.malachite.core.lyrics.LyricWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineSweepTest {
    private val text = "Делать по миллиону в день было бы разумно"
    private val line = LyricLine(startMs = 10_000, text = text)

    @Test
    fun nothingBeforeTheLineEverythingAtItsEnd() {
        assertEquals(0f, LineSweep.reach(text, line, 9_000, 14_000, 13_900))
        assertEquals(text.length.toFloat(), LineSweep.reach(text, line, 13_900, 14_000, 13_900))
    }

    private val timed = line.copy(
        words = listOf(
            LyricWord(10_000, 10_100, "Делать"), LyricWord(10_100, 10_150, "по"), LyricWord(10_150, 13_000, "миллиону"),
            LyricWord(13_000, 13_050, "в"), LyricWord(13_050, 13_100, "день"), LyricWord(13_100, 13_150, "было"),
            LyricWord(13_150, 13_200, "бы"), LyricWord(13_200, 13_800, "разумно"),
        ),
    )

    @Test
    fun anEvenPaceWhenAskedWhateverTheWordsSay() {
        // a quickly sung word and a held one: with the even pace the edge does not jump with them
        val steps = (10_000L..13_600L step 300).map { LineSweep.reach(text, timed, it, 14_000, 13_900, byWords = false) }
        val moves = steps.zipWithNext { a, b -> b - a }
        // every 0.3 s the edge moves by a similar number of letters (vowels a little slower)
        assertTrue(moves.all { it > 1.2f && it < 6f }, moves.toString())
    }

    @Test
    fun eachTimedWordFillsAtItsOwnPace() {
        val from = text.indexOf("миллиону")
        val to = from + "миллиону".length
        // the two quick words are done almost at once
        assertTrue(LineSweep.reach(text, timed, 10_160, 14_000, 13_900) >= from, "quick words")
        // the held word fills slowly over its 2.85 s: half way through it, the edge is in its middle
        val mid = LineSweep.reach(text, timed, 11_575, 14_000, 13_900)
        assertTrue(mid > from + 2 && mid < to - 2, "held word: $mid of $from..$to")
        // the edge never goes back
        val steps = (10_000L..13_900L step 50).map { LineSweep.reach(text, timed, it, 14_000, 13_900) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b >= a }, steps.toString())
        // and the line is full by its end
        assertEquals(text.length.toFloat(), LineSweep.reach(text, timed, 13_900, 14_000, 13_900))
    }

    @Test
    fun aPauseBetweenWordsHoldsTheEdge() {
        val words = LyricLine(
            startMs = 0, text = "раз два",
            words = listOf(LyricWord(0, 400, "раз"), LyricWord(2_000, 2_400, "два")),
        )
        // after the first word and before the second: the first word and the space are coloured, no more
        assertEquals(4f, LineSweep.reach("раз два", words, 1_000, 5_000, 4_900))
        assertEquals(4f, LineSweep.reach("раз два", words, 1_900, 5_000, 4_900))
        assertTrue(LineSweep.reach("раз два", words, 2_200, 5_000, 4_900) in 4.5f..6.9f)
    }

    @Test
    fun aLongPauseAfterTheLineDoesNotStretchIt() {
        // the next line comes 30 seconds later (a solo): the line is still sung through at a normal
        // pace (about 6 s for these 41 letters), not spread over the 30 s
        assertTrue(LineSweep.reach(text, line, 16_000, 40_000, 39_900) > text.length * 0.9f)
        assertEquals(text.length.toFloat(), LineSweep.reach(text, line, 17_000, 40_000, 39_900))
    }

    @Test
    fun theEdgeBlendsOverAboutOneLetter() {
        val n = 10
        assertEquals(0f, LineSweep.mix(0, n, 0f))
        assertEquals(1f, LineSweep.mix(n - 1, n, n.toFloat()))
        val mid = (0 until n).map { LineSweep.mix(it, n, 5f) }
        assertEquals(1f, mid.first())
        assertEquals(0f, mid.last())
        assertTrue(mid.zipWithNext().all { (a, b) -> b <= a })
    }
}
