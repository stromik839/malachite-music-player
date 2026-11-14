package app.malachite.core

import app.malachite.core.audio.TagEdit
import app.malachite.core.audio.TagIO
import app.malachite.core.cover.CoverFinder
import app.malachite.core.fs.DataProtection
import app.malachite.core.fs.PathGuard
import app.malachite.core.fs.SafeFiles
import app.malachite.core.heal.HealExecutor
import app.malachite.core.heal.ImagePrep
import app.malachite.core.heal.JunkItem
import app.malachite.core.heal.LayoutMove
import app.malachite.core.library.LibraryStore
import app.malachite.core.lyrics.LyricsFinder
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Attacks on the player's own files: plans and records in the main folder can be changed by
 * other apps, so a path in them must never lead the player outside the main folder.
 */
class SecurityTest {
    private lateinit var base: File
    private lateinit var root: File
    private lateinit var outside: File

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("sec").toFile()
        root = File(base, "Music/Malachite").apply { mkdirs() }
        // something precious next to the main folder: a photo of the user
        outside = File(base, "DCIM/photo.jpg").apply { parentFile.mkdirs(); writeText("a photo") }
        TagIO.configure(false)
    }

    @AfterTest
    fun tearDown() { base.deleteRecursively() }

    @Test
    fun pathsThatLeaveTheFolderAreRefused() {
        assertTrue(PathGuard.inside(root, File(root, "Artist/Album/song.mp3")))
        assertTrue(PathGuard.inside(root, root))
        assertFalse(PathGuard.inside(root, File(root, "../../DCIM/photo.jpg")))
        assertFalse(PathGuard.inside(root, File(root, "Artist/../../Malachite2/x.mp3")))
        assertNull(PathGuard.child(root, "../../DCIM/photo.jpg"))
        assertNull(PathGuard.child(root, ""))
        assertNotNull(PathGuard.child(root, "Artist/song.mp3"))
        // a folder whose name only starts the same way is not inside
        assertFalse(PathGuard.inside(root, File(base, "Music/Malachite-other/x.mp3")))
    }

    @Test
    fun aLinkOutOfTheFolderIsRefused() {
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), outside.parentFile.toPath())
        assertFalse(PathGuard.inside(root, File(link, "photo.jpg")))
    }

    @Test
    fun aTamperedPlanCannotMoveOrTrashFilesOutsideTheFolder() {
        val store = LibraryStore(root).apply { ensureCreated("test") }
        val safe = SafeFiles(store.dataDir).apply { protect(root, store.dataDir) }
        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(emptyList())

        val moved = exec.applyLayout(listOf(LayoutMove("../../DCIM/photo.jpg", "stolen.jpg")))
        assertEquals(0, moved.changed)
        val trashed = exec.applyJunk(listOf(JunkItem("../../DCIM/photo.jpg", "junk", null, selected = true)))
        assertEquals(0, trashed.changed)

        assertTrue(outside.isFile, "the photo outside the main folder is untouched")
        assertEquals("a photo", outside.readText())
        assertFalse(File(root, "stolen.jpg").exists())
    }

    @Test
    fun aReplacedProtectionFileCannotHangTheApp() {
        val data = File(root, ".malachite").apply { mkdirs() }
        val (d, _) = DataProtection.create("пароль".toCharArray(), iterations = 2000)
        DataProtection.write(data, d.copy(iterations = 2_000_000_000))
        assertNull(DataProtection.read(data), "absurd rounds are not accepted")
        assertNull(DataProtection.keyFor(d.copy(iterations = 2_000_000_000), "пароль".toCharArray()))
        DataProtection.write(data, d)
        assertNotNull(DataProtection.read(data))
    }
}
