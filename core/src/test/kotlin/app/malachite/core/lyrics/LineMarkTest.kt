package app.malachite.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineMarkTest {
    private val song = listOf("Припев, припев!", "Куплет первый", "", "припев припев", "Куплет второй", "ПРИПЕВ — припев")

    @Test
    fun repeatsGetTheirOwnKeys() {
        assertEquals(
            listOf("припев припев#0", "куплет первый#0", "", "припев припев#1", "куплет второй#0", "припев припев#2"),
            lineMarkKeys(song),
        )
    }

    @Test
    fun onlyTheChosenRepeatIsMarked() {
        val keys = lineMarkKeys(song)
        val marks = setOf(keys[3])
        assertEquals(listOf(false, false, false, true, false, false), keys.map { isLineMarked(it, marks) })
    }

    @Test
    fun anOldMarkBelongsToTheFirstRepeatOnly() {
        val keys = lineMarkKeys(song)
        val marks = setOf("припев припев")
        assertTrue(isLineMarked(keys[0], marks))
        assertFalse(isLineMarked(keys[3], marks))
        assertFalse(isLineMarked(keys[5], marks))
        assertFalse(isLineMarked("", setOf("")))
    }
}
