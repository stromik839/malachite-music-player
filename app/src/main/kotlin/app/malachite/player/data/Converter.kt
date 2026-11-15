package app.malachite.player.data

import app.malachite.core.i18n.tr
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a song into another format with the phone's own decoders and encoders (no extra
 * libraries): the sound is decoded to plain samples and encoded again. Android has no MP3
 * encoder, so the choice is M4A (AAC), FLAC (when the phone has its encoder) and WAV.
 */
object Converter {
    enum class Target(val extension: String, val mime: String?) {
        M4A("m4a", MediaFormat.MIMETYPE_AUDIO_AAC),
        FLAC("flac", MediaFormat.MIMETYPE_AUDIO_FLAC),
        WAV("wav", null),
    }

    fun title(t: Target): String = when (t) {
        Target.M4A -> tr("M4A (AAC) — маленький файл, играет везде")
        Target.FLAC -> tr("FLAC — без потерь")
        Target.WAV -> tr("WAV — без потерь, очень большой файл")
    }

    /** The formats this phone can write. */
    fun available(): List<Target> = Target.entries.filter { it.mime == null || hasEncoder(it.mime) }

    private fun hasEncoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    /** Something went wrong; the message says what, for a person. */
    class Failed(message: String) : Exception(message)

    /** Length of the sound in [file] (µs), 0 when Android does not say. */
    fun durationUs(file: File): Long = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val t = audioTrack(ex) ?: return@runCatching 0L
            val f = ex.getTrackFormat(t)
            if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
        } finally {
            ex.release()
        }
    }.getOrDefault(0L)

    private fun audioTrack(ex: MediaExtractor): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }

    /**
     * Decodes [input] and writes it as [target] into [output] (made anew). [progress] gets 0…1.
     * Throws [Failed] with a reason a person can read; [output] is then left incomplete.
     */
    fun convert(input: File, output: File, target: Target, progress: (Float) -> Unit) {
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        var sink: Sink? = null
        try {
            try {
                ex.setDataSource(input.absolutePath)
            } catch (e: Exception) {
                throw Failed(tr("Android не смог открыть файл: ${e.message ?: e.javaClass.simpleName}"))
            }
            val track = audioTrack(ex) ?: throw Failed(tr("В файле нет звуковой дорожки"))
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME) ?: throw Failed(tr("Неизвестный формат звука"))
            val total = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val dec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw Failed(tr("В телефоне нет декодера для $mime"))
            }
            decoder = dec
            try {
                dec.configure(inFormat, null, null, 0)
                dec.start()
            } catch (e: Exception) {
                throw Failed(tr("Декодер не принял файл: ${e.message ?: e.javaClass.simpleName}"))
            }
            var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcm = ByteArray(0)
            var idle = 0
            while (!outputDone) {
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = dec.getInputBuffer(i) ?: throw Failed(tr("Декодер не дал буфер"))
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, max(0L, ex.sampleTime), 0)
                            ex.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    o >= 0 -> {
                        idle = 0
                        val buf = dec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (sink == null) sink = open(target, output, rate, channels)
                            val n = to16Bit(buf, encoding, pcm).also { pcm = it.first }.second
                            sink.write(pcm, n)
                            if (total > 0) progress((info.presentationTimeUs.toFloat() / total).coerceIn(0f, 0.99f))
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    else -> if (inputDone && ++idle > 500) throw Failed(tr("Декодер перестал отвечать"))
                }
            }
            (sink ?: throw Failed(tr("В файле не оказалось звука"))).finish()
            sink = null
            progress(1f)
        } catch (e: Failed) {
            throw e
        } catch (e: Exception) {
            throw Failed(tr("Перевод прервался: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { sink?.abort() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            ex.release()
        }
    }

    /**
     * The decoded samples as 16-bit little-endian bytes (whatever the decoder gave: 16-bit, float,
     * 24- or 32-bit). Returns the (possibly grown) array and how many bytes in it are filled.
     */
    private fun to16Bit(buf: ByteBuffer, encoding: Int, reuse: ByteArray): Pair<ByteArray, Int> {
        val b = buf.slice().order(ByteOrder.nativeOrder())
        return when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val n = b.remaining() / 4
                val out = if (reuse.size >= n * 2) reuse else ByteArray(n * 2)
                for (i in 0 until n) put16(out, i, (b.getFloat(i * 4) * 32767f).roundToInt())
                out to n * 2
            }
            android.media.AudioFormat.ENCODING_PCM_32BIT -> {
                val n = b.remaining() / 4
                val out = if (reuse.size >= n * 2) reuse else ByteArray(n * 2)
                for (i in 0 until n) put16(out, i, b.getInt(i * 4) shr 16)
                out to n * 2
            }
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val n = b.remaining() / 3
                val out = if (reuse.size >= n * 2) reuse else ByteArray(n * 2)
                for (i in 0 until n) {
                    // little-endian 24-bit: the two high bytes are the 16-bit sample
                    out[i * 2] = b.get(i * 3 + 1)
                    out[i * 2 + 1] = b.get(i * 3 + 2)
                }
                out to n * 2
            }
            else -> {
                val n = b.remaining()
                val out = if (reuse.size >= n) reuse else ByteArray(n)
                b.get(out, 0, n)
                out to n
            }
        }
    }

    private fun put16(out: ByteArray, i: Int, v: Int) {
        val s = v.coerceIn(-32768, 32767)
        out[i * 2] = (s and 0xFF).toByte()
        out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
    }

    private fun open(target: Target, output: File, rate: Int, channels: Int): Sink {
        if (channels !in 1..2) throw Failed(tr("Поддерживаются только моно и стерео, а в файле каналов: $channels"))
        output.parentFile?.mkdirs()
        output.delete()
        return when (target) {
            Target.WAV -> WavSink(output, rate, channels)
            Target.M4A -> {
                if (rate > 48_000) throw Failed(tr("M4A (AAC) не бывает с частотой выше 48 кГц, а в файле ${rate / 1000} кГц: выберите FLAC или WAV"))
                EncoderSink(target, output, rate, channels)
            }
            Target.FLAC -> EncoderSink(target, output, rate, channels)
        }
    }

    /** Where the decoded 16-bit samples go. */
    private interface Sink {
        fun write(pcm: ByteArray, size: Int)
        fun finish()
        fun abort()
    }

    /** Plain samples behind a RIFF header; the sizes are filled in at the end. */
    private class WavSink(val file: File, val rate: Int, val channels: Int) : Sink {
        private val out = FileOutputStream(file).buffered(1 shl 16)
        private var data = 0L

        init {
            out.write(header(0))
        }

        private fun header(dataSize: Long): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((36 + dataSize).toInt())
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(channels.toShort())
            h.putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
            h.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize.toInt())
            return h.array()
        }

        override fun write(pcm: ByteArray, size: Int) {
            out.write(pcm, 0, size)
            data += size
            if (data > 0xFFFF_0000L) throw Failed(tr("Слишком длинная запись для WAV (больше 4 ГБ)"))
        }

        override fun finish() {
            out.close()
            RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(data)) }
        }

        override fun abort() {
            runCatching { out.close() }
        }
    }

    /**
     * Samples into the phone's encoder; AAC goes into an MP4 container (MediaMuxer), FLAC frames
     * are written as they come after the stream header, whose sample count is filled in at the end.
     */
    private class EncoderSink(val target: Target, val file: File, val rate: Int, val channels: Int) : Sink {
        private val codec: MediaCodec
        private val muxer: MediaMuxer?
        private val flac: RandomAccessFile?
        private var track = -1
        private var started = false
        private var ended = false
        private var frames = 0L
        private val info = MediaCodec.BufferInfo()
        private var flacHeaderAt = -1L
        /** The FLAC stream header as the encoder gives it (sometimes in pieces, sometimes not at all). */
        private val flacHeader = java.io.ByteArrayOutputStream()
        private var flacFrames = false

        init {
            val format = MediaFormat.createAudioFormat(target.mime!!, rate, channels)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            if (target == Target.M4A) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 128_000 else 256_000)
            } else {
                format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            }
            codec = try {
                MediaCodec.createEncoderByType(target.mime)
            } catch (e: Exception) {
                throw Failed(tr("В телефоне нет кодировщика ${target.name}"))
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw Failed(tr("Кодировщик ${target.name} не принял ${rate / 1000.0} кГц, каналов: $channels"))
            }
            if (target == Target.M4A) {
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                flac = null
            } else {
                muxer = null
                flac = RandomAccessFile(file, "rw").also { it.setLength(0) }
            }
        }

        override fun write(pcm: ByteArray, size: Int) {
            val frame = 2 * channels
            var off = 0
            var waits = 0
            while (off < size) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i < 0) {
                    drain(false)
                    if (++waits > 1000) throw Failed(tr("Кодировщик перестал принимать звук"))
                    continue
                }
                waits = 0
                val buf = codec.getInputBuffer(i) ?: throw Failed(tr("Кодировщик не дал буфер"))
                buf.clear()
                var n = min(buf.remaining(), size - off)
                n -= n % frame
                if (n <= 0) n = min(size - off, buf.remaining())
                buf.put(pcm, off, n)
                codec.queueInputBuffer(i, 0, n, frames * 1_000_000L / rate, 0)
                frames += n / frame
                off += n
                drain(false)
            }
        }

        private fun drain(toEnd: Boolean) {
            var tries = 0
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (toEnd) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!toEnd) return
                        if (++tries > 1000) throw Failed(tr("Кодировщик не закончил запись"))
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxer != null && !started) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        // some encoders hand the FLAC header over in the format instead of a buffer
                        if (flac != null && flacHeader.size() == 0) {
                            runCatching { codec.outputFormat.getByteBuffer("csd-0") }.getOrNull()?.let { b ->
                                val arr = ByteArray(b.remaining())
                                b.duplicate().get(arr)
                                flacHeader.write(arr)
                            }
                        }
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (muxer != null) {
                                // the container takes the codec's setup from its format, not as a sample
                                if (!config && started) muxer.writeSampleData(track, buf, info)
                            } else if (flac != null) {
                                val bytes = ByteArray(info.size).also { buf.get(it) }
                                if (config) {
                                    // the stream header, maybe in pieces
                                    if (!flacFrames) flacHeader.write(bytes)
                                } else if (!flacFrames) {
                                    flacFrames = true
                                    startFlac(flac, bytes)
                                } else {
                                    flac.write(bytes)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            ended = true
                            return
                        }
                    }
                    else -> if (!toEnd) return
                }
            }
        }

        /** The first frames arrive: the header goes in front of them when the encoder gave a real one. */
        private fun startFlac(f: RandomAccessFile, first: ByteArray) {
            var head = flacHeader.toByteArray()
            var frames = first
            // some encoders put the header in front of the first frames instead
            if (head.isEmpty() && first.size >= 4 && String(first, 0, 4, Charsets.US_ASCII) == "fLaC") {
                head = first
                frames = ByteArray(0)
            }
            if (head.isNotEmpty()) writeFlacHeader(f, head)
            if (frames.isNotEmpty()) f.write(frames)
        }

        /**
         * "fLaC" and the metadata blocks, the last one marked as last – only when the first block is
         * a STREAMINFO; otherwise nothing is written and [finish] puts a header of its own in front.
         */
        private fun writeFlacHeader(f: RandomAccessFile, csd: ByteArray) {
            val magic = "fLaC".toByteArray(Charsets.US_ASCII)
            val withMagic = csd.size >= 4 && csd.copyOfRange(0, 4).contentEquals(magic)
            val blocks = if (withMagic) csd.copyOfRange(4, csd.size) else csd
            if (blocks.size < 4 + 34 || (blocks[0].toInt() and 0x7F) != 0) return
            var at = 0
            var lastStart = -1
            while (at + 4 <= blocks.size) {
                val len = ((blocks[at + 1].toInt() and 0xFF) shl 16) or ((blocks[at + 2].toInt() and 0xFF) shl 8) or (blocks[at + 3].toInt() and 0xFF)
                lastStart = at
                if (blocks[at].toInt() and 0x80 != 0) break
                at += 4 + len
            }
            if (lastStart >= 0) blocks[lastStart] = (blocks[lastStart].toInt() or 0x80).toByte()
            flacHeaderAt = f.filePointer
            f.write(magic)
            f.write(blocks)
        }

        /**
         * A FLAC header made here: "fLaC" and a STREAMINFO with the rate, the channels, 16 bits and the
         * number of samples (block and frame sizes left open, no checksum) – for encoders that give none.
         */
        private fun ownFlacHeader(samples: Long): ByteArray {
            val h = ByteBuffer.allocate(4 + 4 + 34).order(ByteOrder.BIG_ENDIAN)
            h.put("fLaC".toByteArray(Charsets.US_ASCII))
            h.put(0x80.toByte()).put(0).put(0).put(34) // the last block, STREAMINFO, 34 bytes
            h.putShort(16).putShort(65535.toShort()) // block sizes: any
            h.put(ByteArray(6)) // frame sizes: unknown
            h.putLong((rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or (15L shl 36) or (samples and 0xF_FFFF_FFFFL))
            h.put(ByteArray(16)) // no MD5
            return h.array()
        }

        override fun finish() {
            out.close()
            RandomAccessFile(file, "rw").use { it.seek(0); it.write(header(data)) }
        }

        override fun abort() {
            runCatching { out.close() }
        }
    }

    /**
     * Samples into the phone's encoder; AAC goes into an MP4 container (MediaMuxer), FLAC frames
     * are written as they come after the stream header, whose sample count is filled in at the end.
     */
    private class EncoderSink(val target: Target, val file: File, val rate: Int, val channels: Int) : Sink {
        private val codec: MediaCodec
        private val muxer: MediaMuxer?
        private val flac: RandomAccessFile?
        private var track = -1
        private var started = false
        private var ended = false
        private var frames = 0L
        private val info = MediaCodec.BufferInfo()
        private var flacHeaderAt = -1L
        /** The FLAC stream header as the encoder gives it (sometimes in pieces, sometimes not at all). */
        private val flacHeader = java.io.ByteArrayOutputStream()
        private var flacFrames = false

        init {
            val format = MediaFormat.createAudioFormat(target.mime!!, rate, channels)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            if (target == Target.M4A) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 128_000 else 256_000)
            } else {
                format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            }
            codec = try {
                MediaCodec.createEncoderByType(target.mime)
            } catch (e: Exception) {
                throw Failed(tr("В телефоне нет кодировщика ${target.name}"))
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw Failed(tr("Кодировщик ${target.name} не принял ${rate / 1000.0} кГц, каналов: $channels"))
            }
            if (target == Target.M4A) {
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                flac = null
            } else {
                muxer = null
                flac = RandomAccessFile(file, "rw").also { it.setLength(0) }
            }
        }

        override fun write(pcm: ByteArray, size: Int) {
            val frame = 2 * channels
            var off = 0
            var waits = 0
            while (off < size) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i < 0) {
                    drain(false)
                    if (++waits > 1000) throw Failed(tr("Кодировщик перестал принимать звук"))
                    continue
                }
                waits = 0
                val buf = codec.getInputBuffer(i) ?: throw Failed(tr("Кодировщик не дал буфер"))
                buf.clear()
                var n = min(buf.remaining(), size - off)
                n -= n % frame
                if (n <= 0) n = min(size - off, buf.remaining())
                buf.put(pcm, off, n)
                codec.queueInputBuffer(i, 0, n, frames * 1_000_000L / rate, 0)
                frames += n / frame
                off += n
                drain(false)
            }
        }

        private fun drain(toEnd: Boolean) {
            var tries = 0
            while (true) {
                val o = codec.dequeueOutputBuffer(info, if (toEnd) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!toEnd) return
                        if (++tries > 1000) throw Failed(tr("Кодировщик не закончил запись"))
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxer != null && !started) {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        // some encoders hand the FLAC header over in the format instead of a buffer
                        if (flac != null && flacHeader.size() == 0) {
                            runCatching { codec.outputFormat.getByteBuffer("csd-0") }.getOrNull()?.let { b ->
                                val arr = ByteArray(b.remaining())
                                b.duplicate().get(arr)
                                flacHeader.write(arr)
                            }
                        }
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (muxer != null) {
                                // the container takes the codec's setup from its format, not as a sample
                                if (!config && started) muxer.writeSampleData(track, buf, info)
                            } else if (flac != null) {
                                val bytes = ByteArray(info.size).also { buf.get(it) }
                                if (config) {
                                    // the stream header, maybe in pieces
                                    if (!flacFrames) flacHeader.write(bytes)
                                } else if (!flacFrames) {
                                    flacFrames = true
                                    startFlac(flac, bytes)
                                } else {
                                    flac.write(bytes)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            ended = true
                            return
                        }
                    }
                    else -> if (!toEnd) return
                }
            }
        }

        /** "fLaC" and the metadata blocks; the last block is marked as last. */
        private fun writeFlacHeader(csd: ByteArray) {
            val f = flac ?: return
            val magic = "fLaC".toByteArray(Charsets.US_ASCII)
            val withMagic = csd.size >= 4 && csd.copyOfRange(0, 4).contentEquals(magic)
            val blocks = if (withMagic) csd.copyOfRange(4, csd.size) else csd
            // walk the blocks: the encoder may leave "last block" unset on its only block
            var at = 0
            var lastStart = -1
            while (at + 4 <= blocks.size) {
                val len = ((blocks[at + 1].toInt() and 0xFF) shl 16) or ((blocks[at + 2].toInt() and 0xFF) shl 8) or (blocks[at + 3].toInt() and 0xFF)
                lastStart = at
                if (blocks[at].toInt() and 0x80 != 0) break
                at += 4 + len
            }
            if (lastStart >= 0) blocks[lastStart] = (blocks[lastStart].toInt() or 0x80).toByte()
            flacHeaderAt = f.filePointer
            f.write(magic)
            f.write(blocks)
        }

        override fun finish() {
            var waits = 0
            while (true) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    codec.queueInputBuffer(i, 0, 0, frames * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
                if (++waits > 1000) throw Failed(tr("Кодировщик не закончил запись"))
            }
            while (!ended) drain(true)
            runCatching { codec.stop() }
            codec.release()
            if (muxer != null) {
                if (!started) throw Failed(tr("Кодировщик ничего не записал"))
                muxer.stop()
                muxer.release()
            }
            flac?.let { f ->
                if (!flacFrames) throw Failed(tr("Кодировщик ничего не записал"))
                if (flacHeaderAt >= 0) {
                    patchFlacSamples(f, flacHeaderAt, frames)
                    f.close()
                } else {
                    // the encoder gave no header (Android 8 and some others): one is put in front of the frames
                    f.close()
                    val whole = File(file.path + ".head")
                    whole.outputStream().buffered(1 shl 16).use { o ->
                        o.write(ownFlacHeader(frames))
                        file.inputStream().use { it.copyTo(o) }
                    }
                    if (!file.delete() || !whole.renameTo(file)) throw Failed(tr("Не удалось дописать заголовок FLAC"))
                }
            }
        }

        /** The total sample count in STREAMINFO (36 bits from bit 108 of its data): length and seeking work. */
        private fun patchFlacSamples(f: RandomAccessFile, headerAt: Long, samples: Long) {
            val at = headerAt + 4 + 4 + 13 // "fLaC", block header, 13 bytes into STREAMINFO
            f.seek(headerAt + 4)
            if ((f.read() and 0x7F) != 0) return // the first block is not STREAMINFO
            f.seek(at)
            val b13 = f.read()
            if (b13 < 0) return
            f.seek(at)
            f.write((b13 and 0xF0) or ((samples shr 32).toInt() and 0x0F))
            f.write(((samples shr 24) and 0xFF).toInt())
            f.write(((samples shr 16) and 0xFF).toInt())
            f.write(((samples shr 8) and 0xFF).toInt())
            f.write((samples and 0xFF).toInt())
        }

        override fun abort() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { flac?.close() }
        }
    }

    /**
     * The result plays: Android opens it, finds the sound and decodes its beginning, and its
     * length matches the original's. Returns what is wrong, or null.
     */
    fun check(output: File, expectedUs: Long): String? {
        if (!output.isFile || output.length() < 512) return tr("Получился пустой файл")
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(output.absolutePath)
            val t = audioTrack(ex) ?: return tr("В получившемся файле нет звука")
            ex.selectTrack(t)
            val f = ex.getTrackFormat(t)
            val got = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            if (expectedUs > 0 && got > 0 && abs(got - expectedUs) > max(1_500_000L, expectedUs / 50)) {
                return tr("Длина не совпала: было ${expectedUs / 1_000_000} с, стало ${got / 1_000_000} с")
            }
            // a few hundred milliseconds must decode
            val d = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(f, null, null, 0)
            d.start()
            val info = MediaCodec.BufferInfo()
            var decoded = 0
            var rounds = 0
            while (decoded < 4 && rounds++ < 400) {
                val i = d.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(d.getInputBuffer(i)!!, 0)
                    if (n < 0) d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    else { d.queueInputBuffer(i, 0, n, max(0L, ex.sampleTime), 0); ex.advance() }
                }
                val o = d.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (info.size > 0) decoded++
                    d.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (decoded == 0) return tr("Получившийся файл не воспроизводится")
            return null
        } catch (e: Exception) {
            return tr("Получившийся файл не читается: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { dec?.stop() }
            runCatching { dec?.release() }
            ex.release()
        }
    }
}
