package app.malachite.core

import app.malachite.core.lyrics.LyricLine
import app.malachite.core.lyrics.LyricWord
import app.malachite.core.lyrics.LyricsEdit
import app.malachite.core.lyrics.SyncRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsEditTest {
    private val line = LyricLine(
        1000, 2500, "алые губы острые ногти",
        listOf(LyricWord(1000, 1300, "алые"), LyricWord(1300, 1700, "губы"), LyricWord(1700, 2100, "острые"), LyricWord(2100, 2500, "ногти")),
    )

    @Test
    fun aFixedWordKeepsEveryTime() {
        val fixed = LyricsEdit.retext(line, "алые губы острые когти", null)
        assertEquals(line.words.map { it.startMs }, fixed.words.map { it.startMs })
        assertEquals("когти", fixed.words[3].text)
        assertFalse(LyricsEdit.timingGuessed(line, "алые губы острые когти"))
    }

    @Test
    fun swappedWordsKeepTheTimesOfTheirPlaces() {
        val fixed = LyricsEdit.retext(line, "губы алые острые ногти", null)
        assertEquals(1000, fixed.words[0].startMs)
        assertEquals("губы", fixed.words[0].text)
    }

    @Test
    fun anotherNumberOfWordsIsSpreadOverTheLine() {
        val fixed = LyricsEdit.retext(line, "алые губы и острые ногти", null)
        assertTrue(LyricsEdit.timingGuessed(line, "алые губы и острые ногти"))
        assertEquals(5, fixed.words.size)
        assertEquals(1000, fixed.words.first().startMs)
        assertEquals(2500, fixed.words.last().endMs)
        assertTrue(fixed.words.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }

    @Test
    fun aLineIsAddedBetweenItsNeighbours() {
        val lines = listOf(LyricLine(1000, 2000, "один"), LyricLine(5000, null, "три"))
        val out = LyricsEdit.insertAfter(lines, 0, "два")
        assertEquals(listOf("один", "два", "три"), out.map { it.text })
        assertTrue(out[1].startMs in 2001 until 5000)
    }

    @Test
    fun syncingAgainKeepsEverythingBeforeTheChosenLine() {
        val lines = listOf(
            line,
            LyricLine(3000, 4000, "в два этажа", listOf(LyricWord(3000, 3300, "в"), LyricWord(3300, 3600, "два"), LyricWord(3600, 4000, "этажа"))),
            LyricLine(5000, 6000, "каблуки", listOf(LyricWord(5000, 6000, "каблуки"))),
        )
        val r = LyricsEdit.resyncFrom(lines, offsetMs = 200, from = 1, wordMode = true)
        assertEquals(4, r.saved.cursor)
        val rec = SyncRecorder(r.lines, SyncRecorder.Mode.WORDS)
        assertTrue(rec.restore(r.saved))
        assertEquals(1, rec.currentLine)
        assertEquals(2700, rec.lastMarkMs)  // 2500 + the offset
        listOf(3100L to 3400L, 3400L to 3700L, 3700L to 4100L, 5100L to 6100L).forEach { (a, b) -> rec.press(a, 1f); rec.release(b, 1f) }
        val result = rec.result()
        assertEquals(listOf(1200L, 3100L, 5100L), result.lines.map { it.startMs })
        assertEquals("ногти", result.lines[0].words[3].text)
    }
}
