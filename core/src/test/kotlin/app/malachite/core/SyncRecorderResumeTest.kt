package app.malachite.core

import app.malachite.core.lyrics.SyncRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncRecorderResumeTest {
    private val lines = listOf("первая строка", "вторая строка", "третья строка")

    @Test
    fun aRecordingContinuesFromWhereItStopped() {
        val first = SyncRecorder(lines, SyncRecorder.Mode.WORDS)
        first.press(1000, 1f); first.release(1400, 1f)
        first.press(1500, 1f); first.release(1900, 1f)
        first.press(2500, 1f); first.release(2900, 1f)
        val saved = first.save()

        val second = SyncRecorder(lines, SyncRecorder.Mode.WORDS)
        assertTrue(second.restore(saved))
        assertEquals(3, second.cursor)
        assertEquals(1, second.currentLine)   // the second word of the second line is next
        assertEquals(1, second.currentWord)
        assertEquals(2900, second.lastMarkMs)
        second.press(3000, 1f); second.release(3300, 1f)
        second.press(4000, 1f); second.release(4300, 1f)
        second.press(4400, 1f); second.release(4800, 1f)
        assertTrue(second.isComplete)
        val result = second.result()
        assertEquals(3, result.lines.size)
        assertEquals(1000, result.lines[0].startMs)
        assertEquals(listOf("первая", "строка"), result.lines[0].words.map { it.text })
        assertEquals(4000, result.lines[2].startMs)
    }

    @Test
    fun marksOfAnotherTextAreRefused() {
        val saved = SyncRecorder(lines, SyncRecorder.Mode.WORDS).apply { press(100, 1f); release(200, 1f) }.save()
        val other = SyncRecorder(lines, SyncRecorder.Mode.LINES)
        assertFalse(other.restore(saved))
        assertEquals(0, other.cursor)
    }

    @Test
    fun aRecordingSavedWhileTheButtonWasHeldIsNotStuck() {
        val first = SyncRecorder(lines, SyncRecorder.Mode.LINES)
        first.press(1000, 1f)   // saved before the release, e.g. the battery died
        val second = SyncRecorder(lines, SyncRecorder.Mode.LINES)
        assertTrue(second.restore(first.save()))
        assertFalse(second.holding)
        assertEquals(1, second.cursor)
        second.press(2000, 1f); second.release(2500, 1f)
        assertEquals(2, second.cursor)
    }
}
