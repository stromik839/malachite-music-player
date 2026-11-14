package app.malachite.core.heal

import app.malachite.core.audio.TagEdit
import app.malachite.core.audio.TagIO
import app.malachite.core.cover.CoverFinder
import app.malachite.core.fs.SafeFiles
import app.malachite.core.library.LibraryScanner
import app.malachite.core.library.LibraryStore
import app.malachite.core.lyrics.LyricsFinder
import app.malachite.core.text.MatchConfidence
import app.malachite.core.text.NameVariant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A healing task that was cut short (the app closed or updated) goes on from where it stopped. */
class HealResumeTest {
    private lateinit var root: File
    private lateinit var safe: SafeFiles
    private lateinit var store: LibraryStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("main").toFile()
        TagIO.configure(false)
        store = LibraryStore(root).apply { ensureCreated("test") }
        safe = SafeFiles(store.dataDir).apply { protect(root, store.dataDir) }
    }

    @AfterTest
    fun tearDown() { root.deleteRecursively() }

    private fun song(rel: String, title: String) {
        val f = File(root, rel).apply { parentFile.mkdirs() }
        javaClass.getResourceAsStream("/audio/sine.mp3")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        check(SafeFiles(File(root, ".malachite")).writeTags(f, TagEdit(title = title, artist = "A", album = "B")).isOk)
    }

    @Test
    fun goesOnFromTheItemItStoppedAt() {
        song("a.mp3", "01 - Первая")
        song("b.mp3", "02 - Вторая")
        song("c.mp3", "03 - Третья")
        val tracks = LibraryScanner(root).scan(emptyList())
        val plan = HealPlanner.titleNumbers(tracks).sortedBy { it.path }
        assertEquals(3, plan.size)
        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(tracks)
        // the first two were done before the app was closed
        exec.resumeAt = 1 to 2
        val seen = ArrayList<Int>()
        val report = exec.applyTitleNumbers(plan, { seen += it.done })
        assertEquals(1, report.changed)
        assertEquals(listOf(2, 3), seen)
        assertEquals(1, exec.loopsStarted)
        val titles = exec.index.values.associate { it.path to it.title }
        assertEquals("01 - Первая", titles["a.mp3"])
        assertEquals("Третья", titles["c.mp3"])
    }

    @Test
    fun plansSurviveBeingKeptOnDisk() {
        val json = Json { ignoreUnknownKeys = true }
        val names = listOf(
            NameFix("n1", NameField.ARTIST, listOf(NameVariant("Кино", 3), NameVariant("кино", 1)), "Кино", MatchConfidence.EXACT, "", listOf("a.mp3")),
        )
        assertEquals(names, json.decodeFromString(ListSerializer(NameFix.serializer()), json.encodeToString(ListSerializer(NameFix.serializer()), names)))
        val covers = listOf(CoverJob("c1", "A", "B", "T", listOf("a.mp3"), donor = null, imageFile = "/x/cover.jpg"))
        assertEquals(covers, json.decodeFromString(ListSerializer(CoverJob.serializer()), json.encodeToString(ListSerializer(CoverJob.serializer()), covers)))
        val formats = listOf(FormatFix("g.wav", app.malachite.core.audio.AudioFormat.entries.first(), "g.mp3"))
        assertEquals(formats, json.decodeFromString(ListSerializer(FormatFix.serializer()), json.encodeToString(ListSerializer(FormatFix.serializer()), formats)))
    }
}
