package app.malachite.core.online

/**
 * Which picture of an Internet Archive entry belongs to which song. An entry is often a whole
 * discography – "45 (1982)/cover.jpg", "Ночь (1986)/folder.jpg", a photo of the band at the top –
 * and a song gets the picture of its own album folder (or of the album above its "CD 1" folder),
 * never the picture of another album: then it gets none, and healing finds the album's own cover.
 */
object ArchiveCovers {
    /** Folders of one album's discs: "CD 1", "Disc2", "Диск 1", "Side A" is not a number but a side. */
    private val DISC = Regex("(?i)^(cd|disc|disk|диск|часть|part|side|сторона)[\\s._-]*(\\d+|[ab])$")

    /**
     * [pictures] and [songs] are paths inside the entry; [coverName] tells a picture named like a
     * cover ("cover", "front", "folder"), preferred to other pictures of the same folder.
     */
    fun picker(pictures: List<String>, songs: List<String>, coverName: (String) -> Boolean): (String) -> String? {
        val songDirs = songs.map(::dir).toSet()
        fun inside(path: String, d: String) = d.isEmpty() || path == d || path.startsWith("$d/")

        /** [d] holds the songs of one album only (or of its discs). */
        fun oneAlbum(d: String): Boolean {
            val under = songDirs.filter { inside(it, d) }
            return under.size <= 1 || under.all { it == d || (dir(it) == d && DISC.matches(it.substringAfterLast('/'))) }
        }

        fun best(list: List<String>) = list.firstOrNull { coverName(it.substringAfterLast('/')) } ?: list.firstOrNull()

        return { song ->
            var d = dir(song)
            var found: String? = null
            while (found == null && oneAlbum(d)) {
                // its own folder, or a folder of pictures in it ("Scans/front.jpg") – not a folder of other songs
                found = best(pictures.filter { dir(it) == d })
                    ?: best(pictures.filter { p -> inside(dir(p), d) && songDirs.none { it != d && inside(it, d) && inside(dir(p), it) } })
                if (d.isEmpty()) break
                d = dir(d)
            }
            found
        }
    }

    private fun dir(path: String) = path.substringBeforeLast('/', "")
}
