package app.malachite.core

import app.malachite.core.online.OnlineMatch
import app.malachite.core.online.OnlineMatch.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnlineMatchTest {
    private fun order(query: String, vararg c: Candidate): List<Candidate> = OnlineMatch.rank(query, c.toList(), { it })

    @Test
    fun theBandComesBeforeReuploadsOfItsSongs() {
        val official = Candidate("Molchat Doma", "Дискотека")
        val reupload = Candidate("AKMV-18", "Molchat Doma - Kletka")
        val ripped = Candidate("Emre", "yt1s.com - Молчат Дома - Судно")
        val other = Candidate("lc", "Molchat Doma (Молчат Дома) - Toska")
        val got = order("molchat doma", ripped, reupload, other, official)
        assertEquals(official, got.first())
        assertEquals(ripped, got.last())
    }

    @Test
    fun partsInAnyOrderFindTheSong() {
        val song = Candidate("Молчат Дома", "Судно (Борис Рыжий)", "Этажи")
        val sameBandOtherSong = Candidate("Молчат Дома", "Тоска", "Этажи")
        val otherBandSameWord = Candidate("Кто-то", "Судно")
        for (q in listOf("молчат дома, судно", "судно, molchat doma", "Этажи; Судно", "molchat doma судно", "судно - молчат дома")) {
            assertEquals(song, order(q, otherBandSameWord, sameBandOtherSong, song).first(), q)
        }
    }

    @Test
    fun approximateWordsStillMatch() {
        val song = Candidate("Molchat Doma", "Sudno")
        // the beginning of a word, one letter off, the other script
        for (q in listOf("molch dom", "molchat domma", "молчат дома", "MOLCHAT DOMA sudn")) {
            assertTrue(OnlineMatch.score(q, song) >= OnlineMatch.ENOUGH, q)
        }
        assertTrue(OnlineMatch.score("rammstein", song) < OnlineMatch.ENOUGH)
    }

    @Test
    fun unreadableEntriesAreLeftOut() {
        val junk = Candidate("?????? ????", "? ???? ????? ????")
        val good = Candidate("Molchat Doma", "Звезды / Zvezdy")
        assertEquals(listOf(good), order("molchat doma", junk, good))
    }

    @Test
    fun anArtistNamedExactlyGoesFirst() {
        val band = Candidate("Molchat Doma", "")
        val tribute = Candidate("Molchat Doma Tribute Band", "")
        val fan = Candidate("molchatdomafan2003", "")
        assertEquals(band, order("molchat doma", fan, tribute, band).first())
    }

    @Test
    fun artistsFarBelowTheBestAreNotOffered() {
        val band = Candidate("Molchat Doma", "")
        val noise = listOf(Candidate("Doma Dankz", ""), Candidate("DOMANI", ""), Candidate("MARIA DOMARK", ""))
        val got = OnlineMatch.rank("molchat doma", noise + band, { it }, nearBest = 0.6)
        assertEquals(listOf(band), got)
        // with nothing better found, the approximate ones stay
        assertTrue(OnlineMatch.rank("molchat doma", noise, { it }, nearBest = 0.6).isNotEmpty())
    }

    @Test
    fun queryPartsAreSplitAtCommasAndDashes() {
        assertEquals(listOf("Molchat Doma", "Этажи", "Судно"), OnlineMatch.parts("Molchat Doma, Этажи ; Судно"))
        assertEquals(listOf("AC/DC", "Thunderstruck"), OnlineMatch.parts("AC/DC - Thunderstruck"))
        assertEquals("Molchat Doma Судно", OnlineMatch.plainQuery("Molchat Doma, Судно"))
    }

    @Test
    fun theWholeNameNotAWordOfALongerOne() {
        assertTrue(OnlineMatch.sameName("Кино", "Kino"))
        assertTrue(OnlineMatch.sameName("Кино", "Кино (Виктор Цой)"))
        assertTrue(OnlineMatch.sameName("Кино", "Ploho & Кино"))
        assertTrue(OnlineMatch.sameName("Молчат Дома", "Molchat Doma [Молчат Дома]"))
        assertTrue(OnlineMatch.sameName("Earth, Wind & Fire", "Earth, Wind & Fire"))
        assertTrue(OnlineMatch.sameName("Кино", "Кино - Дискография"))
        // somebody's collection with the word in its name
        kotlin.test.assertFalse(OnlineMatch.sameName("Кино", "Mike's Kino World"))
        kotlin.test.assertFalse(OnlineMatch.sameName("Молчат Дома", "Molchat Doma Tribute Band"))
        kotlin.test.assertFalse(OnlineMatch.sameName("Кино", ""))
    }
}
