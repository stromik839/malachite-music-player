package app.malachite.core

import app.malachite.core.heal.OddTags
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OddTagsTest {
    @Test
    fun sitesAndPlaceholdersAreNoAlbums() {
        assertNotNull(OddTags.albumProblem("muzrecord.com", "Я ебу собак", "Ебанько"))
        assertNotNull(OddTags.albumProblem("Хиты 2020 (Zvukon.net)", "Песня", "Кто-то"))
        assertNotNull(OddTags.albumProblem("Unknown Album", "Song", "Band"))
        assertNotNull(OddTags.albumProblem("Неизвестный альбом", "Песня", "Группа"))
    }

    @Test
    fun theSongWrittenAgainIsNoAlbum() {
        assertNotNull(OddTags.albumProblem("ЕБАНЬКО Я Ебу Собак", "Я ебу собак", "Ебанько"))
        assertNotNull(OddTags.albumProblem("Ебанько Лешка Лох", "Лешка лох", "Ебанько"))
        // in Latin letters, with a counter left by a download site
        assertNotNull(OddTags.albumProblem("Ebanko Ebi Menya Po Kitayski Sosi Moy Huy Po Turetski ( 1)", "Еби меня по-китайски, соси мой хуй по-турецки", "Ебанько"))
        // and with a word spelled another way
        assertNotNull(OddTags.albumProblem("Ebanko Ebi Menya Po Kitayski Sosi Moy Xuy Po Turetski ( 1)", "Еби меня по-китайски, соси мой хуй по-турецки", "Ебанько"))
    }

    @Test
    fun realAlbumsStay() {
        // a single is named like its song
        assertNull(OddTags.albumProblem("Я ебу собак", "Я ебу собак", "Ебанько"))
        assertNull(OddTags.albumProblem("2ch OST 2", "Я ебу собак", "Ебанько"))
        assertNull(OddTags.albumProblem("Этажи", "Судно (Борис Рыжий)", "Молчат Дома"))
        assertNull(OddTags.albumProblem("Группа крови", "Группа крови", "Кино"))
        assertNull(OddTags.albumProblem("Bohemian Rhapsody (The Original Soundtrack)", "Bohemian Rhapsody", "Queen"))
        assertNull(OddTags.albumProblem("A Night At The Opera", "Bohemian Rhapsody", "Queen"))
        // the band's name on a real album with the song's name in it
        assertNull(OddTags.albumProblem("Queen Greatest Hits: Bohemian Rhapsody Edition", "Bohemian Rhapsody", "Queen"))
    }

    @Test
    fun theFolderOfAMessengerIsNoAlbum() {
        assertNotNull(OddTags.albumProblem("AyuGram", "115620301", "Неизвестный исполнитель"))
        assertNotNull(OddTags.albumProblem("Telegram Audio", "Песня", "Группа"))
        assertNotNull(OddTags.artistProblem("AyuGram"))
        kotlin.test.assertNull(app.malachite.core.library.LibraryLayout.albumFromFolder("AyuGram"))
        kotlin.test.assertEquals("Этажи", app.malachite.core.library.LibraryLayout.albumFromFolder("Этажи (2018)"))
        // many artists under "AyuGram" are no compilation
        val downloads = (1..6).map { app.malachite.core.library.TrackInfo("x/AyuGram/$it.mp3", title = "$it", artist = "Artist $it", album = "AyuGram") }
        kotlin.test.assertTrue(app.malachite.core.heal.HealPlanner.compilations(downloads).isEmpty())
    }

    @Test
    fun compilationMarkAndSongInTheArtist() {
        // healing once took the folder "Разные исполнители" for the artist of songs without tags
        assertNotNull(OddTags.artistProblem("Разные исполнители"))
        assertNotNull(OddTags.artistProblem("Various Artists"))
        assertTrue(app.malachite.core.library.FolderHints.isGeneric("Разные исполнители"))
        kotlin.test.assertEquals(
            "Молчат Дома (Molchat Doma)",
            OddTags.artistWithoutTitle("Молчат Дома (Molchat Doma) - Судно (Sudno)", "Молчат Дома (Molchat Doma) - Судно (Sudno)".substringAfter(" - ")),
        )
        kotlin.test.assertEquals("Кино", OddTags.artistWithoutTitle("Кино - Группа крови", "Группа крови"))
        // a band with a dash in its name and another song stays
        kotlin.test.assertNull(OddTags.artistWithoutTitle("Kid Cudi - MGMT", "Pursuit of Happiness"))
        kotlin.test.assertNull(OddTags.artistProblem("AC/DC", "Thunderstruck"))
    }

    @Test
    fun theArtistWrittenIntoTheTitle() {
        val both = "Молчат Дома (Molchat Doma) - Судно (Sudno)"
        kotlin.test.assertEquals("Молчат Дома (Molchat Doma)" to "Судно (Sudno)", OddTags.splitArtistAndSong(both, both))
        kotlin.test.assertEquals("Кино" to "Группа крови", OddTags.splitArtistAndSong("Кино", "Кино - Группа крови"))
        assertNotNull(OddTags.titleProblem("Кино", "КИНО — Группа крови"))
        // a title with a dash of its own stays
        kotlin.test.assertNull(OddTags.splitArtistAndSong("Queen", "Bohemian Rhapsody - Remastered 2011"))
        kotlin.test.assertNull(OddTags.titleProblem("Кино", "Группа крови"))
    }

    @Test
    fun artists() {
        assertNotNull(OddTags.artistProblem("www.muzofon.com"))
        assertNotNull(OddTags.artistProblem("Unknown Artist"))
        assertNull(OddTags.artistProblem("Ебанько"))
        assertNull(OddTags.artistProblem("AC/DC"))
        assertNull(OddTags.artistProblem("Mr. Credo"))
    }
}
