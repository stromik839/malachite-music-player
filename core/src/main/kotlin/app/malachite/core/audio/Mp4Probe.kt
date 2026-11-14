package app.malachite.core.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Tells real videos (Telegram clips, screen recordings…) apart from audio-only MP4/M4A files.
 * Both use the same container, so the extension and the magic bytes are not enough: we look for
 * a video track ('vide' handler) with more than a couple of frames. A single-frame video track
 * is just embedded cover art, not a video.
 */
object Mp4Probe {

    /** Number of frames of the longest video track, 0 if there is none, -1 if not an MP4 file. */
    fun videoFrames(file: File): Long = try {
        RandomAccessFile(file, "r").use { raf -> scan(raf) }
    } catch (e: Exception) {
        -1
    }

    fun isVideo(file: File): Boolean = videoFrames(file) > 2

    /** What [videoFrames] answers for a video whose frames cannot be counted. */
    private const val FRAGMENTED_VIDEO = 1000L

    private class Box(val type: String, val start: Long, val end: Long, val headerSize: Int) {
        val payload get() = start + headerSize
    }

    private fun boxes(raf: RandomAccessFile, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        val h = ByteArray(16)
        while (pos + 8 <= to) {
            raf.seek(pos)
            raf.readFully(h, 0, 8)
            var size = Bytes.be32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                raf.readFully(h, 8, 8)
                size = 0
                for (k in 8 until 16) size = (size shl 8) or (h[k].toLong() and 0xFF)
                header = 16
            } else if (size == 0L) size = to - pos
            if (size < header || pos + size > to + 8) break
            out += Box(type, pos, minOf(pos + size, to), header)
            pos += size
        }
        return out
    }

    private fun child(raf: RandomAccessFile, parent: Box, type: String) = boxes(raf, parent.payload, parent.end).firstOrNull { it.type == type }

    private fun scan(raf: RandomAccessFile): Long {
        val top = boxes(raf, 0, raf.length())
        if (top.firstOrNull()?.type != "ftyp") return -1
        val moov = top.firstOrNull { it.type == "moov" } ?: return 0
        val inMoov = boxes(raf, moov.payload, moov.end)
        // A "fragmented" file (screen recordings, clips from messengers and bots) keeps its frames
        // in later parts of the file: the table inside "moov" is empty, so it cannot be counted.
        val fragmented = inMoov.any { it.type == "mvex" }
        var maxFrames = 0L
        for (trak in inMoov.filter { it.type == "trak" }) {
            val mdia = child(raf, trak, "mdia") ?: continue
            val hdlr = child(raf, mdia, "hdlr") ?: continue
            val handler = ByteArray(4)
            raf.seek(hdlr.payload + 8) // version/flags (4) + pre_defined (4)
            raf.readFully(handler)
            if (String(handler, Charsets.ISO_8859_1) != "vide") continue
            val stsz = child(raf, mdia, "minf")?.let { child(raf, it, "stbl") }?.let { child(raf, it, "stsz") }
            val frames = if (stsz != null) {
                val b = ByteArray(12)
                raf.seek(stsz.payload)
                raf.readFully(b)
                Bytes.be32(b, 8) // version/flags, sample_size, sample_count
            } else 2
            // a video track of a fragmented file is a real video: cover art is never stored that way
            if (fragmented && frames <= 2) return FRAGMENTED_VIDEO
            maxFrames = maxOf(maxFrames, frames)
        }
        return maxFrames
    }
}
