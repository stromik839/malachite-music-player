package app.malachite.core

import app.malachite.core.net.QueryVariants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryVariantsTest {
    @Test
    fun soundMarksAtTheEndOfATitleAreDropped() {
        assertEquals("потрошу тебя", QueryVariants.stripVersionTags("потрошу тебя speed up, bass boosted"))
        assertEquals("Ferrari", QueryVariants.stripVersionTags("Ferrari - slowed + reverb"))
        assertEquals("Дурачок", QueryVariants.stripVersionTags("Дурачок sped up"))
        assertEquals("Song", QueryVariants.stripVersionTags("Song (Sped Up Version)"))
        assertEquals("Polish Cow", QueryVariants.stripVersionTags("Polish Cow super slowed"))
    }

    @Test
    fun ordinaryTitlesStayWhole() {
        assertEquals("Slowed Dance", QueryVariants.stripVersionTags("Slowed Dance"))
        assertEquals("Speed", QueryVariants.stripVersionTags("Speed"))
        assertEquals("Группа крови", QueryVariants.stripVersionTags("Группа крови"))
    }

    @Test
    fun theSearchTriesTheTitleWithoutTheMarks() {
        val queries = QueryVariants.build("красная плесень", "потрошу тебя speed up, bass boosted", allowSwap = false, isTitle = true)
        assertTrue(queries.any { it.second == "потрошу тебя" }, queries.toString())
    }
}
