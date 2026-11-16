package app.malachite.core.audio

import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.wav.WavOptions
import org.jaudiotagger.audio.wav.WavSaveOptions
import org.jaudiotagger.audio.wav.WavSaveOrder
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.valuepair.TextEncoding
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.vorbiscomment.util.Base64Coder
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.wav.WavTag
import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** Metadata read from a file. */
data class TagData(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val genre: String = "",
    val track: String = "",
    val disc: String = "",
    val lyrics: String = "",
    /** Comment tag – e.g. the YouTube link that download tools put there. */
    val comment: String = "",
    /**
     * Release year found in a YouTube description ("Released on: 2001-11-04") – reliable when
     * the year tag only holds a download date.
     */
    val releaseYear: String = "",
    val hasArtwork: Boolean = false,
    val durationMs: Long = 0,
    val bitrateKbps: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
)

/**
 * A change to apply. `null` means "leave this field exactly as it is"; an empty string
 * means "remove the field". Only fields that are non-null are ever touched.
 */
data class TagEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val track: String? = null,
    val lyrics: String? = null,
    val artwork: Artwork? = null,
    val removeArtwork: Boolean = false,
) {
    class Artwork(val bytes: ByteArray, val mime: String, val width: Int = 0, val height: Int = 0)

    val isEmpty get() = title == null && artist == null && album == null && albumArtist == null &&
        year == null && genre == null && track == null && lyrics == null && artwork == null && !removeArtwork

    /** Merges two edits; values from [other] win. Used when two healers touch the same file. */
    fun merge(other: TagEdit) = TagEdit(
        title = other.title ?: title,
        artist = other.artist ?: artist,
        album = other.album ?: album,
        albumArtist = other.albumArtist ?: albumArtist,
        year = other.year ?: year,
        genre = other.genre ?: genre,
        track = other.track ?: track,
        lyrics = other.lyrics ?: lyrics,
        artwork = other.artwork ?: artwork,
        removeArtwork = (other.removeArtwork || removeArtwork) && (other.artwork ?: artwork) == null,
    )
}

/**
 * Reads and writes tags for every supported format. Writing must never be called on the
 * user's file directly – use [SafeTagWriter], which works on a verified copy.
 */
object TagIO {

    @Volatile
    private var configured = false

    /** Must be called once. [android] switches the tag library to its Android image handling. */
    @Synchronized
    fun configure(android: Boolean) {
        if (configured) return
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        val o = TagOptionSingleton.getInstance()
        o.setAndroid(android)
        // ID3v2.3 is what Windows, phones, messengers and car stereos read best.
        o.setID3V2Version(ID3V2Version.ID3_V23)
        o.setId3v23DefaultTextEncoding(TextEncoding.UTF_16)
        o.setResetTextEncodingForExistingFrames(true)
        o.setWavOptions(WavOptions.READ_ID3_UNLESS_ONLY_INFO)
        o.setWavSaveOptions(WavSaveOptions.SAVE_BOTH)
        o.setWavSaveOrder(WavSaveOrder.INFO_THEN_ID3)
        o.setPadNumbers(false)
        configured = true
    }

    fun canWrite(format: AudioFormat) = format.taggable

    fun read(file: File, format: AudioFormat = AudioFormat.detect(file)): TagData? {
        if (!configured) configure(false)
        return try {
            if (format == AudioFormat.OPUS) readOpus(file) else readJat(file, format)
        } catch (e: Throwable) {
            null
        }
    }

    fun readArtwork(file: File, format: AudioFormat = AudioFormat.detect(file)): ByteArray? = try {
        if (!configured) configure(false)
        if (format == AudioFormat.OPUS) {
            OpusTags.read(file)?.comments?.let { OpusTags.picture(it) }
        } else {
            withProperExtension(file, format) { f -> AudioFileIO.read(f).tag?.firstArtwork?.binaryData }
        }
    } catch (e: Throwable) {
        null
    }

    private fun readOpus(file: File): TagData? {
        val info = OpusTags.read(file) ?: return null
        val c = info.comments
        return TagData(
            title = c.get("TITLE").orEmpty(),
            artist = c.getAll("ARTIST").joinToString("; "),
            album = c.get("ALBUM").orEmpty(),
            albumArtist = c.get("ALBUMARTIST") ?: c.get("ALBUM ARTIST") ?: "",
            year = c.get("DATE") ?: c.get("YEAR") ?: "",
            genre = c.get("GENRE").orEmpty(),
            track = c.get("TRACKNUMBER").orEmpty(),
            disc = c.get("DISCNUMBER").orEmpty(),
            lyrics = c.get("LYRICS") ?: c.get("UNSYNCEDLYRICS") ?: "",
            comment = c.get("COMMENT") ?: c.get("DESCRIPTION") ?: "",
            releaseYear = c.fields.firstNotNullOfOrNull { RELEASED_ON.find(it.second)?.groupValues?.get(1) }.orEmpty(),
            hasArtwork = c.get(OpusTags.PICTURE_KEY) != null,
            durationMs = info.durationMs,
            channels = info.channels,
            sampleRate = 48000,
        )
    }

