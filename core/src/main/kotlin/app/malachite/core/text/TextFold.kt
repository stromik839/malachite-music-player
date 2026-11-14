package app.malachite.core.text

import java.text.Normalizer
import java.util.Locale

/**
 * Turns "decorated" names that download sites and fancy-text generators put into tags
 * back into plain text, and builds comparison keys used to detect that two differently
 * spelled names are actually the same artist / album / track.
 *
 *   𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁, 𝓁𝒶𝓉ℯ𝓍 𝒻𝓇𝓊𝒾𝓉, 𝔩𝔞𝔱𝔢𝔵 𝔣𝔯𝔲𝔦𝔱, 𝒍𝒂𝒕𝒆𝒙 𝒇𝒓𝒖𝒊𝒕 -> latex fruit
 *   Ⱡ₳₮ɆӾ ₣ⱤɄł₮                                      -> LATEX FRUIT
 *   "Всем смертям назло" / "Всемсмертямназло"         -> same key
 *   "свидетельство о смерти" / "svidetelstvo o smerti" -> same key
 *
 * Two levels are kept strictly apart:
 *  - [cleanDisplay] is what may be written back into a file: it only removes decoration and
 *    never changes the spelling, script or case of ordinary letters;
 *  - [strictKey] / [looseKey] are lossy and are used only for comparisons.
 */
object TextFold {

    // ---------------------------------------------------------------- display cleaning

