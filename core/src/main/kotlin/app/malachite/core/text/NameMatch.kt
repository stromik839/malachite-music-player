package app.malachite.core.text

import app.malachite.core.online.OnlineMatch

/**
 * Whether a typed name answers an artist, album or title: letter case, the alphabet ("kino" finds
 * "Кино", "молчат" finds "Molchat Doma"), the beginning of a word and a small typo do not matter.
 */
object NameMatch {
    fun matches(query: String, value: String): Boolean {
        if (value.isBlank()) return false
        val q = TextFold.looseKey(query)
        if (q.isEmpty()) return false
        // every typed word is in the name (or begins a word of it)
        if (OnlineMatch.words(query).isNotEmpty() && OnlineMatch.covered(query, value) >= 1.0) return true
        // typed without spaces or in the middle of a word ("молчатдома")
        return TextFold.looseKey(value).contains(q)
    }
}
