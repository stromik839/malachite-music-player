package app.malachite.core.cover

import java.io.File

/**
 * What a picture says about itself, read from its first bytes without decoding it: its size, and
 * whether a camera made it (the "Make" / "Model" marks a phone or a camera writes into a photo).
 * An album cover is square-ish and has no such marks; a photo of a person or of a document is
 * neither – such a picture is never taken for a cover, whatever it is called.
 */
object ImageFacts {
    data class Facts(val width: Int, val height: Int, val camera: Boolean) {
        /** Square or nearly so, as covers are (a scanned booklet is a little off; photos and screenshots are far off). */
        val squarish: Boolean get() = width > 0 && height > 0 && maxOf(width, height) <= minOf(width, height) * MAX_SIDE_RATIO

        /** A picture that may stand for a cover: square-ish and not out of a camera. */
        val coverLike: Boolean get() = squarish && !camera
    }

    /** How much longer one side of a cover may be than the other. */
    const val MAX_SIDE_RATIO = 1.25

    /** The marks of a camera are in the first 64 KB; the size of a JPEG comes soon after them. */
    private const val HEAD = 512 * 1024

    fun read(file: File): Facts? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(minOf(HEAD.toLong(), file.length()).toInt().coerceAtLeast(0))
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            read(if (n == buf.size) buf else buf.copyOf(n))
        }
    }.getOrNull()

    fun read(b: ByteArray): Facts? = runCatching {
        when {
            b.size >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8 -> jpeg(b)
            b.size >= 24 && u8(b, 0) == 0x89 && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() ->
                Facts(be32(b, 16), be32(b, 20), camera = false)
            b.size >= 30 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP" -> webp(b)
            b.size >= 10 && ascii(b, 0, 4) == "GIF8" -> Facts(le16(b, 6), le16(b, 8), camera = false)
            b.size >= 26 && ascii(b, 0, 2) == "BM" -> Facts(le32(b, 18), kotlin.math.abs(le32(b, 22)), camera = false)
            else -> null
        }
    }.getOrNull()

    private fun jpeg(b: ByteArray): Facts? {
        var i = 2
        var camera = false
        while (i + 4 <= b.size) {
            if (u8(b, i) != 0xFF) { i++; continue }
            val marker = u8(b, i + 1)
            when {
                marker == 0xFF -> { i++; continue } // fill byte
                marker == 0x01 || marker in 0xD0..0xD8 -> { i += 2; continue } // no length
                marker == 0xD9 || marker == 0xDA -> return null // the picture itself starts: no size before it
            }
            val len = be16(b, i + 2)
            if (len < 2) return null
            val seg = i + 4
            val end = minOf(b.size, i + 2 + len)
            if (marker == 0xE1 && seg + 6 <= end && ascii(b, seg, 4) == "Exif") camera = camera || exifHasCamera(b, seg + 6, end)
            // the frame header (any kind of JPEG); C4, C8 and CC are other things
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return if (seg + 5 <= b.size) Facts(be16(b, seg + 3), be16(b, seg + 1), camera) else null
            }
            i += 2 + len
        }
        return null
    }

    /** The first directory of the EXIF block holds "Make" (0x010F) and "Model" (0x0110) when a camera wrote it. */
    private fun exifHasCamera(b: ByteArray, tiff: Int, end: Int): Boolean {
        if (tiff + 8 > end) return false
        val little = b[tiff] == 'I'.code.toByte()
        fun s(p: Int) = if (little) le16(b, p) else be16(b, p)
        fun l(p: Int): Long = if (little) le32(b, p).toLong() and 0xFFFFFFFFL else be32(b, p).toLong() and 0xFFFFFFFFL
        val offset = l(tiff + 4)
        if (offset < 8 || tiff + offset + 2 > end) return false
        val dir = tiff + offset.toInt()
        val count = s(dir)
        for (k in 0 until count) {
            val e = dir + 2 + k * 12
            if (e + 12 > end) break
            val tag = s(e)
            if (tag == 0x010F || tag == 0x0110) return true
        }
        return false
    }

    private fun webp(b: ByteArray): Facts? = when (ascii(b, 12, 4)) {
        "VP8X" -> Facts(1 + le24(b, 24), 1 + le24(b, 27), camera = false)
        "VP8 " -> if (u8(b, 23) == 0x9D && u8(b, 24) == 0x01 && u8(b, 25) == 0x2A) Facts(le16(b, 26) and 0x3FFF, le16(b, 28) and 0x3FFF, camera = false) else null
        "VP8L" -> {
            val bits = le32(b, 21)
            Facts(1 + (bits and 0x3FFF), 1 + ((bits ushr 14) and 0x3FFF), camera = false)
        }
        else -> null
    }

    private fun u8(b: ByteArray, p: Int) = b[p].toInt() and 0xFF
    private fun be16(b: ByteArray, p: Int) = (u8(b, p) shl 8) or u8(b, p + 1)
    private fun le16(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8)
    private fun le24(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8) or (u8(b, p + 2) shl 16)
    private fun be32(b: ByteArray, p: Int) = (u8(b, p) shl 24) or (u8(b, p + 1) shl 16) or (u8(b, p + 2) shl 8) or u8(b, p + 3)
    private fun le32(b: ByteArray, p: Int) = u8(b, p) or (u8(b, p + 1) shl 8) or (u8(b, p + 2) shl 16) or (u8(b, p + 3) shl 24)
    private fun ascii(b: ByteArray, p: Int, n: Int) = if (p + n <= b.size) String(b, p, n, Charsets.ISO_8859_1) else ""
}
