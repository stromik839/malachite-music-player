package app.malachite.core

import app.malachite.core.library.TrackInfo
import app.malachite.core.soundid.AcoustId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AcoustIdTest {
    @Test
    fun aKnownSongWithItsAlbum() {
        val text = """
            {"status":"ok","results":[{"id":"r1","score":0.94,"recordings":[
              {"id":"rec-a","title":"Судно (Борис Рыжий)","duration":146,
               "artists":[{"id":"x","name":"Молчат Дома"}],
               "releasegroups":[{"id":"g1","title":"Этажи","type":"Album"},
                                {"id":"g2","title":"Post-Punk Hits","type":"Album","secondarytypes":["Compilation"]},
                                {"id":"g3","title":"Судно","type":"Single"}]},
              {"id":"rec-b","title":"Судно (Борис Рыжий)","artists":[{"name":"Молчат Дома"}],"releasegroups":[]}
            ]},{"id":"r2","score":0.41,"recordings":[{"id":"rec-c","title":"Other","artists":[{"name":"A","joinphrase":" & "},{"name":"B"}]}]}]}
        """.trimIndent()
        val a = assertIs<AcoustId.Answer.Found>(AcoustId.parse(text))
        assertEquals(2, a.matches.size)
        val best = a.matches.first()
        assertEquals("Молчат Дома", best.artist)
        assertEquals("Этажи", best.album)
        assertEquals(146, best.durationSec)
        assertEquals("A & B", a.matches.last().artist)
    }

    @Test
    fun aWrongKeyIsSaidSo() {
        val a = AcoustId.parse("""{"status":"error","error":{"code":4,"message":"invalid API key"}}""")
        assertIs<AcoustId.Answer.KeyProblem>(a)
        assertEquals(AcoustId.KeyTrouble.INVALID, a.trouble)
        assertIs<AcoustId.Answer.Failed>(AcoustId.parse("""{"status":"error","error":{"code":14,"message":"too many requests"}}"""))
        assertIs<AcoustId.Answer.Failed>(AcoustId.parse("<html>Bad gateway</html>"))
        assertEquals(0, assertIs<AcoustId.Answer.Found>(AcoustId.parse("""{"status":"ok","results":[]}""")).matches.size)
    }

    @Test
    fun whichSongsAreWorthListeningTo() {
        for (t in listOf("115620301", "tmpeicn5cwv", "Track 3", "AUD-20240101-WA0001", "youtube video #c2nVYwK-DFs", "")) {
            assertTrue(AcoustId.meaninglessTitle(t), t)
        }
        for (t in listOf("1989", "Судно", "Track Me Down", "99 Problems", "7 rings")) assertFalse(AcoustId.meaninglessTitle(t), t)
        assertTrue(AcoustId.worthListening(TrackInfo("a/b/115620301.mp3", title = "115620301", artist = "Кто-то"), false))
        assertTrue(AcoustId.worthListening(TrackInfo("a/b/x.mp3", title = "Судно"), false))
        assertFalse(AcoustId.worthListening(TrackInfo("a/b/x.mp3", title = "Судно", artist = "Молчат Дома"), false))
    }
}
