package app.malachite.core.text

/**
 * Cyrillic <-> Latin transliteration.
 *
 * [cyrToLat] is deterministic and is used for comparison keys.
 * [latToCyr] is a best-effort reverse used to build *search query variants*
 * ("sviditelstvo o smerti" -> "свидительство о смерти") so that cover / lyrics
 * services can be asked with the spelling they know.
 */
object Translit {

    private val CYR_TO_LAT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "",
        'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        // Ukrainian / Belarusian
        'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u",
    )

    fun cyrToLat(s: String): String {
        if (s.none { TextFold.isCyrillic(it) }) return s
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            val lower = ch.lowercaseChar()
            val t = CYR_TO_LAT[lower]
            if (t == null) {
                sb.append(ch)
            } else if (ch.isUpperCase() && t.isNotEmpty()) {
                sb.append(t[0].uppercaseChar()).append(t.substring(1))
            } else sb.append(t)
        }
        return sb.toString()
    }

    private val LAT_MULTI: List<Pair<String, String>> = listOf(
        "shch" to "щ", "sch" to "щ", "zh" to "ж", "kh" to "х", "ts" to "ц", "tz" to "ц",
        "ch" to "ч", "sh" to "ш", "yu" to "ю", "ju" to "ю", "iu" to "ю", "ya" to "я", "ja" to "я",
        "ia" to "я", "yo" to "ё", "jo" to "ё", "ye" to "е", "je" to "е", "ph" to "ф", "ck" to "к",
    )
    private val LAT_SINGLE: Map<Char, String> = mapOf(
        'a' to "а", 'b' to "б", 'v' to "в", 'g' to "г", 'd' to "д", 'e' to "е", 'z' to "з",
        'i' to "и", 'j' to "й", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о",
        'p' to "п", 'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'f' to "ф", 'h' to "х",
        'c' to "к", 'w' to "в", 'x' to "кс", 'q' to "к", '\'' to "ь",
    )
    private const val VOWELS = "aeiouyаеёиоуыэюяі"

    /** Best-effort Latin -> Cyrillic for building alternative search queries. */
    fun latToCyr(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        val lower = s.lowercase()
        while (i < s.length) {
            val ch = s[i]
            if (ch.lowercaseChar() !in 'a'..'z' && ch != '\'') {
                sb.append(ch); i++; continue
            }
            val upper = ch.isUpperCase()
            var matched: String? = null
            var len = 1
            for ((from, to) in LAT_MULTI) {
                if (lower.startsWith(from, i)) { matched = to; len = from.length; break }
            }
            if (matched == null) {
                val c = lower[i]
                matched = when (c) {
                    'y' -> {
                        val prev = if (i > 0) lower[i - 1] else ' '
                        val next = if (i + 1 < lower.length) lower[i + 1] else ' '
                        when {
                            prev in VOWELS -> "й"           // "Kuzmin" no, "Tolstoy" -> Толстой
                            !next.isLetter() && prev.isLetter() -> "ый".takeIf { prev !in VOWELS } ?: "й"
                            else -> "ы"
                        }
                    }
                    else -> LAT_SINGLE[c] ?: c.toString()
                }
            }
            if (upper && matched.isNotEmpty()) sb.append(matched[0].uppercaseChar()).append(matched.substring(1))
            else sb.append(matched)
            i += len
        }
        return sb.toString()
    }

    fun hasCyrillic(s: String) = s.any { TextFold.isCyrillic(it) }
    fun hasLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' }

    /** The other-script spelling of [s], or null if it does not apply. */
    fun alternateScript(s: String): String? = when {
        hasCyrillic(s) && !hasLatin(s) -> cyrToLat(s)
        hasLatin(s) && !hasCyrillic(s) -> latToCyr(s)
        else -> null
    }
}
