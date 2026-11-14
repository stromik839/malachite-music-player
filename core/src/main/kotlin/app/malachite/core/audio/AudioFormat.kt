package app.malachite.core.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * The real container format of a file, detected from its bytes – never from the extension.
 * A file called "song.wav" that is actually MP3 is reported as [MP3].
 */
enum class AudioFormat(
    val extension: String,
    val mime: String,
    /** Tags (incl. embedded cover) can be written safely. */
    val taggable: Boolean,
    /** Typically used by voice notes / recorders rather than music. */
    val voiceLike: Boolean = false,
) {
    MP3("mp3", "audio/mpeg", true),
    FLAC("flac", "audio/flac", true),
    OGG_VORBIS("ogg", "audio/ogg", true),
    OPUS("opus", "audio/ogg", true),
    OGG_FLAC("oga", "audio/ogg", false),
    SPEEX("spx", "audio/ogg", false, voiceLike = true),
    M4A("m4a", "audio/mp4", true),
    WAV("wav", "audio/wav", true),
    AIFF("aiff", "audio/aiff", true),
    WMA("wma", "audio/x-ms-wma", true),
    DSF("dsf", "audio/dsf", true),
    DFF("dff", "audio/dff", false),
    APE("ape", "audio/ape", false),
    WAVPACK("wv", "audio/wavpack", false),
    AAC("aac", "audio/aac", false),
    MATROSKA("mka", "audio/x-matroska", false),
    AMR("amr", "audio/amr", false, voiceLike = true),
    THREE_GP("3gp", "audio/3gpp", false, voiceLike = true),
    MIDI("mid", "audio/midi", false, voiceLike = true),
    UNKNOWN("", "application/octet-stream", false);

    companion object {
        /** Extensions we look at when scanning the device (anything else is ignored). */
        /** Extensions that are usually video containers: checked for a video track before import. */
        val VIDEO_CAPABLE = setOf("mp4", "m4v", "3gp", "3g2", "mkv", "webm", "mov", "avi")

        val SCAN_EXTENSIONS = setOf(
            "mp3", "flac", "ogg", "oga", "opus", "m4a", "mp4", "m4b", "aac", "wav", "wave", "aif", "aiff",
            "aifc", "wma", "asf", "dsf", "dff", "ape", "wv", "mka", "webm", "weba", "amr", "3gp", "mpga", "mp2",
        )

        fun detect(file: File): AudioFormat = try {
            RandomAccessFile(file, "r").use { detect(it) }
        } catch (e: Exception) {
            UNKNOWN
        }

        fun detect(raf: RandomAccessFile): AudioFormat {
            val len = raf.length()
            if (len < 12) return UNKNOWN
            var offset = 0L
            // Skip one or more ID3v2 tags (some FLAC/AAC files carry them too).
            repeat(4) {
                val h = ByteArray(10)
                raf.seek(offset)
                if (raf.read(h) < 10) return UNKNOWN
                if (h[0] == 'I'.code.toByte() && h[1] == 'D'.code.toByte() && h[2] == '3'.code.toByte()) {
                    val size = Bytes.syncSafe(h, 6)
                    val footer = if ((h[5].toInt() and 0x10) != 0) 10 else 0
                    offset += 10 + size + footer
                } else return@repeat
            }
            val head = ByteArray(64)
            raf.seek(offset)
            val n = raf.read(head)
            if (n < 12) return UNKNOWN
            fun at(i: Int, s: String) = Bytes.startsWith(head, i, s)

            if (at(0, "fLaC")) return FLAC
            if (at(0, "OggS")) {
                // First page: 27-byte header + segment table, then the first packet.
                val segs = head[26].toInt() and 0xFF
                val p = 27 + segs
                val pkt = ByteArray(16)
                raf.seek(offset + p)
                raf.read(pkt)
                return when {
                    Bytes.startsWith(pkt, 0, "OpusHead") -> OPUS
                    pkt[0].toInt() == 1 && Bytes.startsWith(pkt, 1, "vorbis") -> OGG_VORBIS
                    pkt[0].toInt() == 0x7F && Bytes.startsWith(pkt, 1, "FLAC") -> OGG_FLAC
                    Bytes.startsWith(pkt, 0, "Speex") -> SPEEX
                    else -> UNKNOWN
                }
            }
            if (at(4, "ftyp")) {
                val brand = String(head, 8, 4, Charsets.ISO_8859_1).lowercase()
                return if (brand.startsWith("3g")) THREE_GP else M4A
            }
            if ((at(0, "RIFF") || at(0, "RF64")) && at(8, "WAVE")) return WAV
            if (at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC"))) return AIFF
            if (head[0] == 0x30.toByte() && head[1] == 0x26.toByte() && head[2] == 0xB2.toByte() && head[3] == 0x75.toByte()) return WMA
            if (at(0, "DSD ")) return DSF
            if (at(0, "FRM8")) return DFF
            if (at(0, "MAC ")) return APE
            if (at(0, "wvpk")) return WAVPACK
            if (at(0, "#!AMR")) return AMR
            if (at(0, "MThd")) return MIDI
            if (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte()) return MATROSKA
            // MPEG audio frame sync, or ADTS AAC.
            val sync = findFrameSync(raf, offset, minOf(len, offset + 64 * 1024))
            if (sync != null) return sync
            return UNKNOWN
        }

        private fun findFrameSync(raf: RandomAccessFile, from: Long, to: Long): AudioFormat? {
            val size = (to - from).toInt().coerceAtLeast(0)
            if (size < 4) return null
            val buf = ByteArray(size)
            raf.seek(from)
            val read = raf.read(buf)
            var i = 0
            while (i + 4 <= read) {
                if ((buf[i].toInt() and 0xFF) == 0xFF && (buf[i + 1].toInt() and 0xE0) == 0xE0) {
                    val layer = (buf[i + 1].toInt() shr 1) and 0x3
                    if (layer == 0 && (buf[i + 1].toInt() and 0xF6) == 0xF0) return AAC
                    val len = Mpeg.frameLength(buf, i)
                    if (len > 0) {
                        // Require a second valid frame right after the first one.
                        if (i + len + 4 <= read) {
                            if (Mpeg.frameLength(buf, i + len) > 0) return MP3
                        } else return MP3
                    }
                }
                i++
            }
            return null
        }

        fun fromExtension(ext: String): AudioFormat = when (ext.lowercase()) {
            "mp3", "mpga", "mp2" -> MP3
            "flac" -> FLAC
            "ogg" -> OGG_VORBIS
            "oga" -> OGG_FLAC
            "opus" -> OPUS
            "m4a", "mp4", "m4b" -> M4A
            "wav", "wave" -> WAV
            "aif", "aiff", "aifc" -> AIFF
            "wma", "asf" -> WMA
            "dsf" -> DSF
            "dff" -> DFF
            "ape" -> APE
            "wv" -> WAVPACK
            "aac" -> AAC
            "mka", "webm", "weba" -> MATROSKA
            "amr" -> AMR
            "3gp" -> THREE_GP
            "mid", "midi" -> MIDI
            else -> UNKNOWN
        }

        /** True if the extension is a legitimate name for the detected format. */
        fun extensionMatches(ext: String, real: AudioFormat): Boolean {
            val e = ext.lowercase()
            return when (real) {
                MP3 -> e == "mp3"
                M4A -> e == "m4a" || e == "mp4" || e == "m4b"
                WAV -> e == "wav" || e == "wave"
                AIFF -> e == "aif" || e == "aiff" || e == "aifc"
                OGG_VORBIS -> e == "ogg" || e == "oga"
                OPUS -> e == "opus" || e == "ogg"
                OGG_FLAC -> e == "oga" || e == "ogg"
                MATROSKA -> e == "mka" || e == "webm" || e == "weba"
                WMA -> e == "wma" || e == "asf"
                UNKNOWN -> true
                else -> e == real.extension
            }
        }
    }
}

internal object Bytes {
    fun startsWith(b: ByteArray, at: Int, s: String): Boolean {
        if (at + s.length > b.size) return false
        for (i in s.indices) if (b[at + i] != s[i].code.toByte()) return false
        return true
    }

    fun syncSafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    fun le64(b: ByteArray, at: Int): Long {
        var r = 0L
        for (i in 7 downTo 0) r = (r shl 8) or (b[at + i].toLong() and 0xFF)
        return r
    }
}

internal object Mpeg {
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val BITRATES_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
    private val BITRATES_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
    private val BITRATES_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val SAMPLE_RATES = arrayOf(
        intArrayOf(11025, 12000, 8000), // MPEG 2.5
        intArrayOf(0, 0, 0),
        intArrayOf(22050, 24000, 16000), // MPEG 2
        intArrayOf(44100, 48000, 32000), // MPEG 1
    )

    /** Length in bytes of the MPEG audio frame starting at [i], or -1 if the header is invalid. */
    fun frameLength(b: ByteArray, i: Int): Int {
        if (i + 4 > b.size) return -1
        val b0 = b[i].toInt() and 0xFF
        val b1 = b[i + 1].toInt() and 0xFF
        val b2 = b[i + 2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return -1
        val version = (b1 shr 3) and 0x3
        val layer = (b1 shr 1) and 0x3
        if (version == 1 || layer == 0) return -1
        val brIdx = (b2 shr 4) and 0xF
        val srIdx = (b2 shr 2) and 0x3
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return -1
        val padding = (b2 shr 1) and 0x1
        val sr = SAMPLE_RATES[version][srIdx]
        val v1 = version == 3
        val kbps = when (layer) {
            3 -> if (v1) BITRATES_V1_L1[brIdx] else BITRATES_V2_L1[brIdx]
            2 -> if (v1) BITRATES_V1_L2[brIdx] else BITRATES_V2_L23[brIdx]
            else -> if (v1) BITRATES_V1_L3[brIdx] else BITRATES_V2_L23[brIdx]
        }
        if (kbps == 0 || sr == 0) return -1
        return when (layer) {
            3 -> (12 * kbps * 1000 / sr + padding) * 4
            1 -> if (v1) 144 * kbps * 1000 / sr + padding else 72 * kbps * 1000 / sr + padding
            else -> 144 * kbps * 1000 / sr + padding
        }
    }
}
