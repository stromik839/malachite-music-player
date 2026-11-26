package app.malachite.core

import app.malachite.core.cover.ImageFacts
import app.malachite.core.library.FolderHints
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageFactsTest {
    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun be32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    /** The head of a JPEG: (a camera's EXIF with "Make"), the frame header with the size, the start of the picture. */
    private fun jpeg(w: Int, h: Int, camera: Boolean, pad: Int = 9000): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        if (camera) {
            val tiff = ByteArrayOutputStream()
            tiff.write("II".toByteArray()); tiff.write(byteArrayOf(0x2A, 0)); tiff.write(le32(8))
            tiff.write(le16(1))
            tiff.write(le16(0x010F)); tiff.write(le16(2)); tiff.write(le32(7)); tiff.write(le32(26))
            tiff.write(le32(0))
            tiff.write("Xiaomi\u0000".toByteArray())
            val body = "Exif\u0000\u0000".toByteArray() + tiff.toByteArray()
            out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte())); out.write(be16(body.size + 2)); out.write(body)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xC0.toByte())); out.write(be16(17)); out.write(8)
        out.write(be16(h)); out.write(be16(w)); out.write(3)
        repeat(3) { out.write(byteArrayOf(1, 0x11, 0)) }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        out.write(ByteArray(pad))
        return out.toByteArray()
    }

    private fun png(w: Int, h: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) +
            be32(13) + "IHDR".toByteArray() + be32(w) + be32(h) + ByteArray(9000)

    @Test
    fun theSizeAndTheCameraAreReadFromTheHead() {
        val photo = ImageFacts.read(jpeg(4000, 3000, camera = true))!!
        assertEquals(4000, photo.width)
        assertEquals(3000, photo.height)
        assertTrue(photo.camera)
        assertFalse(photo.coverLike)
        val cover = ImageFacts.read(jpeg(600, 600, camera = false))!!
        assertTrue(cover.coverLike)
        val screenshot = ImageFacts.read(png(1080, 2400))!!
        assertFalse(screenshot.squarish)
        assertNull(ImageFacts.read("not a picture at all".toByteArray()))
    }

    private fun folder(vararg files: Pair<String, ByteArray>, under: String = "Artist/Album"): File {
        val root = Files.createTempDirectory("covers").toFile()
        val dir = File(root, under).apply { mkdirs() }
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        return dir
    }

    @Test
    fun aPhotoNextToTheSongsIsNeverTheirCover() {
        // a picture without a cover's name: no longer taken, however square it is
        assertNull(FolderHints.coverImageIn(folder("IMG_20251125_101112.jpg" to jpeg(1000, 1000, camera = false))))
        // named "cover" but out of a camera: a photo
        assertNull(FolderHints.coverImageIn(folder("cover.jpg" to jpeg(3000, 3000, camera = true))))
        // named "cover" but as tall as a photo of a page
        assertNull(FolderHints.coverImageIn(folder("cover.jpg" to jpeg(1200, 1600, camera = false))))
        // in the phone's own pictures or downloads: not looked into at all
        assertNull(FolderHints.coverImageIn(folder("cover.jpg" to jpeg(800, 800, camera = false), under = "Download/Telegram")))
        assertNull(FolderHints.coverImageIn(folder("cover.jpg" to jpeg(800, 800, camera = false), under = "DCIM/Camera")))
    }

    @Test
    fun aRealCoverStillIs() {
        val dir = folder("cover.jpg" to jpeg(800, 800, camera = false), "IMG_0001.jpg" to jpeg(3000, 3000, camera = true))
        assertEquals("cover.jpg", FolderHints.coverImageIn(dir)?.name)
        val named = folder("ВремяВыткалоНить.jpeg" to jpeg(1000, 1000, camera = false))
        assertEquals("ВремяВыткалоНить.jpeg", FolderHints.coverImageIn(named, albumName = "Время выткало нить")?.name)
        val store = folder("6439b2e9.1000x1000x1.jpg" to jpeg(1000, 1000, camera = false))
        assertEquals("6439b2e9.1000x1000x1.jpg", FolderHints.coverImageIn(store)?.name)
    }
}
