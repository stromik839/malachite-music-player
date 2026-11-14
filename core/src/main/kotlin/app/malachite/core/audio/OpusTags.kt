package app.malachite.core.audio

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Reads and rewrites the comment header of Ogg Opus files (RFC 7845), including the
 * embedded cover (METADATA_BLOCK_PICTURE). The general tag library does not support Opus,
 * so this is done here by hand: only the comment packet is rebuilt, every audio page is
 * copied byte-for-byte (only its sequence number and checksum change).
 */
object OpusTags {

    data class Comments(
        val vendor: String,
        val fields: List<Pair<String, String>>,
        /** Binary data after the comments that must be preserved (RFC 7845 §5.2). */
        val trailing: ByteArray? = null,
    ) {
        fun get(key: String): String? = fields.firstOrNull { it.first.equals(key, true) }?.second
        fun getAll(key: String) = fields.filter { it.first.equals(key, true) }.map { it.second }

        fun with(key: String, value: String?): Comments {
            val rest = fields.filterNot { it.first.equals(key, true) }
            return copy(fields = if (value.isNullOrEmpty()) rest else rest + (key.uppercase() to value))
        }

        fun withoutPictures() = copy(fields = fields.filterNot { it.first.equals(PICTURE_KEY, true) })
    }

    const val PICTURE_KEY = "METADATA_BLOCK_PICTURE"

    class Info(val comments: Comments, val durationMs: Long, val channels: Int, val inputSampleRate: Int)

    fun read(file: File): Info? = RandomAccessFile(file, "r").use { raf ->
        var head: ByteArray? = null
        val tags = ByteArrayOutputStream()
        var packets = 0
        var lastGranule = 0L
        var serial: Long? = null
        val ok = OggPages.forEach(raf) { page ->
            if (serial == null) serial = page.serial
            if (page.serial == serial && page.granule != -1L) lastGranule = maxOf(lastGranule, page.granule)
            if (packets < 2) {
                var off = 0
                val cur = ByteArrayOutputStream()
                for (seg in page.segments) {
                    val s = seg.toInt() and 0xFF
                    if (packets == 0) cur.write(page.data, off, s) else if (packets == 1) tags.write(page.data, off, s)
                    off += s
                    if (s < 255) {
                        if (packets == 0) head = cur.toByteArray()
                        packets++
                        if (packets >= 2) break
                    }
                }
            }
            true
        }
        val h = head ?: return null
        if (!ok || packets < 2 || !Bytes.startsWith(h, 0, "OpusHead")) return null
        val comments = parseComments(tags.toByteArray()) ?: return null
        val preSkip = (h[10].toInt() and 0xFF) or ((h[11].toInt() and 0xFF) shl 8)
        val rate = Bytes.le32(h, 12).toInt()
        val samples = (lastGranule - preSkip).coerceAtLeast(0)
        Info(comments, samples * 1000 / 48000, h[9].toInt() and 0xFF, rate)
    }

    fun parseComments(p: ByteArray): Comments? {
        if (!Bytes.startsWith(p, 0, "OpusTags")) return null
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        try {
            val vlen = bb.int
            if (vlen < 0 || vlen > bb.remaining()) return null
            val vendor = String(p, bb.position(), vlen, Charsets.UTF_8)
            bb.position(bb.position() + vlen)
            val count = bb.int
            if (count < 0) return null
            val fields = ArrayList<Pair<String, String>>(count)
            repeat(count) {
                val len = bb.int
                if (len < 0 || len > bb.remaining()) return null
                val s = String(p, bb.position(), len, Charsets.UTF_8)
                bb.position(bb.position() + len)
                val eq = s.indexOf('=')
                if (eq > 0) fields += s.substring(0, eq) to s.substring(eq + 1)
            }
            val trailing = if (bb.remaining() > 0 && (p[bb.position()].toInt() and 1) == 1) {
                p.copyOfRange(bb.position(), p.size)
            } else null
            return Comments(vendor, fields, trailing)
        } catch (e: java.nio.BufferUnderflowException) {
            return null
        }
    }

    fun buildComments(c: Comments): ByteArray {
        val out = ByteArrayOutputStream()
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        out.write("OpusTags".toByteArray(Charsets.US_ASCII))
        val vendor = c.vendor.toByteArray(Charsets.UTF_8)
        le32(vendor.size); out.write(vendor)
        le32(c.fields.size)
        for ((k, v) in c.fields) {
            val b = "$k=$v".toByteArray(Charsets.UTF_8)
            le32(b.size); out.write(b)
        }
        c.trailing?.let { out.write(it) }
        return out.toByteArray()
    }

