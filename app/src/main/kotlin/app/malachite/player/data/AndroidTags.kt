package app.malachite.player.data

import android.media.MediaMetadataRetriever
import app.malachite.core.audio.TagData
import java.io.File

/**
 * Reads tags with Android's own parser. It is used as a second opinion when our parser finds an
 * empty album / artist / title – some files carry tags in places only one of the two understands.
 */
object AndroidTags {
    fun read(f: File): TagData? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            fun get(key: Int) = r.extractMetadata(key)?.trim().orEmpty()
            TagData(
                title = get(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                year = get(MediaMetadataRetriever.METADATA_KEY_YEAR),
                genre = get(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = get(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
            )
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    /** True if Android sees a video stream (works for MP4, 3GP, WebM, MKV on every phone). */
    fun hasVideo(f: File): Boolean = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } finally {
            runCatching { r.release() }
        }
    }.getOrDefault(false)
}
