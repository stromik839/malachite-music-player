package app.malachite.core.lyrics

/**
 * Where the chorus of a song starts, found in its synced lyrics: the longest run of lines that are
 * sung again later in the song. Used as the "best moment" mark on the seek bar until the user
 * marks one of their own.
 */
object BestMoment {
    private val NOT_WORDS = Regex("[^\\p{L}\\p{N}]+")

    private fun key(text: String): String = text.lowercase().replace('ё', 'е').replace(NOT_WORDS, " ").trim()

    /** The moment in the song (ms, the lyrics' own shift counted in); null when there is no clear chorus. */
    fun guess(lyrics: Lyrics): Long? {
        if (!lyrics.synced) return null
        val lines = lyrics.lines.filter { !SyncRecorder.isPauseText(it.text) && key(it.text).isNotEmpty() }
        if (lines.size < 4) return null
        val keys = lines.map { key(it.text) }
        val count = keys.groupingBy { it }.eachCount()
        var bestStart = -1
        var bestLength = 0
        var bestRepeats = 0
        var i = 0
        while (i < keys.size) {
            if ((count[keys[i]] ?: 0) < 2) { i++; continue }
            var j = i
            while (j < keys.size && (count[keys[j]] ?: 0) >= 2) j++
            val length = j - i
            val repeats = (i until j).minOf { count.getValue(keys[it]) }
            if (length > bestLength || (length == bestLength && repeats > bestRepeats)) {
                bestStart = i
                bestLength = length
                bestRepeats = repeats
            }
            i = j
        }
        // one line sung twice is not a chorus yet; one line sung three times or more is
        if (bestStart < 0 || (bestLength < 2 && bestRepeats < 3)) return null
        return (lines[bestStart].startMs + lyrics.offsetMs).coerceAtLeast(0)
    }
}
