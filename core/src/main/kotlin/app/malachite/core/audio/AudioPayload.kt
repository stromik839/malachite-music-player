package app.malachite.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Fingerprint of the *sound* part of a file, ignoring all metadata (tags, covers, lyrics).
 *
 * After any tag/cover change the fingerprint of the new file must be identical to the
 * fingerprint of the original. This is the proof that only metadata was touched and the
 * music itself is bit-for-bit the same. If the proof fails, the change is thrown away.
 */
object AudioPayload {

    /** SHA-256 of the audio data, or null if the format has no reliable payload parser. */
    fun fingerprint(file: File, format: AudioFormat = AudioFormat.detect(file)): String? =
        RandomAccessFile(file, "r").use { raf ->
            when (format) {
                AudioFormat.MP3 -> mp3(raf)
                AudioFormat.FLAC -> flac(raf)
                AudioFormat.OGG_VORBIS -> ogg(raf, headerPackets = 3)
                AudioFormat.OPUS -> ogg(raf, headerPackets = 2)
                AudioFormat.M4A -> mp4(raf)
                AudioFormat.WAV -> riffChunk(raf, "data", littleEndian = true, headerSize = 12)
                AudioFormat.AIFF -> riffChunk(raf, "SSND", littleEndian = false, headerSize = 12)
                else -> null
            }
        }

