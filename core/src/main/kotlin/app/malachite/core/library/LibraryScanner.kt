package app.malachite.core.library

import app.malachite.core.audio.AudioFormat
import app.malachite.core.audio.TagIO
import java.io.File

/** Reads the songs that are inside the main folder (only those – nothing else is shown). */
class LibraryScanner(private val root: File, private val options: LibraryLayout.Options = LibraryLayout.Options()) {

    /**
     * Returns all songs in the main folder. Tags are re-read only for files whose size or
     * modification time changed since [cached].
     */
    fun scan(cached: List<TrackInfo>, threads: Int = 3, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<TrackInfo> {
        val byPath = cached.associateBy { it.path }
        val files = listAudioFiles()
        val out = arrayOfNulls<TrackInfo>(files.size)
        val toRead = ArrayList<Int>()
        for ((i, f) in files.withIndex()) {
            val rel = f.relativeTo(root).invariantSeparatorsPath
            val c = byPath[rel]
            if (c != null && c.size == f.length() && c.lastModified == f.lastModified()) out[i] = c else toRead += i
        }
        // Only new / changed files are read – in parallel, the slow part on big libraries.
        val done = java.util.concurrent.atomic.AtomicInteger(files.size - toRead.size)
        onProgress(done.get(), files.size)
        if (toRead.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(threads.coerceIn(1, 8))
            try {
                val futures = toRead.map { i ->
                    pool.submit {
                        val f = files[i]
                        out[i] = runCatching { read(f) }.getOrElse { TrackInfo(path = f.relativeTo(root).invariantSeparatorsPath, size = f.length(), lastModified = f.lastModified()) }
                        val d = done.incrementAndGet()
                        if (d % 25 == 0) onProgress(d, files.size)
                    }
                }
                futures.forEach { it.get() }
            } finally {
                pool.shutdown()
            }
        }
        onProgress(files.size, files.size)
        return out.filterNotNull()
    }

    fun read(f: File, rel: String = f.relativeTo(root).invariantSeparatorsPath): TrackInfo =
        fillFromLayout(readTags(f, rel))

    /**
     * Inside the main folder the layout is Artist/Album/Song: when a tag is empty (or the format
     * cannot hold tags) the folder tells what it is.
     */
    private fun fillFromLayout(t: TrackInfo): TrackInfo {
        val segs = t.path.split('/')
        if (segs.size != 3) return t
        val artistDir = segs[0]
        val albumDir = segs[1]
        val inferred = t.inferred.split(',').filterTo(ArrayList()) { it.isNotEmpty() }
        val before = inferred.size
        var out = t
        val albumName = LibraryLayout.albumFromFolder(albumDir, options)
        if (t.album.isBlank() && albumName != null) { out = out.copy(album = albumName); inferred += "album" }
        // "Разные исполнители", "Download", "AyuGram": a folder that is no artist (once it was
        // taken for one and healing wrote "Разные исполнители" into songs as their artist)
        if (t.artist.isBlank() && t.albumArtist.isBlank() && artistDir != LibraryLayout.UNKNOWN_ARTIST && !FolderHints.isGeneric(artistDir)) {
            out = out.copy(artist = artistDir); inferred += "artist"
        }
        return if (inferred.size == before) out else out.copy(inferred = inferred.joinToString(","))
    }

    private fun readTags(f: File, rel: String): TrackInfo {
        val format = AudioFormat.detect(f)
        val t = TagIO.read(f, format)
        // "20250227" is a download date; "Released on: 2001-11-04" in the YouTube description is the release
        val yearFromDescription = t != null && LibraryLayout.reliableYear(t.year) == null && t.releaseYear.isNotEmpty()
        return TrackInfo(
            path = rel,
            title = t?.title.orEmpty(),
            artist = t?.artist.orEmpty(),
            album = t?.album.orEmpty(),
            albumArtist = t?.albumArtist.orEmpty(),
            year = if (yearFromDescription) t!!.releaseYear else t?.year.orEmpty(),
            genre = t?.genre.orEmpty(),
            track = t?.track?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            disc = t?.disc?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
            durationMs = t?.durationMs ?: 0,
            size = f.length(),
            lastModified = f.lastModified(),
            format = format.name,
            hasArtwork = t?.hasArtwork ?: false,
            bitrateKbps = t?.bitrateKbps ?: 0,
            sampleRate = t?.sampleRate ?: 0,
            inferred = if (yearFromDescription) "year" else "",
        )
    }

    fun listAudioFiles(): List<File> {
        val out = ArrayList<File>()
        val top = root.listFiles() ?: return out
        for (child in top) {
            if (child.name in LibraryLayout.RESERVED || child.name.startsWith(".")) continue
            if (child.isDirectory) {
                child.walkTopDown()
                    .onEnter { !it.name.startsWith(".") }
                    .filter { it.isFile && isAudioName(it) }
                    .forEach { out += it }
            } else if (isAudioName(child)) out += child
        }
        return out
    }

    companion object {
        fun isAudioName(f: File) = !f.name.startsWith(".") && f.extension.lowercase() in AudioFormat.SCAN_EXTENSIONS
    }
}