    /** Removes decoration while preserving the real spelling. Safe to write into tags. */
    fun cleanDisplay(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        // "Âåùèå Îñëû" -> "Вещие Ослы" before anything else
        val nfc = Normalizer.normalize(Mojibake.fix(input) ?: input, Normalizer.Form.NFC)
        val sb = StringBuilder(nfc.length)
        val decorativeWordMask = decorativeWords(nfc)
        val manyRegional = countRegionalIndicators(nfc) >= 3
        val manySmallCaps = nfc.codePoints().filter { SMALL_CAPS.containsKey(it) }.count() >= 2
        val manySuper = nfc.codePoints().filter { isSuperOrSubscript(it) }.count() >= 2

        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            val wordIsDecorative = decorativeWordMask[i]
            i += Character.charCount(cp)
            when {
                isInvisible(cp) -> {}
                isUnicodeSpace(cp) -> sb.append(' ')
                isDecorationMark(cp) -> {}
                cp in 0x1D400..0x1D7FF || cp in 0xFF01..0xFF5E -> sb.append(nfkcSingle(cp))
                cp in 0x2100..0x214F && cp !in LETTERLIKE_KEEP -> sb.append(nfkcSingle(cp))
                cp in 0x2460..0x24FF -> sb.append(nfkcSingle(cp))
                cp in 0x1F130..0x1F149 -> sb.append(('A' + (cp - 0x1F130)))
                cp in 0x1F150..0x1F169 -> sb.append(('A' + (cp - 0x1F150)))
                cp in 0x1F170..0x1F189 -> sb.append(('A' + (cp - 0x1F170)))
                cp in 0x1F110..0x1F12F -> sb.append(nfkcSingle(cp))
                cp in 0x1F1E6..0x1F1FF && manyRegional -> sb.append(('A' + (cp - 0x1F1E6)))
                manySmallCaps && SMALL_CAPS.containsKey(cp) -> sb.append(SMALL_CAPS.getValue(cp))
                manySuper && isSuperOrSubscript(cp) -> sb.append(nfkcSingle(cp))
                wordIsDecorative && STYLED_LETTERS.containsKey(cp) -> sb.append(STYLED_LETTERS.getValue(cp))
                else -> sb.appendCodePoint(cp)
            }
        }
        val fixed = fixMixedScriptWords(sb.toString())
        return collapseSpaces(fixed)
    }

    /** Words in which more than half of the letters (or at least two) are "styled" symbols. */
    private fun decorativeWords(s: String): BooleanArray {
        val mask = BooleanArray(s.length)
        var start = 0
        while (start < s.length) {
            while (start < s.length && s[start].isWhitespace()) start++
            var end = start
            while (end < s.length && !s[end].isWhitespace()) end++
            if (end > start) {
                val word = s.substring(start, end)
                var styled = 0
                var letters = 0
                word.codePoints().forEach { cp ->
                    if (STYLED_LETTERS.containsKey(cp)) styled++
                    if (Character.isLetter(cp) || STYLED_LETTERS.containsKey(cp)) letters++
                }
                val decorative = styled >= 2 || (letters > 0 && styled * 2 > letters)
                if (decorative) for (k in start until end) mask[k] = true
            }
            start = end
        }
        return mask
    }

    private fun countRegionalIndicators(s: String): Int =
        s.codePoints().filter { it in 0x1F1E6..0x1F1FF }.count().toInt()

    private fun nfkcSingle(cp: Int): String {
        val n = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC)
        // Only accept simple results (a letter or a digit); otherwise keep the original symbol.
        return if (n.length in 1..2 && n.all { it.isLetterOrDigit() }) n else String(Character.toChars(cp))
    }

    private fun isSuperOrSubscript(cp: Int) =
        cp in 0x1D2C..0x1D6A || cp in 0x1D9B..0x1DBF || cp in 0x2070..0x209C || cp in 0x02B0..0x02B8

    fun isInvisible(cp: Int): Boolean =
        cp in 0x200B..0x200F || cp in 0x2060..0x2064 || cp == 0xFEFF || cp == 0x00AD ||
            cp == 0x180E || cp in 0xFE00..0xFE0F || cp in 0x202A..0x202E || cp in 0x2066..0x2069 ||
            cp == 0x034F || cp in 0xE0000..0xE007F || cp < 0x20 && cp != 0x09 || cp == 0x7F

    fun isUnicodeSpace(cp: Int): Boolean =
        cp == 0x09 || cp == 0x00A0 || cp in 0x2000..0x200A || cp == 0x202F || cp == 0x205F ||
            cp == 0x3000 || cp == 0x1680

    /** Combining marks used for strike-through / underline / zalgo decoration. */
    private fun isDecorationMark(cp: Int): Boolean =
        cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x1DC0..0x1DFF ||
            cp in 0x20D0..0x20FF || cp in 0xFE20..0xFE2F

    fun collapseSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var lastSpace = true
        for (ch in s) {
            if (ch == ' ') {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(ch)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /** "Bсем" (Latin B + Cyrillic) -> "Всем"; "Sоng" (Cyrillic о) -> "Song". */
    private fun fixMixedScriptWords(s: String): String {
        if (s.none { isCyrillic(it) } || s.none { it in 'a'..'z' || it in 'A'..'Z' }) return s
        val out = StringBuilder(s.length)
        val words = s.split(' ')
        words.forEachIndexed { idx, w ->
            if (idx > 0) out.append(' ')
            val cyr = w.count { isCyrillic(it) }
            val lat = w.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (cyr > 0 && lat > 0) {
                if (cyr >= lat && w.all { !(it in 'a'..'z' || it in 'A'..'Z') || LAT_TO_CYR_HOMO.containsKey(it) }) {
                    w.forEach { out.append(LAT_TO_CYR_HOMO[it] ?: it) }
                } else if (lat > cyr && w.all { !isCyrillic(it) || CYR_TO_LAT_HOMO.containsKey(it) }) {
                    w.forEach { out.append(CYR_TO_LAT_HOMO[it] ?: it) }
                } else out.append(w)
            } else out.append(w)
        }
        return out.toString()
    }

    fun isCyrillic(c: Char) = c in 'Ѐ'..'ӿ' || c in 'Ԁ'..'ԯ'

    // ---------------------------------------------------------------- keys

    /**
     * Exact-identity key: decoration removed, case/diacritics/punctuation/spaces ignored,
     * Cyrillic transliterated to Latin. "Всем смертям назло" == "Всемсмертямназло" ==
     * "VSEM SMERTYAM NAZLO".
     */
    fun strictKey(input: String?): String {
        val clean = cleanDisplay(input).lowercase(Locale.ROOT)
        val noMarks = stripDiacritics(clean)
        val translit = Translit.cyrToLat(noMarks)
        val sb = StringBuilder(translit.length)
        translit.codePoints().forEach { cp ->
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toLowerCase(cp))
        }
        return sb.toString()
    }

    /**
     * Phonetic-ish key that also ignores differences between transliteration schemes
     * (kh/h, zh/z, ya/ja/ia, ts/c, double letters...). Used for "probably the same".
     */
    fun looseKey(input: String?): String = loosen(strictKey(input))

    fun loosen(strict: String): String {
        var s = strict
        for ((from, to) in LOOSE_RULES) s = s.replace(from, to)
        // collapse doubled letters
        val sb = StringBuilder(s.length)
        for (ch in s) if (sb.isEmpty() || sb[sb.length - 1] != ch || ch.isDigit()) sb.append(ch)
        return sb.toString()
    }

    fun stripDiacritics(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        for (ch in nfd) if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
    }

    /** All digit runs of a string: "Part 2 (2011)" -> [2, 2011]. Used to never merge "Part 1" with "Part 2". */
    fun digitRuns(input: String?): List<String> =
        Regex("\\d+").findAll(cleanDisplay(input)).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    /** True if the string contains characters that [cleanDisplay] would change. */
    fun isDecorated(input: String?): Boolean = !input.isNullOrEmpty() && cleanDisplay(input) != input

    // ---------------------------------------------------------------- tables

    private val LETTERLIKE_KEEP = setOf(
        0x2116, // №
        0x2122, // ™
        0x2117, // ℗
        0x2120, // ℠
        0x2103, 0x2109, // ℃ ℉
        0x2105, 0x2106, // ℅ ℆
        0x2121, // ℡
        0x212E, // ℮
        0x2126, // Ω (ohm) – leave
    )

    private val SMALL_CAPS: Map<Int, Char> = mapOf(
        0x1D00 to 'A', 0x0299 to 'B', 0x1D04 to 'C', 0x1D05 to 'D', 0x1D07 to 'E', 0xA730 to 'F',
        0x0262 to 'G', 0x029C to 'H', 0x026A to 'I', 0x1D0A to 'J', 0x1D0B to 'K', 0x029F to 'L',
        0x1D0D to 'M', 0x0274 to 'N', 0x1D0F to 'O', 0x1D18 to 'P', 0x0280 to 'R', 0xA731 to 'S',
        0x1D1B to 'T', 0x1D1C to 'U', 0x1D20 to 'V', 0x1D21 to 'W', 0x028F to 'Y', 0x1D22 to 'Z',
    ).mapValues { it.value.lowercaseChar() }

    /** Letters drawn with currency signs / strokes / hooks by "fancy text" generators. */
    private val STYLED_LETTERS: Map<Int, Char> = mapOf(
        // currency-style alphabet: ₳฿₵ĐɆ₣₲ⱧłJ₭Ⱡ₥₦Ø₱QⱤ₴₮ɄV₩ӾɎⱫ
        0x20B3 to 'A', 0x0E3F to 'B', 0x20B5 to 'C', 0x20A1 to 'C', 0x0110 to 'D', 0x0246 to 'E',
        0x0247 to 'e', 0x20A3 to 'F', 0x20B2 to 'G', 0x2C67 to 'H', 0x0141 to 'L', 0x0142 to 'I',
        0x20AD to 'K', 0x2C60 to 'L', 0x2C61 to 'l', 0x2C62 to 'L', 0x20A5 to 'm', 0x20A6 to 'N',
        0x00D8 to 'O', 0x00F8 to 'o', 0x20B1 to 'P', 0x2C64 to 'R', 0x20B4 to 'S', 0x20AE to 'T',
        0x0244 to 'U', 0x0289 to 'u', 0x20A9 to 'W', 0x04FE to 'X', 0x04FF to 'x', 0x024E to 'Y',
        0x024F to 'y', 0x2C6B to 'Z', 0x2C6C to 'z', 0x0197 to 'I', 0x0268 to 'i', 0x0180 to 'b',
        0x0111 to 'd', 0x01E5 to 'g', 0x0127 to 'h', 0x0126 to 'H', 0x01B5 to 'Z', 0x01B6 to 'z',
        0x20AB to 'd', 0x20B9 to 'R', 0x20BD to 'P', 0x0243 to 'B', 0x023B to 'C', 0x023C to 'c',
        0x024C to 'R', 0x024D to 'r', 0x0166 to 'T', 0x0167 to 't', 0x024A to 'Q', 0x024B to 'q',
        0x0248 to 'J', 0x0249 to 'j', 0x0190 to 'E', 0x025B to 'e', 0x0186 to 'O', 0x0254 to 'o',
        0x2C68 to 'h', 0x2C69 to 'K', 0x2C6A to 'k', 0x0198 to 'K', 0x0199 to 'k',
    )

    private val LAT_TO_CYR_HOMO: Map<Char, Char> = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х', 'y' to 'у',
        'A' to 'А', 'B' to 'В', 'E' to 'Е', 'K' to 'К', 'M' to 'М', 'H' to 'Н', 'O' to 'О',
        'P' to 'Р', 'C' to 'С', 'T' to 'Т', 'X' to 'Х', 'k' to 'к', 'm' to 'м', 'T' to 'Т',
    )
    private val CYR_TO_LAT_HOMO: Map<Char, Char> = LAT_TO_CYR_HOMO.entries.associate { it.value to it.key } +
        mapOf('і' to 'i', 'І' to 'I', 'ј' to 'j', 'Ј' to 'J', 'ѕ' to 's', 'Ѕ' to 'S')

    private val LOOSE_RULES = listOf(
        "shch" to "s", "sch" to "s", "tch" to "c", "zh" to "z", "kh" to "h", "ch" to "c", "sh" to "s",
        "ts" to "c", "tz" to "c", "ck" to "k", "ph" to "f", "th" to "t", "w" to "v", "q" to "k",
        "x" to "ks", "yu" to "u", "ju" to "u", "iu" to "u", "ya" to "a", "ja" to "a", "ia" to "a",
        "yo" to "o", "jo" to "o", "io" to "o", "ye" to "e", "je" to "e", "ie" to "e",
        "y" to "i", "j" to "i", "ei" to "e", "ii" to "i",
    )
}
