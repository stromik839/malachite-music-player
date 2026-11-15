package app.malachite.core.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The start of a FLAC file – "fLaC" and the metadata blocks – for frames that come out of a phone's
 * encoder: with the encoder's own blocks when it gives them, with blocks made here when it does not
 * (the encoder of Android 8 drops them).
 *
 * Android 8 and 9 recognise a FLAC file only when its first eight bytes are "fLaC", 0, 0, 0, 34,
 * that is when STREAMINFO is *not* the last metadata block. So a padding block always closes the
 * header made here; the padding also leaves room for the tags written into the file afterwards.
 */
object FlacHeader {

    private val MAGIC = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // "fLaC"
    private const val STREAMINFO = 0
    private const val PADDING = 1
    private const val STREAMINFO_SIZE = 34

    /** Room left for tags (the reference encoder leaves the same). */
    const val PADDING_SIZE = 8192

    /** Where the total sample count sits, from the start of the file: patched in when the stream ends. */
    const val SAMPLES_AT = 4 + 4 + 13

    /** What the first frame tells about the stream. */
    data class FirstFrame(val blockSize: Int, val fixed: Boolean)

    /** A header built from the encoder's: [header] to write, [used] bytes of the encoder's data taken by it. */
    class Encoded(val header: ByteArray, val used: Int)

    fun startsWithMagic(b: ByteArray): Boolean = b.size >= 4 && b.copyOfRange(0, 4).contentEquals(MAGIC)

    /**
     * Reads the frame header at the start of [frame]: its block size, and whether the stream counts
     * frames (a fixed block size) or samples. Null when no frame header is there.
     */
    fun firstFrame(frame: ByteArray): FirstFrame? {
        if (frame.size < 6) return null
        fun at(i: Int) = frame[i].toInt() and 0xFF
        if (at(0) != 0xFF || (at(1) and 0xFE) != 0xF8) return null
        val fixed = at(1) and 1 == 0
        val sizeKey = at(2) shr 4
        if (sizeKey == 0 || (at(2) and 0x0F) == 0x0F || (at(3) and 1) != 0) return null
        // the frame (or sample) number, coded like UTF-8 in one to seven bytes
        val lead = at(4)
        val numberBytes = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            lead and 0xFC == 0xF8 -> 5
            lead and 0xFE == 0xFC -> 6
            lead == 0xFE -> 7
            else -> return null
        }
        val tail = 4 + numberBytes // an uncommon block size is written after the number
        val blockSize = when (sizeKey) {
            1 -> 192
            in 2..5 -> 576 shl (sizeKey - 2)
            6 -> if (frame.size > tail) at(tail) + 1 else return null
            7 -> if (frame.size > tail + 1) ((at(tail) shl 8) or at(tail + 1)) + 1 else return null
            else -> 256 shl (sizeKey - 8)
        }
        return FirstFrame(blockSize, fixed)
    }

    /**
     * A header made here: STREAMINFO with the rate, the channels, the bits per sample, the number of
     * samples and the block sizes (frame sizes left open, no checksum), then the padding.
     */
    fun own(rate: Int, channels: Int, bits: Int, samples: Long, minBlock: Int, maxBlock: Int): ByteArray {
        require(rate in 1..655_350 && channels in 1..8 && bits in 4..32)
        val h = ByteBuffer.allocate(4 + 4 + STREAMINFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        h.put(MAGIC)
        h.put(blockHeader(STREAMINFO, last = false, STREAMINFO_SIZE))
        h.putShort(minBlock.coerceIn(16, 65535).toShort())
        h.putShort(maxBlock.coerceIn(16, 65535).toShort())
        h.put(ByteArray(6)) // frame sizes: unknown
        h.putLong((rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or ((bits - 1).toLong() shl 36) or (samples and 0xF_FFFF_FFFFL))
        h.put(ByteArray(16)) // no MD5
        return h.array() + padding()
    }

    /** The header for a stream the encoder gave no header for, from what its [first] frame says. */
    fun forFrames(rate: Int, channels: Int, bits: Int, samples: Long, first: FirstFrame): ByteArray =
        if (first.fixed) own(rate, channels, bits, samples, first.blockSize, first.blockSize)
        else own(rate, channels, bits, samples, 16, 65535) // the frames count samples themselves

    /**
     * The encoder's own header ([data], with or without "fLaC" in front, maybe followed by the first
     * frames): its blocks as they are (its padding left out), then the padding made here as the last
     * block. Null when the header does not start with STREAMINFO or is cut short.
     */
    fun fromEncoder(data: ByteArray): Encoded? {
        val skip = if (startsWithMagic(data)) 4 else 0
        val blocks = data.copyOfRange(skip, data.size)
        if (blocks.size < 4 + STREAMINFO_SIZE || (blocks[0].toInt() and 0x7F) != STREAMINFO) return null
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        var at = 0
        while (at + 4 <= blocks.size) {
            val type = blocks[at].toInt() and 0x7F
            if (type == 127) break // the frames (0xFF…) right after blocks none of which was marked last
            if (type == STREAMINFO && at > 0) return null // only one, and only first
            val len = ((blocks[at + 1].toInt() and 0xFF) shl 16) or ((blocks[at + 2].toInt() and 0xFF) shl 8) or (blocks[at + 3].toInt() and 0xFF)
            if (at + 4 + len > blocks.size) return null
            if (type != PADDING) {
                out.write(type) // never the last: the padding closes the header
                out.write(blocks, at + 1, 3 + len)
            }
            val last = blocks[at].toInt() and 0x80 != 0
            at += 4 + len
            if (last) break
        }
        out.write(padding())
        return Encoded(out.toByteArray(), skip + at)
    }

    /** Do these first bytes of a file open on old Android versions: "fLaC", 0, 0, 0, 34? */
    fun opensOnOldAndroid(start: ByteArray): Boolean =
        startsWithMagic(start) && start.size >= 8 &&
            start[4].toInt() == 0 && start[5].toInt() == 0 && start[6].toInt() == 0 && start[7].toInt() == STREAMINFO_SIZE

    private fun padding(): ByteArray = blockHeader(PADDING, last = true, PADDING_SIZE) + ByteArray(PADDING_SIZE)

    private fun blockHeader(type: Int, last: Boolean, size: Int) = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(),
        (size shr 16).toByte(),
        (size shr 8).toByte(),
        size.toByte(),
    )
}