    private fun digestRange(raf: RandomAccessFile, from: Long, to: Long, md: MessageDigest = sha()): MessageDigest {
        val buf = ByteArray(64 * 1024)
        var pos = from
        raf.seek(from)
        while (pos < to) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), to - pos).toInt())
            if (n <= 0) break
            md.update(buf, 0, n)
            pos += n
        }
        return md
    }

    private fun sha() = MessageDigest.getInstance("SHA-256")
    private fun hex(md: MessageDigest) = md.digest().joinToString("") { "%02x".format(it) }

    private fun skipId3v2(raf: RandomAccessFile): Long {
        var offset = 0L
        val h = ByteArray(10)
        while (true) {
            raf.seek(offset)
            if (raf.read(h) < 10) return offset
            if (Bytes.startsWith(h, 0, "ID3")) {
                val footer = if ((h[5].toInt() and 0x10) != 0) 10 else 0
                offset += 10 + Bytes.syncSafe(h, 6) + footer
            } else return offset
        }
    }

    // ------------------------------------------------------------------ MP3

    private fun mp3(raf: RandomAccessFile): String? {
        val len = raf.length()
        val afterId3 = skipId3v2(raf)
        // First position with two consecutive valid MPEG frames.
        val scan = ByteArray(minOf(len - afterId3, 512L * 1024).toInt().coerceAtLeast(0))
        raf.seek(afterId3)
        val read = raf.read(scan)
        var start = -1L
        var i = 0
        while (i + 4 <= read) {
            val fl = Mpeg.frameLength(scan, i)
            if (fl > 0 && (i + fl + 4 > read || Mpeg.frameLength(scan, i + fl) > 0)) {
                start = afterId3 + i; break
            }
            i++
        }
        if (start < 0) return null
        var end = len
        // ID3v1 at the very end
        if (end - 128 > start) {
            val t = ByteArray(3)
            raf.seek(end - 128); raf.read(t)
            if (Bytes.startsWith(t, 0, "TAG")) end -= 128
        }
        // APEv2 / Lyrics3 footers
        end = stripApeFooter(raf, start, end)
        end = stripLyrics3(raf, start, end)
        return hex(digestRange(raf, start, end))
    }

    private fun stripApeFooter(raf: RandomAccessFile, start: Long, end: Long): Long {
        if (end - 32 <= start) return end
        val f = ByteArray(32)
        raf.seek(end - 32); raf.read(f)
        if (!Bytes.startsWith(f, 0, "APETAGEX")) return end
        val size = Bytes.le32(f, 12) // includes footer, excludes header
        val flags = Bytes.le32(f, 20)
        val hasHeader = (flags and 0x80000000L) != 0L
        val total = size + if (hasHeader) 32 else 0
        return if (end - total > start) end - total else end
    }

    private fun stripLyrics3(raf: RandomAccessFile, start: Long, end: Long): Long {
        if (end - 15 <= start) return end
        val f = ByteArray(15)
        raf.seek(end - 15); raf.read(f)
        if (!Bytes.startsWith(f, 6, "LYRICS200")) return end
        val size = String(f, 0, 6, Charsets.ISO_8859_1).toLongOrNull() ?: return end
        val total = size + 15
        return if (end - total > start) end - total else end
    }

    // ------------------------------------------------------------------ FLAC

    private fun flac(raf: RandomAccessFile): String? {
        var pos = skipId3v2(raf)
        val magic = ByteArray(4)
        raf.seek(pos); raf.read(magic)
        if (!Bytes.startsWith(magic, 0, "fLaC")) return null
        pos += 4
        val h = ByteArray(4)
        while (true) {
            raf.seek(pos)
            if (raf.read(h) < 4) return null
            val last = (h[0].toInt() and 0x80) != 0
            val size = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            pos += 4 + size
            if (last) break
        }
        var end = raf.length()
        if (end - 128 > pos) {
            val t = ByteArray(3)
            raf.seek(end - 128); raf.read(t)
            if (Bytes.startsWith(t, 0, "TAG")) end -= 128
        }
        return hex(digestRange(raf, pos, end))
    }

    // ------------------------------------------------------------------ OGG

    private fun ogg(raf: RandomAccessFile, headerPackets: Int): String? {
        val md = sha()
        var packetsDone = 0
        var serial: Long? = null
        var ok = true
        val complete = OggPages.forEach(raf) { page ->
            if (serial == null) serial = page.serial
            if (page.serial != serial) { ok = false; return@forEach false } // multiplexed streams unsupported
            var off = 0
            for (seg in page.segments) {
                val s = seg.toInt() and 0xFF
                if (packetsDone >= headerPackets) md.update(page.data, off, s)
                off += s
                if (s < 255) packetsDone++
            }
            if (packetsDone > headerPackets) {
                // granule positions are part of the timing of the audio
                md.update(java.nio.ByteBuffer.allocate(8).putLong(page.granule).array())
            }
            true
        }
        return if (ok && complete && serial != null) hex(md) else null
    }

    // ------------------------------------------------------------------ MP4

    private fun mp4(raf: RandomAccessFile): String? {
        val md = sha()
        var pos = skipId3v2(raf) // an ID3 tag glued in front of some M4A files is not audio
        val len = raf.length()
        var found = false
        val h = ByteArray(16)
        while (pos + 8 <= len) {
            raf.seek(pos)
            raf.read(h, 0, 8)
            var size = Bytes.be32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L) {
                raf.read(h, 8, 8)
                size = 0
                for (k in 8 until 16) size = (size shl 8) or (h[k].toLong() and 0xFF)
                headerLen = 16
            } else if (size == 0L) size = len - pos
            if (size < headerLen) return null
            if (type == "mdat") {
                digestRange(raf, pos + headerLen, minOf(len, pos + size), md)
                found = true
            }
            pos += size
        }
        return if (found) hex(md) else null
    }

    // ------------------------------------------------------------------ RIFF / IFF

    private fun riffChunk(raf: RandomAccessFile, id: String, littleEndian: Boolean, headerSize: Int): String? {
        val len = raf.length()
        var pos = headerSize.toLong()
        val h = ByteArray(8)
        while (pos + 8 <= len) {
            raf.seek(pos)
            if (raf.read(h) < 8) return null
            val cid = String(h, 0, 4, Charsets.ISO_8859_1)
            val size = if (littleEndian) Bytes.le32(h, 4) else Bytes.be32(h, 4)
            if (cid == id) {
                return hex(digestRange(raf, pos + 8, minOf(len, pos + 8 + size)))
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }
}

/** Minimal Ogg page reader/writer shared by the payload check and the Opus tag writer. */
internal object OggPages {

    class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Long,
        var sequence: Long,
        val segments: ByteArray,
        val data: ByteArray,
        val rawOffset: Long = -1,
    )

    /** Reads every page; null if the stream is truncated / not Ogg. */
    fun read(raf: RandomAccessFile): List<Page>? {
        val pages = ArrayList<Page>()
        return if (forEach(raf) { pages += it; true } && pages.isNotEmpty()) pages else null
    }

    /**
     * Streams pages to [action] (return false to stop). Returns false if the file is
     * truncated or not an Ogg stream. Trailing non-Ogg bytes after at least one page
     * (e.g. an ID3v1 tag appended by a bad tagger) are tolerated.
     */
    inline fun forEach(raf: RandomAccessFile, action: (Page) -> Boolean): Boolean {
        val len = raf.length()
        var pos = 0L
        var count = 0
        val h = ByteArray(27)
        while (pos + 27 <= len) {
            raf.seek(pos)
            raf.readFully(h)
            if (!Bytes.startsWith(h, 0, "OggS")) return count > 0
            val nseg = h[26].toInt() and 0xFF
            val segs = ByteArray(nseg)
            raf.readFully(segs)
            var dataLen = 0
            for (s in segs) dataLen += s.toInt() and 0xFF
            if (pos + 27 + nseg + dataLen > len) return false
            val data = ByteArray(dataLen)
            raf.readFully(data)
            val page = Page(
                headerType = h[5].toInt() and 0xFF,
                granule = Bytes.le64(h, 6),
                serial = Bytes.le32(h, 14),
                sequence = Bytes.le32(h, 18),
                segments = segs,
                data = data,
                rawOffset = pos,
            )
            count++
            if (!action(page)) return true
            pos += 27 + nseg + dataLen
        }
        return count > 0
    }

    fun write(page: Page): ByteArray {
        val out = ByteArray(27 + page.segments.size + page.data.size)
        "OggS".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        out[4] = 0
        out[5] = page.headerType.toByte()
        for (i in 0 until 8) out[6 + i] = (page.granule ushr (8 * i)).toByte()
        for (i in 0 until 4) out[14 + i] = (page.serial ushr (8 * i)).toByte()
        for (i in 0 until 4) out[18 + i] = (page.sequence ushr (8 * i)).toByte()
        // CRC (bytes 22..25) computed with the field zeroed
        out[26] = page.segments.size.toByte()
        System.arraycopy(page.segments, 0, out, 27, page.segments.size)
        System.arraycopy(page.data, 0, out, 27 + page.segments.size, page.data.size)
        val crc = crc(out)
        for (i in 0 until 4) out[22 + i] = (crc ushr (8 * i)).toByte()
        return out
    }

    private val CRC_TABLE = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            t[i] = r
        }
    }

    fun crc(b: ByteArray): Int {
        var c = 0
        for (x in b) c = (c shl 8) xor CRC_TABLE[((c ushr 24) and 0xFF) xor (x.toInt() and 0xFF)]
        return c
    }
}