    private fun readJat(file: File, format: AudioFormat): TagData? = withProperExtension(file, format) { f ->
        val af = AudioFileIO.read(f)
        val tag: Tag? = af.tag
        val h = af.audioHeader
        fun get(k: FieldKey) = try { tag?.getFirst(k).orEmpty().trim() } catch (e: Exception) { "" }
        TagData(
            title = get(FieldKey.TITLE),
            artist = get(FieldKey.ARTIST),
            album = get(FieldKey.ALBUM),
            albumArtist = get(FieldKey.ALBUM_ARTIST),
            year = yearOf(tag, get(FieldKey.YEAR)),
            genre = get(FieldKey.GENRE),
            track = get(FieldKey.TRACK),
            disc = get(FieldKey.DISC_NO),
            lyrics = get(FieldKey.LYRICS),
            comment = get(FieldKey.COMMENT),
            releaseYear = releasedOn(tag),
            hasArtwork = try { tag?.firstArtwork != null } catch (e: Exception) { false },
            durationMs = ((h?.preciseTrackLength ?: 0.0) * 1000).toLong(),
            bitrateKbps = h?.bitRateAsNumber?.toInt() ?: 0,
            sampleRate = h?.sampleRateAsNumber ?: 0,
            channels = h?.channels?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
        )
    }

    /**
     * Many MP3s carry the year in a frame of the "wrong" ID3 version (TDRC inside an ID3v2.3 tag),
     * which the generic YEAR lookup misses. Check the usual year frames too.
     */
    private val RELEASED_ON = Regex("Released on:\\s*((?:19|20)\\d\\d)")

    private fun releasedOn(tag: Tag?): String {
        if (tag == null) return ""
        return runCatching {
            val it = tag.fields
            while (it.hasNext()) {
                val f = it.next()
                val text = (f as? org.jaudiotagger.tag.TagTextField)?.content ?: continue
                RELEASED_ON.find(text)?.let { m -> return@runCatching m.groupValues[1] }
            }
            ""
        }.getOrDefault("")
    }

