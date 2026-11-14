package app.malachite.core

import app.malachite.core.heal.HealPlanner
import app.malachite.core.library.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilationsTest {
    private fun t(artist: String, album: String, albumArtist: String = "", n: Int = 0) =
        TrackInfo(path = "$artist/$album/$n.mp3", title = "song $n", artist = artist, album = album, albumArtist = albumArtist)

    @Test
    fun aScatteredCompilationIsFound() {
        val tracks = listOf(
            t("Armin van Buuren", "A State of Trance 2023", n = 1),
            t("Ahmed Helmy", "A State of Trance 2023", n = 2),
            t("Ferry Corsten", "A State of Trance 2023", n = 3),
            t("Gareth Emery", "A State of Trance 2023", n = 4),
        )
        val found = HealPlanner.compilations(tracks)
        assertEquals(1, found.size)
        assertEquals(4, found[0].paths.size)
        assertTrue(found[0].confident)
    }

    @Test
    fun anArtistsOwnAlbumWithGuestsIsLeftAlone() {
        val tracks = (1..8).map { t("Кино", "Группа крови", n = it) } + t("Кино feat. Гость", "Группа крови", n = 9) + t("Другой", "Группа крови", n = 10)
        assertTrue(HealPlanner.compilations(tracks).isEmpty())
    }

    @Test
    fun analbumThatIsAlreadyWholeIsLeftAlone() {
        val tracks = listOf("A", "B", "C", "D").mapIndexed { i, a -> t(a, "Хиты 90-х", albumArtist = "Разные исполнители", n = i) }
        assertTrue(HealPlanner.compilations(tracks).isEmpty())
    }

    @Test
    fun aCompilationByNameNeedsOnlyTwoArtists() {
        val tracks = listOf(t("A", "Лучшее 2020", n = 1), t("A", "Лучшее 2020", n = 2), t("B", "Лучшее 2020", n = 3))
        assertEquals(1, HealPlanner.compilations(tracks).size)
    }
}
