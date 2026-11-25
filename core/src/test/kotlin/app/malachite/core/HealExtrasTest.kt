package app.malachite.core

import app.malachite.core.audio.LoudnessMeter
import app.malachite.core.heal.CaseField
import app.malachite.core.heal.CoverLook
import app.malachite.core.heal.HealPlanner
import app.malachite.core.library.TrackInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealExtrasTest {
    private fun t(title: String, track: Int = 0, artist: String = "Кино", album: String = "Группа крови", path: String = "$artist/$album/$title.mp3") =
        TrackInfo(path = path, title = title, artist = artist, album = album, track = track)

    @Test
    fun numbersInTitlesButNotTitlesWithNumbers() {
        val fixes = HealPlanner.titleNumbers(
            listOf(t("01 - Группа крови"), t("02. Закрой за мной дверь"), t("7-я пятница"), t("99 Luftballons"), t("1979"), t("15 лет (паспорт)"), t("03) Война", track = 5)),
        )
        assertEquals(listOf("Группа крови", "Закрой за мной дверь", "Война"), fixes.map { it.newTitle })
        assertEquals(1, fixes[0].track)
        assertTrue(fixes[0].selected)
        // the tag says 5, the title says 3: offered, not ticked
        assertEquals(false, fixes[2].selected)
    }

    @Test
    fun zeroPaddedAndAlbumWideNumbers() {
        // a number with a leading zero and a space is a track number
        val padded = HealPlanner.titleNumbers(listOf(t("09 Иван Говнов", album = "Попс")))
        assertEquals(listOf("Иван Говнов"), padded.map { it.newTitle })
        assertEquals(9, padded.single().track)
        // a bare number and a space: only when the folder is numbered like that throughout
        val folder = listOf(t("8 Против", album = "Х"), t("9 Иван", album = "Х"), t("10 Лес", album = "Х"), t("11 Дом", album = "Х"))
        assertEquals(listOf("Против", "Иван", "Лес", "Дом"), HealPlanner.titleNumbers(folder).map { it.newTitle })
        // a single «99 Luftballons» among normal titles stays, and so does a title that is only numbers
        val mixed = listOf(t("99 Luftballons", album = "Y"), t("Песня", album = "Y"), t("Другая", album = "Y"), t("2 3", album = "Y"))
        assertTrue(HealPlanner.titleNumbers(mixed).isEmpty())
    }

    @Test
    fun titlesThatAreOnlyNumbersComeFromTheAlbumList() {
        fun song(title: String, track: Int, ms: Long) = TrackInfo(
            path = "ГО/Попс/$title.mp3", title = title, artist = "Гражданская Оборона",
            album = "Гражданская Оборона — Попс 1993", track = track, durationMs = ms,
        )
        val songs = listOf(song("13", 13, 150_000), song("14", 0, 200_000), song("Нормальная", 1, 100_000))
        var asked: Pair<String, String>? = null
        val fixes = HealPlanner.numberTitles(songs) { artist, album ->
            asked = artist to album
            listOf(
                app.malachite.core.cover.AlbumTracks.Tracklist(
                    "test", "Попс", "Гражданская Оборона",
                    listOf(
                        app.malachite.core.cover.AlbumTracks.Track(13, "Песня тринадцать", 151_000),
                        app.malachite.core.cover.AlbumTracks.Track(14, "Песня четырнадцать", 260_000),
                    ),
                ),
            )
        }
        // looked up without the artist's name in front of the album
        assertEquals("Гражданская Оборона" to "Попс 1993", asked)
        assertEquals(listOf("Песня тринадцать", "Песня четырнадцать"), fixes.map { it.newTitle })
        // the length agrees: ticked; it does not (a minute off): offered only
        assertTrue(fixes[0].selected)
        assertEquals(false, fixes[1].selected)
        // the number goes into the tag where it had none
        assertEquals(null, fixes[0].track)
        assertEquals(14, fixes[1].track)
    }

    @Test
    fun anAlbumNamedAfterItsArtistLosesTheArtist() {
        val songs = listOf(
            t("Песня 1", artist = "Гражданская Оборона", album = "Гражданская Оборона — Попс 1993"),
            t("Песня 2", artist = "Гражданская Оборона", album = "Гражданская Оборона — Попс 1993"),
        )
        val album = HealPlanner.names(songs).first { it.field == app.malachite.core.heal.NameField.ALBUM }
        assertEquals("Попс 1993", album.canonical)
    }

    @Test
    fun letterCase() {
        assertEquals("Группа крови", HealPlanner.normalCase("ГРУППА КРОВИ"))
        assertEquals("Highway to Hell", HealPlanner.normalCase("HIGHWAY TO HELL"))
        assertEquals("Ferrari Sped Up", HealPlanner.normalCase("ferrari sped up"))
        assertEquals("DJ Smash Moscow", HealPlanner.normalCase("DJ smash moscow"))
        val fixes = HealPlanner.letterCase(listOf(t("ЗВЕЗДА ПО ИМЕНИ СОЛНЦЕ"), t("Кукушка"), t("ok", artist = "KRASKI")))
        assertEquals(setOf(CaseField.TITLE, CaseField.ARTIST), fixes.map { it.field }.toSet())
        assertTrue(fixes.none { it.selected })
    }

    @Test
    fun coverQuality() {
        val tracks = listOf(
            t("a").copy(hasArtwork = true), t("b").copy(hasArtwork = true),
            t("c", album = "Другой").copy(hasArtwork = true),
            t("d", album = "Третий").copy(hasArtwork = true),
        )
        val issues = HealPlanner.coverQuality(tracks, { tr ->
            when (tr.album) { "Группа крови" -> CoverLook(120, 120, 9000, "a"); "Другой" -> CoverLook(3000, 3000, 4_000_000, "b"); else -> CoverLook(800, 800, 200_000, "c") }
        })
        assertEquals(2, issues.size)
        assertEquals(2, issues.first { !it.heavy }.paths.size)
        assertTrue(issues.any { it.heavy && it.album == "Другой" })
        assertTrue(issues.none { it.shared })
    }

    @Test
    fun oneCoverOnDifferentAlbumsIsReplacedByEachAlbumsOwn() {
        // a discography downloaded with one picture: «45» on «Ночь» and «Группа крови» too
        val tracks = listOf(
            t("Время есть, а денег нет", album = "45"), t("Электричка", album = "45"),
            t("Видели ночь", album = "Ночь"), t("Фильмы", album = "Ночь"),
            t("Группа крови"), t("Война"),
            t("Пачка сигарет", album = "Последний герой"),
            t("Это не любовь", album = ""),
        ).map { it.copy(hasArtwork = true) }
        val look = { tr: TrackInfo -> if (tr.album == "Последний герой") CoverLook(600, 600, 90_000, "own") else CoverLook(600, 600, 80_000, "same") }
        val issues = HealPlanner.coverQuality(tracks, look)
        assertEquals(setOf("45", "Ночь", "Группа крови", ""), issues.map { it.album }.toSet())
        assertTrue(issues.all { it.shared && !it.heavy && it.sharedBy == 4 })
        assertEquals(2, issues.first { it.album == "Ночь" }.paths.size)
        // a cover the user picked is theirs: «Ночь» is left alone and is not counted
        val kept = HealPlanner.coverQuality(tracks, look, chosen = { it.album == "Ночь" })
        assertTrue(kept.none { it.album == "Ночь" })
        assertTrue(kept.all { it.sharedBy == 3 })
        // one album with one picture on every song is just an album
        assertTrue(HealPlanner.coverQuality(tracks.filter { it.album == "45" }, look).isEmpty())
    }

    @Test
    fun internetArchiveDiscographyGivesEachAlbumItsOwnPicture() {
        val songs = listOf("45 (1982)/Электричка.mp3", "Ночь (1986)/Фильмы.mp3", "Синглы/Это не любовь.mp3", "Звезда/CD1/a.mp3", "Звезда/CD2/b.mp3")
        val pictures = listOf("kino.jpg", "45 (1982)/back.jpg", "45 (1982)/front.jpg", "Ночь (1986)/Scans/folder.jpg", "Звезда/cover.png")
        val pick = app.malachite.core.online.ArchiveCovers.picker(pictures, songs) { Regex("(?i)cover|front|folder").containsMatchIn(it) }
        assertEquals("45 (1982)/front.jpg", pick("45 (1982)/Электричка.mp3"))
        assertEquals("Ночь (1986)/Scans/folder.jpg", pick("Ночь (1986)/Фильмы.mp3"))
        // the band's photo at the top is no album's cover
        assertNull(pick("Синглы/Это не любовь.mp3"))
        // the discs of one album share its cover
        assertEquals("Звезда/cover.png", pick("Звезда/CD1/a.mp3"))
        assertEquals("Звезда/cover.png", pick("Звезда/CD2/b.mp3"))
        // one album in one folder: any picture of the entry, also from a folder of scans
        val one = app.malachite.core.online.ArchiveCovers.picker(listOf("scans/front.jpg"), listOf("01.mp3", "02.mp3")) { it.contains("front") }
        assertEquals("scans/front.jpg", one("01.mp3"))
        // folders named by numbers are albums, not discs
        val numbered = app.malachite.core.online.ArchiveCovers.picker(listOf("cover.jpg"), listOf("45/a.mp3", "46/b.mp3")) { true }
        assertNull(numbered("45/a.mp3"))
    }

    @Test
    fun trackNumbersFromTheListOfTheAlbum() {
        val local = listOf(t("Кукушка"), t("Группа крови"), t("Спокойная ночь"))
        val fixes = HealPlanner.matchNumbers(local, listOf("Группа крови" to 1, "Закрой за мной дверь" to 2, "Спокойная ночь" to 9, "Кукушка" to 10))
        assertEquals(mapOf("Кукушка" to 10, "Группа крови" to 1, "Спокойная ночь" to 9), fixes.associate { it.title to it.track })
        assertEquals(1, HealPlanner.albumsWithoutNumbers(local).size)
    }

    private fun sine(meter: LoudnessMeter, rate: Int, seconds: Double, amp: Double) {
        val n = (rate * seconds).toInt()
        val buf = FloatArray(n * 2)
        for (i in 0 until n) {
            val v = (amp * sin(2 * PI * 1000.0 * i / rate)).toFloat()
            buf[2 * i] = v; buf[2 * i + 1] = v
        }
        meter.feed(buf, n)
    }

    @Test
    fun loudnessOfAKnownTone() {
        // EBU Tech 3341, test 1: a stereo 1 kHz sine at -23 dBFS measures -23 LUFS; so -20 dBFS gives -20
        val m = LoudnessMeter(48000, 2)
        sine(m, 48000, 5.0, 0.1) // -20 dBFS
        val l = assertNotNull(m.integrated())
        assertTrue(abs(l - -20.0) < 0.1, "measured $l")
        // twice as loud is +6 dB
        val m2 = LoudnessMeter(44100, 2)
        sine(m2, 44100, 5.0, 0.2)
        assertTrue(abs(m2.integrated()!! - l - 6.02) < 0.3)
        // silence has no loudness
        val m3 = LoudnessMeter(44100, 2)
        m3.feed(FloatArray(44100 * 4), 44100 * 2)
        assertNull(m3.integrated())
        assertEquals(-3.0, LoudnessMeter.gainFor(-11.0), 1e-9)
    }
}
