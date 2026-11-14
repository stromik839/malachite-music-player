package app.malachite.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** The highlight never leaves a word-by-word line before its last word has been sung. */
class LastWordTest {
    private fun lyrics(lastWordEnd: Long?) = Lyrics(
        listOf(
            LyricLine(
                1000, null, "Повязки, мази и весы",
                listOf(LyricWord(1000, 1400, "Повязки,"), LyricWord(1400, 1700, "мази"), LyricWord(1700, 1900, "и"), LyricWord(1900, lastWordEnd, "весы")),
            ),
            LyricLine(2100, null, "Попал ко мне в больницу ты"),
        ),
        synced = true,
    )

    @Test
    fun aLineWithoutWordsSwitchesWithTheFullLead() {
        val l = Lyrics(listOf(LyricLine(1000, null, "a"), LyricLine(3000, null, "b")), synced = true)
        assertEquals(2600, l.switchMs(0, 400))
        assertEquals(1, l.activeLineAhead(2600, 400))
        assertEquals(0, l.activeLineAhead(2599, 400))
    }

    @Test
    fun theLastWordIsSungBeforeTheNextLineTakesOver() {
        // the last word starts 200 ms before the next line: a 400 ms lead used to skip it
        val l = lyrics(lastWordEnd = null)
        assertEquals(2100, l.switchMs(0, 400))
        assertEquals(0, l.activeLineAhead(1950, 400))
        assertEquals(0, l.activeLineAhead(2099, 400))
        assertEquals(1, l.activeLineAhead(2100, 400))
    }

    @Test
    fun aKnownEndOfTheLastWordIsWaitedFor() {
        val l = lyrics(lastWordEnd = 2000)
        assertEquals(2000, l.switchMs(0, 400))
        assertEquals(0, l.activeLineAhead(1999, 400))
        assertEquals(1, l.activeLineAhead(2000, 400))
    }

    @Test
    fun theLastLineHasNoSwitch() {
        assertEquals(null, lyrics(null).switchMs(1, 400))
    }
}
