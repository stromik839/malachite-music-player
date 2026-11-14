package app.malachite.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Repairs "кракозябры" – Cyrillic text that was stored in one encoding and read in another,
 * very common in Russian MP3 tags:
 *
 *   "Âåùèå Îñëû"        (Windows-1251 read as Latin-1)  -> "Вещие Ослы"
 *   "Р’РµС‰РёРµ РћСЃР»С‹" (UTF-8 read as Windows-1251)  -> "Вещие Ослы"
 *   "Ð’ÐµÑ‰Ð¸Ðµ ÐžÑÐ»Ñ‹"   (UTF-8 read as Latin-1)        -> "Вещие Ослы"
 *
 * Android's own media scanner guesses this for Mi Music & co, so such songs look fine there.
 * A string is only changed when the result is clearly Cyrillic text.
 */
object Mojibake {

    private val CP1251: Charset? = runCatching { Charset.forName("windows-1251") }.getOrNull()
    private val LATIN1 = Charsets.ISO_8859_1

    /** The repaired string, or null when [s] does not look broken. */
    fun fix(s: String?): String? {
        if (s.isNullOrEmpty() || s.all { it.code < 0x80 }) return null
        val letters = s.count { it.isLetter() }
        if (letters == 0) return null
        val hasCyrillic = s.any { TextFold.isCyrillic(it) }

        // 1) UTF-8 bytes shown as Latin-1: "Ð’ÐµÑ‰Ð¸Ðµ"
        if (!hasCyrillic && s.all { it.code <= 0xFF } && s.count { it == 'Ð' || it == 'Ñ' } * 3 >= letters) {
            decodeStrict(s.toByteArray(LATIN1), Charsets.UTF_8)?.let { if (isGoodCyrillic(it)) return it }
        }
        val cp = CP1251
        // 2) Windows-1251 bytes shown as Latin-1: "Âåùèå Îñëû" (letters in À..ÿ, plus ¸/¨ for ё/Ё)
        if (!hasCyrillic && cp != null && s.all { it.code <= 0xFF }) {
            val high = s.count { it.code in 0xC0..0xFF || it == '¸' || it == '¨' }
            if (high * 4 >= letters * 3) {
                val decoded = String(s.toByteArray(LATIN1), cp)
                if (isGoodCyrillic(decoded)) return decoded
            }
        }
        // 3) UTF-8 bytes shown as Windows-1251: "Р’РµС‰РёРµ" – lots of Р/С followed by odd symbols
        if (hasCyrillic && cp != null) {
            val rs = s.count { it == 'Р' || it == 'С' }
            if (rs * 3 >= letters) {
                val bytes = runCatching { encodeStrict(s, cp) }.getOrNull()
                if (bytes != null) decodeStrict(bytes, Charsets.UTF_8)?.let { if (isGoodCyrillic(it) && it.length < s.length) return it }
            }
        }
        return null
    }

    fun fixOrSelf(s: String): String = fix(s) ?: s

    private fun isGoodCyrillic(t: String): Boolean {
        val letters = t.count { it.isLetter() }
        if (letters == 0) return false
        val cyr = t.count { TextFold.isCyrillic(it) }
        // mostly Cyrillic, no replacement chars, no leftover Latin-1 noise, and no word that mixes
        // Latin and Cyrillic letters ("Ça été" must stay French, not become "За йtй")
        if (cyr * 10 < letters * 7 || t.any { it == '\uFFFD' || it.code in 0x80..0x9F }) return false
        return t.split(Regex("[^\\p{L}]+")).none { w -> w.any { TextFold.isCyrillic(it) } && w.any { it in 'a'..'z' || it in 'A'..'Z' } }
    }

    private fun decodeStrict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun encodeStrict(s: String, cs: Charset): ByteArray {
        val enc = cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val bb = enc.encode(java.nio.CharBuffer.wrap(s))
        return ByteArray(bb.remaining()).also { bb.get(it) }
    }
}