    /** FLAC-style picture block, base64-encoded for METADATA_BLOCK_PICTURE. */
    fun pictureField(image: ByteArray, mime: String, width: Int = 0, height: Int = 0): String {
        val m = mime.toByteArray(Charsets.US_ASCII)
        val bb = ByteBuffer.allocate(32 + m.size + image.size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(3) // front cover
        bb.putInt(m.size); bb.put(m)
        bb.putInt(0) // description
        bb.putInt(width); bb.putInt(height)
        bb.putInt(24); bb.putInt(0)
        bb.putInt(image.size); bb.put(image)
        return Base64.getEncoder().encodeToString(bb.array())
    }

    /** Decodes the first front cover (or any picture) from the comments. */
    fun picture(c: Comments): ByteArray? {
        val all = c.getAll(PICTURE_KEY)
        var fallback: ByteArray? = null
        for (b64 in all) {
            try {
                val raw = Base64.getMimeDecoder().decode(b64)
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
                val type = bb.int
                val ml = bb.int; bb.position(bb.position() + ml)
                val dl = bb.int; bb.position(bb.position() + dl)
                bb.position(bb.position() + 16)
                val len = bb.int
                val data = ByteArray(len); bb.get(data)
                if (type == 3) return data
                if (fallback == null) fallback = data
            } catch (e: Exception) {
                // ignore broken picture blocks
            }
        }
        return fallback
    }

    /**
     * Copies [src] to [dst] replacing the comment header with [transform]'s result.
     * Throws if the file is not a plain, spec-conforming single-stream Ogg Opus file.
     */
    fun rewrite(src: File, dst: File, transform: (Comments) -> Comments) {
        RandomAccessFile(src, "r").use { raf ->
            BufferedOutputStream(FileOutputStream(dst), 256 * 1024).use { out ->
                var stage = 0 // 0: head page, 1: collecting tags, 2: audio
                var serial = 0L
                val tags = ByteArrayOutputStream()
                var shift = 0L
                var oldTagPages = 0
                var error: String? = null
                val ok = OggPages.forEach(raf) { page ->
                    when (stage) {
                        0 -> {
                            serial = page.serial
                            if ((page.headerType and 0x02) == 0 || page.segments.size != 1 ||
                                !Bytes.startsWith(page.data, 0, "OpusHead")
                            ) {
                                error = "first page is not a lone OpusHead"; return@forEach false
                            }
                            out.write(OggPages.write(page))
                            stage = 1
                        }
                        1 -> {
                            if (page.serial != serial) { error = "multiplexed stream"; return@forEach false }
                            oldTagPages++
                            var off = 0
                            var finished = false
                            for ((idx, seg) in page.segments.withIndex()) {
                                val s = seg.toInt() and 0xFF
                                tags.write(page.data, off, s)
                                off += s
                                if (s < 255) {
                                    finished = true
                                    if (idx != page.segments.size - 1) {
                                        error = "audio shares a page with the comment header"; return@forEach false
                                    }
                                }
                            }
                            if (finished) {
                                val old = parseComments(tags.toByteArray())
                                if (old == null) { error = "broken OpusTags"; return@forEach false }
                                val packet = buildComments(transform(old))
                                val newPages = paginate(packet, serial, firstSequence = 1)
                                newPages.forEach { out.write(OggPages.write(it)) }
                                shift = (newPages.size - oldTagPages).toLong()
                                stage = 2
                            }
                        }
                        else -> {
                            if (page.serial != serial) { error = "multiplexed stream"; return@forEach false }
                            page.sequence = page.sequence + shift
                            out.write(OggPages.write(page))
                        }
                    }
                    true
                }
                if (error != null) throw IOException("Opus: $error")
                if (!ok || stage != 2) throw IOException("Opus: truncated or unsupported file")
            }
        }
    }

    private fun paginate(packet: ByteArray, serial: Long, firstSequence: Long): List<OggPages.Page> {
        // Lacing values for the whole packet.
        val lacing = ArrayList<Int>()
        var remaining = packet.size
        while (remaining >= 255) { lacing += 255; remaining -= 255 }
        lacing += remaining
        val pages = ArrayList<OggPages.Page>()
        var seg = 0
        var dataOff = 0
        var seq = firstSequence
        while (seg < lacing.size) {
            val count = minOf(255, lacing.size - seg)
            val segs = ByteArray(count) { lacing[seg + it].toByte() }
            val bytes = (0 until count).sumOf { lacing[seg + it] }
            val data = packet.copyOfRange(dataOff, dataOff + bytes)
            pages += OggPages.Page(
                headerType = if (seg == 0) 0 else 0x01,
                granule = 0,
                serial = serial,
                sequence = seq++,
                segments = segs,
                data = data,
            )
            seg += count
            dataOff += bytes
        }
        return pages
    }
}
