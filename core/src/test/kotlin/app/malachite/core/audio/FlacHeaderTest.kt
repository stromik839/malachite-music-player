package app.malachite.core.audio

import app.malachite.core.fs.SafeFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlacHeaderTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("malachite-flac").toFile()
        TagIO.configure(android = false)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private class Info(val minBlock: Int, val maxBlock: Int, val rate: Int, val channels: Int, val bits: Int, val samples: Long)

    /** STREAMINFO read back from the start of a file. */
    private fun info(file: ByteArray): Info {
        val si = file.copyOfRange(8, 8 + 34)
        fun u(i: Int) = si[i].toLong() and 0xFF
        var v = 0L
        for (i in 10 until 18) v = (v shl 8) or u(i)
        return Info(
            ((u(0) shl 8) or u(1)).toInt(), ((u(2) shl 8) or u(3)).toInt(),
            (v ushr 44).toInt(), ((v ushr 41) and 7).toInt() + 1, ((v ushr 36) and 31).toInt() + 1, v and 0xF_FFFF_FFFFL,
        )
    }

    /** The metadata blocks of a file: (type, last, length). */
    private fun blocks(file: ByteArray): List<Triple<Int, Boolean, Int>> {
        val out = mutableListOf<Triple<Int, Boolean, Int>>()
        var at = 4
        while (true) {
            val h = file[at].toInt() and 0xFF
            val len = ((file[at + 1].toInt() and 0xFF) shl 16) or ((file[at + 2].toInt() and 0xFF) shl 8) or (file[at + 3].toInt() and 0xFF)
            out += Triple(h and 0x7F, h and 0x80 != 0, len)
            at += 4 + len
            if (h and 0x80 != 0) return out
        }
    }

    private fun framesStart(file: ByteArray): Int = 4 + blocks(file).sumOf { 4 + it.third }

    private val fixture: ByteArray get() = javaClass.getResourceAsStream("/audio/sine.flac")!!.readBytes()

    @Test
    fun ownHeaderOpensOnOldAndroid() {
        val h = FlacHeader.own(44_100, 2, 16, 10_000_000L, 4096, 4096)
        // Android 8 and 9 compare the first eight bytes with exactly this
        assertContentEquals(bytes(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 34), h.copyOfRange(0, 8))
        assertTrue(FlacHeader.opensOnOldAndroid(h))
        val i = info(h)
        assertEquals(4096, i.minBlock)
        assertEquals(4096, i.maxBlock)
        assertEquals(44_100, i.rate)
        assertEquals(2, i.channels)
        assertEquals(16, i.bits)
        assertEquals(10_000_000L, i.samples)
        assertEquals(listOf(Triple(0, false, 34), Triple(1, true, FlacHeader.PADDING_SIZE)), blocks(h))
        assertEquals(h.size, framesStart(h))
    }

    @Test
    fun readsTheFirstFrame() {
        // fixed 4096 (key 12), 44.1 kHz, stereo, 16 bit, frame 0
        assertEquals(FlacHeader.FirstFrame(4096, true), FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0xC9, 0x18, 0x00, 0x00)))
        // 576 (key 2), 1152 (key 3), 4608 (key 5), 192 (key 1)
        assertEquals(576, FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x24, 0x08, 0x00, 0xCA))!!.blockSize)
        assertEquals(1152, FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x39, 0x18, 0x00, 0x00))!!.blockSize)
        assertEquals(4608, FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x59, 0x18, 0x00, 0x00))!!.blockSize)
        assertEquals(192, FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x19, 0x18, 0x00, 0x00))!!.blockSize)
        // uncommon sizes after the number: 8 bit (key 6) and 16 bit (key 7), the number taking two bytes
        assertEquals(100, FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x69, 0x18, 0x00, 99, 0x00))!!.blockSize)
        assertEquals(3000, FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x79, 0x18, 0xC2, 0x80, 0x0B, 0xB7, 0x00))!!.blockSize)
        // a stream that counts samples
        assertEquals(FlacHeader.FirstFrame(4096, false), FlacHeader.firstFrame(bytes(0xFF, 0xF9, 0xC9, 0x18, 0x00, 0x00)))
        // not a frame
        assertNull(FlacHeader.firstFrame(bytes(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 34)))
        assertNull(FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x09, 0x18, 0x00, 0x00))) // block size key 0
        assertNull(FlacHeader.firstFrame(bytes(0xFF, 0xF8, 0x69, 0x18, 0x00))) // cut short
        // the real file's first frame
        val f = fixture
        assertEquals(FlacHeader.FirstFrame(576, true), FlacHeader.firstFrame(f.copyOfRange(framesStart(f), f.size)))
    }

    @Test
    fun encoderHeaderIsClosedByPadding() {
        val streamInfo = FlacHeader.own(48_000, 2, 16, 0L, 4096, 4096).copyOfRange(8, 8 + 34)
        val vendor = "reference libFLAC".toByteArray()
        val comment = bytes(vendor.size, 0, 0, 0) + vendor + bytes(0, 0, 0, 0)
        // as libFLAC writes it: STREAMINFO, then VORBIS_COMMENT marked last
        val libflac = bytes(0x66, 0x4C, 0x61, 0x43, 0x00, 0, 0, 34) + streamInfo + bytes(0x84, 0, 0, comment.size) + comment
        val e = assertNotNull(FlacHeader.fromEncoder(libflac))
        assertEquals(libflac.size, e.used)
        assertTrue(FlacHeader.opensOnOldAndroid(e.header))
        assertEquals(listOf(Triple(0, false, 34), Triple(4, false, comment.size), Triple(1, true, FlacHeader.PADDING_SIZE)), blocks(e.header))
        assertContentEquals(comment, e.header.copyOfRange(8 + 34 + 4, 8 + 34 + 4 + comment.size))

        // only STREAMINFO, marked last and without "fLaC" (the way some encoders hand it over)
        val bare = assertNotNull(FlacHeader.fromEncoder(bytes(0x80, 0, 0, 34) + streamInfo))
        assertTrue(FlacHeader.opensOnOldAndroid(bare.header))
        assertEquals(listOf(Triple(0, false, 34), Triple(1, true, FlacHeader.PADDING_SIZE)), blocks(bare.header))
        assertEquals(48_000, info(bare.header).rate)

        // the header in front of the first frames: the frames stay outside it
        val frame = bytes(0xFF, 0xF8, 0xC9, 0x18, 0x00, 0x00, 1, 2, 3)
        val joined = assertNotNull(FlacHeader.fromEncoder(libflac + frame))
        assertEquals(libflac.size, joined.used)
        // blocks none of which is marked last, then the frames
        val unmarked = bytes(0x66, 0x4C, 0x61, 0x43, 0x00, 0, 0, 34) + streamInfo
        assertEquals(unmarked.size, assertNotNull(FlacHeader.fromEncoder(unmarked + frame)).used)

        // the encoder's padding is left out, its other blocks kept
        val padded = bytes(0x00, 0, 0, 34) + streamInfo + bytes(0x01, 0, 0, 4, 0, 0, 0, 0) + bytes(0x84, 0, 0, comment.size) + comment
        assertEquals(listOf(Triple(0, false, 34), Triple(4, false, comment.size), Triple(1, true, FlacHeader.PADDING_SIZE)), blocks(FlacHeader.fromEncoder(padded)!!.header))

        // not a header: no STREAMINFO first, cut short, a second STREAMINFO
        assertNull(FlacHeader.fromEncoder(bytes(0x84, 0, 0, comment.size) + comment + ByteArray(40)))
        assertNull(FlacHeader.fromEncoder(bytes(0x00, 0, 0, 34) + streamInfo.copyOfRange(0, 20)))
        assertNull(FlacHeader.fromEncoder(bytes(0x00, 0, 0, 34) + streamInfo + bytes(0x80, 0, 0, 34) + streamInfo))
        assertNull(FlacHeader.fromEncoder(frame))
    }

    /** Frames of a real file under a header made here: read, tagged, still opening on old Android. */
    @Test
    fun ownHeaderOverRealFramesReadsAndTakesTags() {
        val original = fixture
        val frames = original.copyOfRange(framesStart(original), original.size)
        val first = assertNotNull(FlacHeader.firstFrame(frames))
        val made = File(dir, "made.flac")
        made.writeBytes(FlacHeader.forFrames(8000, 1, 16, 16_000L, first) + frames)
        assertEquals(576, info(made.readBytes()).maxBlock)

        val src = File(dir, "original.flac").also { it.writeBytes(original) }
        assertEquals(AudioFormat.FLAC, AudioFormat.detect(made))
        assertEquals(AudioPayload.fingerprint(src), AudioPayload.fingerprint(made), "the sound is the same")
        val t = assertNotNull(TagIO.read(made))
        assertTrue(t.durationMs in 1900..2100, "duration ${t.durationMs}")

        val r = SafeFiles(File(dir, ".malachite")).writeTags(made, TagEdit(title = "Перевод", artist = "Исполнитель"), dir)
        assertTrue(r.isOk, "$r")
        assertEquals("Перевод", TagIO.read(made)!!.title)
        assertEquals(AudioPayload.fingerprint(src), AudioPayload.fingerprint(made), "tags did not touch the sound")
        assertTrue(FlacHeader.opensOnOldAndroid(made.readBytes().copyOfRange(0, 8)), "STREAMINFO is still not the last block")
    }

    /** The encoder's blocks of a real file, closed here: the file reads the same. */
    @Test
    fun encoderHeaderOverRealFrames() {
        val original = fixture
        val start = framesStart(original)
        val e = assertNotNull(FlacHeader.fromEncoder(original))
        assertEquals(start, e.used)
        val made = File(dir, "rebuilt.flac")
        made.writeBytes(e.header + original.copyOfRange(e.used, original.size))
        val src = File(dir, "original.flac").also { it.writeBytes(original) }
        assertEquals(AudioPayload.fingerprint(src), AudioPayload.fingerprint(made))
        assertEquals(TagIO.read(src)!!.durationMs, TagIO.read(made)!!.durationMs)
        assertTrue(FlacHeader.opensOnOldAndroid(made.readBytes().copyOfRange(0, 8)))
    }
}