    private fun yearOf(tag: Tag?, generic: String): String {
        if (generic.isNotBlank() || tag == null) return generic
        for (id in listOf("TDRC", "TYER", "TDOR", "TORY", "TYE", "©day", "DATE", "YEAR")) {
            val v = runCatching { tag.getFirst(id) }.getOrNull()?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        // A v2.4 frame inside a v2.3 tag is kept "unsupported": decode its text by hand.
        for (id in listOf("TDRC", "TYER", "TDOR")) {
            val v = runCatching { rawFrameText(tag, id) }.getOrNull().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun rawFrameText(tag: Tag, id: String): String {
        val f = tag.getFields(id)?.firstOrNull() as? org.jaudiotagger.tag.id3.AbstractID3v2Frame ?: return ""
        val raw = f.rawContent ?: return ""
        if (raw.size <= 11) return ""
        val body = raw.copyOfRange(10, raw.size) // 10-byte frame header (ID3v2.3 / v2.4)
        val cs = when (body[0].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(body, 1, body.size - 1, cs).trim('\u0000', ' ')
    }

    /**
     * Applies [edit] to [file] **in place**. Only for use on a scratch copy.
     * The file must already carry the extension that matches its real [format].
     */
    internal fun writeInPlace(file: File, format: AudioFormat, edit: TagEdit) {
        if (!configured) configure(false)
        require(format.taggable) { "Format $format cannot be tagged" }
        require(AudioFormat.extensionMatches(file.extension, format)) { "Extension must match real format" }
        if (edit.isEmpty) return
        if (format == AudioFormat.OPUS) {
            val tmp = File(file.parentFile, file.name + ".opustmp")
            OpusTags.rewrite(file, tmp) { c -> applyOpus(c, edit) }
            if (!file.delete() || !tmp.renameTo(file)) throw java.io.IOException("Could not replace scratch file")
            return
        }
        val af: AudioFile = AudioFileIO.read(file)
        var wavTag: WavTag? = null
        val tag: Tag = if (format == AudioFormat.WAV) {
            // Write into the ID3 chunk (the only WAV chunk that can hold a cover) and mirror
            // the text fields into the RIFF INFO chunk for old players.
            val wt = af.tagOrCreateAndSetDefault as WavTag
            if (wt.getID3Tag() == null) wt.setID3Tag(WavTag.createDefaultID3Tag())
            wt.syncToId3FromInfoIfEmpty()
            wavTag = wt
            wt.getID3Tag()
        } else if (format == AudioFormat.MP3) {
            val mp3 = af as org.jaudiotagger.audio.mp3.MP3File
            val existing = mp3.iD3v2Tag
            val t = when {
                existing == null -> ID3v23Tag()
                existing is ID3v23Tag -> existing
                else -> ID3v23Tag(existing) // convert 2.2/2.4 to 2.3, keeping all frames
            }
            mp3.setID3v2Tag(t)
            t
        } else af.tagOrCreateAndSetDefault

        fun set(key: FieldKey, v: String?) {
            if (v == null) return
            if (v.isEmpty()) {
                try { tag.deleteField(key) } catch (e: Exception) { }
            } else tag.setField(key, v)
        }
        set(FieldKey.TITLE, edit.title)
        set(FieldKey.ARTIST, edit.artist)
        set(FieldKey.ALBUM, edit.album)
        set(FieldKey.ALBUM_ARTIST, edit.albumArtist)
        set(FieldKey.YEAR, edit.year)
        set(FieldKey.GENRE, edit.genre)
        set(FieldKey.TRACK, edit.track)
        set(FieldKey.LYRICS, edit.lyrics)
        if (edit.removeArtwork || edit.artwork != null) {
            try { tag.deleteArtworkField() } catch (e: Exception) { }
        }
        edit.artwork?.let { a ->
            when (tag) {
                // FLAC and Ogg Vorbis: the tag library's own way of making the picture block asks
                // for desktop image classes and on Android always failed with
                // UnsupportedOperationException – a cover was never embedded into these files
                // (found, carried over to a FLAC made from a song). The block is made here.
                is FlacTag -> tag.setField(pictureBlock(a))
                is VorbisCommentTag -> tag.setField(
                    tag.createField(VorbisCommentFieldKey.METADATA_BLOCK_PICTURE, String(Base64Coder.encode(pictureBlock(a).rawContent))),
                )
                else -> {
                    val art = ArtworkFactory.getNew()
                    art.binaryData = a.bytes
                    art.mimeType = mimeOf(a)
                    art.pictureType = 3
                    art.description = ""
                    art.width = a.width
                    art.height = a.height
                    tag.setField(art)
                }
            }
        }
        wavTag?.let {
            it.syncToInfoFromId3Overwrite()
            it.setExistingId3Tag(true)
        }
        af.commit()
    }

    /** A FLAC picture block (also what Ogg Vorbis keeps, in Base64): the front cover, no description. */
    private fun pictureBlock(a: TagEdit.Artwork) = MetadataBlockDataPicture(a.bytes, 3, mimeOf(a), "", a.width.coerceAtLeast(0), a.height.coerceAtLeast(0), 0, 0)

    /** The picture's type as given, or read from its first bytes when none was given. */
    private fun mimeOf(a: TagEdit.Artwork): String = a.mime.ifBlank {
        val b = a.bytes
        when {
            b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b.size >= 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
            else -> "image/jpeg"
        }
    }

    private fun applyOpus(c0: OpusTags.Comments, e: TagEdit): OpusTags.Comments {
        var c = c0
        e.title?.let { c = c.with("TITLE", it) }
        e.artist?.let { c = c.with("ARTIST", it) }
        e.album?.let { c = c.with("ALBUM", it) }
        e.albumArtist?.let { c = c.with("ALBUMARTIST", it) }
        e.year?.let { c = c.with("DATE", it) }
        e.genre?.let { c = c.with("GENRE", it) }
        e.track?.let { c = c.with("TRACKNUMBER", it) }
        e.lyrics?.let { c = c.with("LYRICS", it) }
        if (e.removeArtwork || e.artwork != null) c = c.withoutPictures()
        e.artwork?.let { a ->
            c = c.copy(fields = c.fields + (OpusTags.PICTURE_KEY to OpusTags.pictureField(a.bytes, a.mime, a.width, a.height)))
        }
        return c
    }

    /**
     * The tag library chooses its parser by extension. If a file is misnamed (an MP3 called
     * ".wav"), read it through a temporary hard copy with the correct extension.
     */
    private fun <T> withProperExtension(file: File, format: AudioFormat, block: (File) -> T): T {
        val prefix = if (format == AudioFormat.M4A) id3PrefixLength(file) else 0L
        if (prefix == 0L && (AudioFormat.extensionMatches(file.extension, format) || format == AudioFormat.UNKNOWN)) return block(file)
        val tmp = File.createTempFile("malachite_read_", "." + format.extension)
        try {
            copyFrom(file, prefix, tmp)
            return block(tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Some M4A files start with an ID3 tag glued in front (an app treated them as MP3 because
     * of their name). Players skip it, the MP4 parser does not. Returns its length (0 if none).
     */
    fun id3PrefixLength(file: File): Long = runCatching {
        java.io.RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            val h = ByteArray(10)
            while (true) {
                raf.seek(offset)
                if (raf.read(h) < 10 || !Bytes.startsWith(h, 0, "ID3")) break
                offset += 10 + Bytes.syncSafe(h, 6) + if ((h[5].toInt() and 0x10) != 0) 10 else 0
            }
            offset
        }
    }.getOrDefault(0L)

    internal fun copyFrom(src: File, offset: Long, dst: File) {
        java.io.RandomAccessFile(src, "r").use { raf ->
            raf.seek(offset)
            dst.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    }
}
