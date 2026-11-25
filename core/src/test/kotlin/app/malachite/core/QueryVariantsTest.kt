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

    @Test
    fun aTrackNumberInFrontIsNotAskedFor() {
        // «08 Наваждение» found only a wrong plain text; «Наваждение» finds the synced ones
        val first = QueryVariants.build("Гражданская Оборона", "08 Наваждение", isTitle = true).first()
        assertEquals("Наваждение", first.second)
        assertEquals("Song", app.malachite.core.text.Watermarks.searchTitle("08. Song", null))
        assertEquals("Song", app.malachite.core.text.Watermarks.searchTitle("8) Song", null))
        assertEquals("Song", app.malachite.core.text.Watermarks.searchTitle("12 - Song", null))
        assertEquals("Song", app.malachite.core.text.Watermarks.searchTitle("03_Song", null))
    }

    @Test
    fun aNumberThatIsPartOfTheNameStays() {
        assertEquals("21 Guns", app.malachite.core.text.Watermarks.searchTitle("21 Guns", null))
        assertEquals("7 Seconds", app.malachite.core.text.Watermarks.searchTitle("7 Seconds", null))
        assertEquals("1000 лет", app.malachite.core.text.Watermarks.searchTitle("1000 лет", null))
        assertEquals("08", app.malachite.core.text.Watermarks.searchTitle("08", null))
        // a bare number is also tried without it, after the title as it is
        val queries = QueryVariants.build("Гражданская Оборона", "8 Наваждение", allowSwap = false, isTitle = true)
        assertEquals("8 Наваждение", queries.first().second)
        assertTrue(queries.any { it.second == "Наваждение" }, queries.toString())
    }

    @Test
    fun aSongFoundWithoutTheNumberScoresAsAnExactMatch() {
        val s = app.malachite.core.lyrics.LyricsFinder.score("08 Наваждение", "Гражданская Оборона", 151.0, "Наваждение", "Гражданская оборона", 151.0, synced = true)
        assertTrue(s >= 0.95, s.toString())
    }
}
