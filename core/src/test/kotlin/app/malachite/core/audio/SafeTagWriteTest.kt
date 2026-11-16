package app.malachite.core.audio

import app.malachite.core.fs.SafeFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SafeTagWriteTest {

    private lateinit var dir: File
    private lateinit var data: File
    private lateinit var safe: SafeFiles

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("malachite-test").toFile()
        data = File(dir, ".malachite")
        safe = SafeFiles(data)
        TagIO.configure(android = false)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun fixture(name: String, as_: String = name): File {
        val f = File(dir, as_)
        javaClass.getResourceAsStream("/audio/$name")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun resource(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.readBytes()

    @Test
    fun detectsRealFormatRegardlessOfExtension() {
        assertEquals(AudioFormat.MP3, AudioFormat.detect(fixture("sine.mp3")))
        assertEquals(AudioFormat.FLAC, AudioFormat.detect(fixture("sine.flac")))
        assertEquals(AudioFormat.OGG_VORBIS, AudioFormat.detect(fixture("sine.ogg")))
        assertEquals(AudioFormat.OPUS, AudioFormat.detect(fixture("sine.opus")))
        assertEquals(AudioFormat.M4A, AudioFormat.detect(fixture("sine.m4a")))
        assertEquals(AudioFormat.WAV, AudioFormat.detect(fixture("sine.wav")))
        assertEquals(AudioFormat.AIFF, AudioFormat.detect(fixture("sine.aiff")))
        val misnamed = fixture("actually_mp3.wav")
        assertEquals(AudioFormat.MP3, AudioFormat.detect(misnamed))
        assertFalse(AudioFormat.extensionMatches(misnamed.extension, AudioFormat.MP3))
    }

    @Test
    fun readsCyrillicTags() {
        val t = TagIO.read(fixture("sine.mp3"))!!
        assertEquals("Подводная лодка", t.title)
        assertEquals("Свидетельство о смерти", t.artist)
        assertTrue(t.durationMs in 1500..2600, "duration ${t.durationMs}")
        val o = TagIO.read(fixture("sine.opus"))!!
        assertEquals("Опус", o.title)
        assertEquals("𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁", o.artist)
        assertTrue(o.durationMs in 1500..2600, "opus duration ${o.durationMs}")
    }

    @Test
    fun embedsCoverIntoEveryTaggableFormatWithoutTouchingAudio() {
        val jpeg = resource("cover.jpg")
        for (name in listOf("sine.mp3", "sine.flac", "sine.ogg", "sine.opus", "sine.m4a", "sine.wav", "sine.aiff")) {
            val f = fixture(name)
            val format = AudioFormat.detect(f)
            val before = AudioPayload.fingerprint(f, format)
            assertNotNull(before, "fingerprint for $name")
            val r = safe.writeTags(f, TagEdit(album = "Всем смертям назло", artwork = TagEdit.Artwork(jpeg, "image/jpeg", 320, 320)), dir)
            assertTrue(r.isOk, "$name: $r")
            assertEquals(before, AudioPayload.fingerprint(f, format), "audio changed in $name")
            val art = TagIO.readArtwork(f, format)
            assertNotNull(art, "cover missing in $name")
            assertContentEquals(jpeg, art)
            assertEquals("Всем смертям назло", TagIO.read(f, format)!!.album, name)
            assertEquals(format, AudioFormat.detect(f))
        }
    }

    /**
     * The same as on a phone: the tag library switched to Android, where its own way of making a
     * FLAC or Ogg picture block throws. Every taggable format must still take a cover (a PNG one
     * too, and one whose type was not given).
     */
    @Test
    fun embedsCoverTheAndroidWay() {
        val options = org.jaudiotagger.tag.TagOptionSingleton.getInstance()
        val was = options.isAndroid
        options.isAndroid = true
        try {
            val jpeg = resource("cover.jpg")
            val png = resource("cover.png")
            for (name in listOf("sine.flac", "sine.ogg", "sine.mp3", "sine.m4a", "sine.wav", "sine.aiff", "sine.opus")) {
                for ((picture, mime) in listOf(jpeg to "image/jpeg", png to "")) {
                    val f = fixture(name)
                    val format = AudioFormat.detect(f)
                    val before = AudioPayload.fingerprint(f, format)
                    val r = safe.writeTags(f, TagEdit(title = "Перевод", artwork = TagEdit.Artwork(picture, mime)), dir)
                    assertTrue(r.isOk, "$name ($mime): $r")
                    assertEquals(before, AudioPayload.fingerprint(f, format), "audio changed in $name")
                    assertContentEquals(picture, TagIO.readArtwork(f, format), "cover in $name ($mime)")
                    assertEquals("Перевод", TagIO.read(f, format)!!.title, name)
                    f.delete()
                }
            }
        } finally {
            options.isAndroid = was
        }
    }

    @Test
    fun renamingAFieldKeepsOtherFields() {
        val f = fixture("sine.mp3")
        assertTrue(safe.writeTags(f, TagEdit(artwork = TagEdit.Artwork(resource("cover.png"), "image/png"))).isOk)
        assertTrue(safe.writeTags(f, TagEdit(artist = "Свидетельство о смерти (fixed)")).isOk)
        val t = TagIO.read(f)!!
        assertEquals("Подводная лодка", t.title)
        assertEquals("Свидетельство о смерти (fixed)", t.artist)
        assertTrue(t.hasArtwork)
    }

    @Test
    fun refusesToTagMisnamedFile() {
        val f = fixture("actually_mp3.wav")
        val r = safe.writeTags(f, TagEdit(title = "x"))
        assertTrue(r is SafeFiles.Result.Skipped, "$r")
        // fixing the extension first makes it writable
        val moved = safe.move(f, File(dir, "actually_mp3.mp3"))
        assertTrue(moved.isOk)
        val fixed = (moved as SafeFiles.Result.Ok).file
        assertTrue(safe.writeTags(fixed, TagEdit(title = "Fixed")).isOk)
        assertEquals("Fixed", TagIO.read(fixed)!!.title)
    }

    @Test
    fun backupKeepsOriginalBytes() {
        val f = fixture("sine.flac")
        val original = f.readBytes()
        val session = safe.startBackupSession("test")
        assertTrue(safe.writeTags(f, TagEdit(title = "Новое"), dir).isOk)
        val backup = File(session, "sine.flac")
        assertTrue(backup.isFile)
        assertContentEquals(original, backup.readBytes())
        assertEquals("Новое", TagIO.read(f)!!.title)
    }

    @Test
    fun opusRewriteHandlesLargeCoverAcrossPages() {
        val f = fixture("sine.opus")
        val big = ByteArray(300_000) { (it % 251).toByte() }.also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }
        val before = AudioPayload.fingerprint(f)
        assertTrue(safe.writeTags(f, TagEdit(artwork = TagEdit.Artwork(big, "image/jpeg"))).isOk)
        assertEquals(before, AudioPayload.fingerprint(f))
        assertContentEquals(big, TagIO.readArtwork(f))
        // and shrinking again
        assertTrue(safe.writeTags(f, TagEdit(removeArtwork = true, title = "Без обложки")).isOk)
        assertFalse(TagIO.read(f)!!.hasArtwork)
        assertEquals(before, AudioPayload.fingerprint(f))
        assertEquals("𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁", TagIO.read(f)!!.artist)
    }

    @Test
    fun recoversInterruptedReplacement() {
        val f = fixture("sine.mp3")
        val work = File(data, "work/x.mp3").apply { parentFile.mkdirs() }
        f.copyTo(work)
        val old = File(dir, ".sine.mp3.malachite-old")
        assertTrue(f.renameTo(old)) // crash happened right after moving the original aside
        File(data, "journal").mkdirs()
        File(data, "journal/1.json").writeText(
            """{"target":"${f.path}","work":"${work.path}","old":"${old.path}","keepOld":false}""",
        )
        assertEquals(1, safe.recover())
        assertTrue(f.isFile)
    }
}
