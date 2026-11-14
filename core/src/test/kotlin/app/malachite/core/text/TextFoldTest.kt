package app.malachite.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextFoldTest {

    @Test
    fun fancyLatexFruitVariantsBecomePlain() {
        val variants = listOf(
            "𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁",
            "𝓁𝒶𝓉ℯ𝓍 𝒻𝓇𝓊𝒾𝓉",
            "𝔩𝔞𝔱𝔢𝔵 𝔣𝔯𝔲𝔦𝔱",
            "𝒍𝒂𝒕𝒆𝒙 𝒇𝒓𝒖𝒊𝒕",
            "ｌａｔｅｘ ｆｒｕｉｔ",
            "ⓛⓐⓣⓔⓧ ⓕⓡⓤⓘⓣ",
            "ʟᴀᴛᴇx ꜰʀᴜɪᴛ",
            "l̶a̶t̶e̶x̶ f̶r̶u̶i̶t̶",
        )
        for (v in variants) assertEquals("latex fruit", TextFold.cleanDisplay(v).lowercase(), v)
        assertEquals("LATEX FRUIT", TextFold.cleanDisplay("Ⱡ₳₮ɆӾ ₣ⱤɄł₮"))
        val keys = (variants + "Ⱡ₳₮ɆӾ ₣ⱤɄł₮" + "Latex Fruit").map { TextFold.strictKey(it) }.toSet()
        assertEquals(setOf("latexfruit"), keys)
    }

    @Test
    fun ordinaryTextIsNotTouched() {
        val samples = listOf(
            "Всем смертям назло", "Łódź", "Sigur Rós", "Mötley Crüe", "Песня №1", "AC/DC",
            "Boris Kuzmin", "Время выткало нить", "Ølstykke", "E=MC²", "Beyoncé", "日本語のタイトル",
        )
        for (s in samples) assertEquals(s, TextFold.cleanDisplay(s), s)
    }

    @Test
    fun invisibleAndWeirdSpacesAreRemoved() {
        assertEquals("Всем смертям назло", TextFold.cleanDisplay("  Всем смертям​  назло﻿ "))
    }

    @Test
    fun spacesAndScriptAreIgnoredInKeys() {
        assertEquals(TextFold.strictKey("Всем смертям назло"), TextFold.strictKey("Всемсмертямназло"))
        assertEquals(TextFold.strictKey("Всем смертям назло"), TextFold.strictKey("VSEM SMERTYAM NAZLO"))
        assertEquals(TextFold.strictKey("свидительство о смерти"), TextFold.strictKey("sviditelstvo o smerti"))
        assertEquals(TextFold.strictKey("Всё"), TextFold.strictKey("Все"))
    }

    @Test
    fun homoglyphMixedWordsAreFixed() {
        // Latin 'B' and 'o' inside Russian words
        assertEquals("Всем смертям назло", TextFold.cleanDisplay("Bсем смертям нaзлo"))
    }

    @Test
    fun sameNameLevels() {
        assertEquals(MatchConfidence.EXACT, Similarity.sameName("Всем смертям назло", "Всемсмертямназло"))
        assertEquals(MatchConfidence.EXACT, Similarity.sameName("𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁", "latex fruit"))
        assertEquals(MatchConfidence.STRONG, Similarity.sameName("Кухня", "Kuhnya"))
        assertEquals(MatchConfidence.POSSIBLE, Similarity.sameName("Свидетельство о смерти", "sviditelstvo o smerti"))
        assertNull(Similarity.sameName("Part 1", "Part 2"))
        assertNull(Similarity.sameName("Кино", "Кина"))
        assertNull(Similarity.sameName("Nirvana", "Metallica"))
    }

    @Test
    fun groupingPicksNiceCanonical() {
        val groups = NameGrouping.group(
            listOf(
                NameVariant("Свидетельство о смерти", 20),
                NameVariant("sviditelstvo o smerti", 1),
                NameVariant("Свидетельство  о смерти ", 2),
                NameVariant("Кино", 30),
            ),
        )
        assertEquals(1, groups.size)
        val g = groups[0]
        assertEquals("Свидетельство о смерти", g.canonical)
        assertEquals(MatchConfidence.POSSIBLE, g.confidence)
        assertEquals(3, g.variants.size)

        val albums = NameGrouping.group(listOf(NameVariant("Всем смертям назло", 9), NameVariant("Всемсмертямназло", 3)))
        assertEquals("Всем смертям назло", albums.single().canonical)
        assertEquals(MatchConfidence.EXACT, albums.single().confidence)
    }

    @Test
    fun decoratedSingleVariantIsCleaned() {
        val g = NameGrouping.group(listOf(NameVariant("𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁", 1), NameVariant("latex fruit", 5)))
        assertEquals("latex fruit", g.single().canonical)
        val single = NameGrouping.group(listOf(NameVariant("𝒍𝒂𝒕𝒆𝒙 𝒇𝒓𝒖𝒊𝒕", 1)))
        assertEquals("latex fruit", single.single().canonical)
    }

    @Test
    fun watermarksAreStripped() {
        assertEquals("Подводная лодка", Watermarks.strip("Подводная лодка (muzofond.fm)"))
        assertEquals("Подводная лодка", Watermarks.strip("Подводная лодка - www.mp3party.net"))
        assertEquals("Song", Watermarks.strip("Song [zaycev.net]"))
        assertEquals("Song", Watermarks.strip("Song (Official Video)"))
        assertEquals("Song (Live)", Watermarks.strip("Song (Live)"))
        assertEquals("Mr. Brightside", Watermarks.strip("Mr. Brightside"))
        assertTrue(Watermarks.contains("x (muzofond.fm)"))
        assertEquals("Artist", Watermarks.primaryArtist("Artist feat. Other"))
        assertEquals("Simon & Garfunkel", Watermarks.primaryArtist("Simon & Garfunkel"))
    }

    @Test
    fun mojibakeIsRepaired() {
        // real tags from the "Безнадёжность (2022)" album
        assertEquals("Вещие Ослы", Mojibake.fix("Âåùèå Îñëû"))
        assertEquals("Безнадёжность", Mojibake.fix("Áåçíàä¸æíîñòü"))
        assertEquals("Когда Я Его Видал", Mojibake.fix("Êîãäà ß Åãî Âèäàë"))
        assertEquals("Не положено", Mojibake.fix("Íå ïîëîæåíî"))
        // UTF-8 shown as Windows-1251 and as Latin-1
        assertEquals("Вещие Ослы", Mojibake.fix("Р’РµС‰РёРµ РћСЃР»С‹"))
        assertEquals("Вещие Ослы", Mojibake.fix(String("Вещие Ослы".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)))
        // real names stay as they are
        for (ok in listOf("Mötley Crüe", "Beyoncé", "Sigur Rós", "Ølstykke", "Café del Mar", "Ñandú", "Ça été", "Вещие Ослы", "Рок-н-ролл", "Сплин")) {
            assertEquals(null, Mojibake.fix(ok), ok)
        }
        // and grouping sees them as the same artist
        assertEquals(TextFold.strictKey("Вещие Ослы"), TextFold.strictKey("Âåùèå Îñëû"))
        assertEquals("Вещие Ослы", NameGrouping.group(listOf(NameVariant("Вещие Ослы", 50), NameVariant("Âåùèå Îñëû", 11))).single().canonical)
    }

    @Test
    fun translitBothWays() {
        assertEquals("sviditelstvo o smerti", Translit.cyrToLat("свидительство о смерти"))
        assertEquals("свидителство о смерти", Translit.latToCyr("sviditelstvo o smerti")) // best effort: soft sign is unknowable
        assertEquals("Борис Кузмин", Translit.latToCyr("Boris Kuzmin"))
        assertNotEquals(null, Translit.alternateScript("Кино"))
    }
}
