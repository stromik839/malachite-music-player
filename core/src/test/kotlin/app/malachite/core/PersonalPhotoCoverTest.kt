package app.malachite.core

import app.malachite.core.heal.CoverLook
import app.malachite.core.heal.HealPlanner
import app.malachite.core.heal.PersonalPicture
import app.malachite.core.library.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalPhotoCoverTest {
    private fun song(path: String, album: String = "") = TrackInfo(path, title = path.substringAfterLast('/'), artist = "Artist", album = album, hasArtwork = true)

    @Test
    fun aSongCarryingAPersonalPhotoIsFoundAndThePhotoGoes() {
        val photo = PersonalPicture("Artist/Telegram/cover.jpg", print = 0x0F0F_0F0F_0F0F_0F0FL, why = "camera")
        val looks = mapOf(
            // the same picture, recompressed: two squares differ
            "Artist/Синглы/a.mp3" to CoverLook(750, 1000, 90_000, "a", print = 0x0F0F_0F0F_0F0F_0F0CL),
            // an ordinary square cover
            "Artist/Album/b.mp3" to CoverLook(600, 600, 80_000, "b", print = 0x7777_0000_7777_0000L),
            // a tall picture nobody matched: offered, not ticked
            "Artist/Other/c.mp3" to CoverLook(600, 900, 70_000, "c", print = 0x1234_5678_9ABC_DEF0L),
        )
        val tracks = listOf(song("Artist/Синглы/a.mp3"), song("Artist/Album/b.mp3", "Album"), song("Artist/Other/c.mp3", "Other"))
        val plan = HealPlanner.coverQuality(tracks, { looks[it.path] }, personal = listOf(photo))
        val a = plan.first { "Artist/Синглы/a.mp3" in it.paths }
        assertTrue(a.photo && a.selected)
        val c = plan.first { "Artist/Other/c.mp3" in it.paths }
        assertTrue(c.photo)
        assertFalse(c.selected)
        assertTrue(plan.none { "Artist/Album/b.mp3" in it.paths })
        // the picture file itself is put away too
        val file = plan.first { it.files.isNotEmpty() }
        assertEquals(listOf("Artist/Telegram/cover.jpg"), file.files)
        assertTrue(file.selected)
    }
}
