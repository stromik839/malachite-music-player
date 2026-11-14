package app.malachite.core

import app.malachite.core.library.PlaylistOrder
import app.malachite.core.library.PlaylistSorting
import app.malachite.core.library.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSortingTest {
    private fun t(title: String, album: String, year: String, track: Int, artist: String = "Кино", ms: Long = 200_000) =
        TrackInfo(path = "$artist/$album/$title.mp3", title = title, artist = artist, album = album, year = year, track = track, durationMs = ms)

    private val mixed = listOf(
        t("Звезда по имени Солнце", "Звезда по имени Солнце", "1989", 1),
        t("Электричка", "45", "1982", 7),
        t("Группа крови", "Группа крови", "1988", 1),
        t("Время есть, а денег нет", "45", "1982", 1),
        t("Война", "Группа крови", "1988", 3),
        t("Пачка сигарет", "Звезда по имени Солнце", "1989", 0),
        t("Последний герой", "Ночь", "1986", 5),
        t("Kickstart My Heart", "Dr. Feelgood", "1989", 1, artist = "Mötley Crüe", ms = 290_000),
    )

    @Test
    fun songsOfOneAlbumTogetherInTheirOrder() {
        assertEquals(
            listOf(
                "Kickstart My Heart",
                "Время есть, а денег нет", "Электричка",
                "Последний герой",
                "Группа крови", "Война",
                "Звезда по имени Солнце", "Пачка сигарет",
            ),
            PlaylistSorting.sort(mixed, PlaylistOrder.ALBUMS).map { it.title },
        )
    }

    @Test
    fun otherOrders() {
        assertEquals("Время есть, а денег нет", PlaylistSorting.sort(mixed, PlaylistOrder.YEARS).first().title)
        assertEquals("Kickstart My Heart", PlaylistSorting.sort(mixed, PlaylistOrder.LONGEST).first().title)
        assertEquals(mixed.reversed(), PlaylistSorting.sort(mixed, PlaylistOrder.REVERSE))
        // Russian letters in their own order: «Война» before «Время», «Ё» as «Е»
        val titles = PlaylistSorting.sort(mixed, PlaylistOrder.TITLES).map { it.title }.filter { it.first() in 'А'..'я' }
        assertEquals(listOf("Война", "Время есть, а денег нет", "Группа крови", "Звезда по имени Солнце", "Пачка сигарет", "Последний герой", "Электричка"), titles)
        val added = mapOf("Электричка" to 5L, "Война" to 9L)
        assertEquals("Война", PlaylistSorting.sort(mixed, PlaylistOrder.ADDED) { added[it.title] ?: 0L }.first().title)
    }
}
