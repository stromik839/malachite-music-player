package app.malachite.core.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsTest {

    private val genius = """
        [Текст песни «Подводная лодка»]

        [Интро]
        Раз, два, три, и—

        [Куплет 1]
        В глубинах воды сторожа горизонт
        Кому и зачем я отдам свою жизнь?
        [Припев]
        Подводная лодка, пробиты борта
        Мы ляжем на дно навсегда
        Ну и пусть, ну и пусть, ну и пусть (ну и пусть)
        (Припев)
        {Bridge}
        <Аутро>
        Подводная лодка, пробиты борта (x2)
        И телом моим станет лёгкая грусть123Embed
    """.trimIndent()

    @Test
    fun geniusAnnotationsAreRemoved() {
        val lines = LyricsCleaner.singableLines(genius)
        assertEquals(
            listOf(
                "Раз, два, три, и—",
                "В глубинах воды сторожа горизонт",
                "Кому и зачем я отдам свою жизнь?",
                "Подводная лодка, пробиты борта",
                "Мы ляжем на дно навсегда",
                "Ну и пусть, ну и пусть, ну и пусть (ну и пусть)",
                // "(Припев)" with nothing under it = the chorus sung again
                "Подводная лодка, пробиты борта",
                "Мы ляжем на дно навсегда",
                "Ну и пусть, ну и пусть, ну и пусть (ну и пусть)",
                // "(x2)" = the line twice
                "Подводная лодка, пробиты борта",
                "Подводная лодка, пробиты борта",
                "И телом моим станет лёгкая грусть",
            ),
            lines,
        )
        // The sung backing vocal is kept by default, removed in strict mode.
        val strict = LyricsCleaner.singableLines(genius, LyricsCleaner.Options(removeAllParentheses = true))
        assertTrue("Ну и пусть, ну и пусть, ну и пусть" in strict)
    }

    @Test
    fun englishGeniusHeaderAndFooter() {
        val text = "12 Contributors\nSong Name Lyrics\n[Verse 1: Someone]\nHello world\n[Chorus]\nYeah (Yeah)\nYou might also like\nBye5Embed"
        assertEquals(listOf("Hello world", "Yeah (Yeah)", "Bye"), LyricsCleaner.singableLines(text))
    }

    @Test
    fun lrcRoundTripWithEndsAndWords() {
        val lrc = """
            [ar:Test]
            [offset:+100]
            [00:12.50]First line
            [00:15.00]
            [00:20.00]<00:20.00>Second <00:20.50>line <00:21.20>
            [00:25.00][01:00.00]Chorus [Припев]
        """.trimIndent()
        val l = Lrc.parse(lrc)
        assertTrue(l.synced)
        assertEquals(100, l.offsetMs)
        assertEquals(4, l.lines.size)
        assertEquals(12_500, l.lines[0].startMs)
        assertEquals(15_000, l.lines[0].endMs)
        assertEquals(2, l.lines[1].words.size)
        assertEquals(21_200, l.lines[1].endMs)
        assertEquals("Chorus", l.lines[2].text)
        assertEquals(60_000, l.lines[3].startMs)
        val again = Lrc.parse(Lrc.write(l))
        assertEquals(l.lines.map { it.startMs + 100 }, again.lines.map { it.startMs })
        assertEquals(l.lines.map { it.text }, again.lines.map { it.text })
    }

    @Test
    fun activeLineAndGaps() {
        val l = Lrc.parse("[00:01.00]a\n[00:03.00]\n[00:05.00]b\n[00:07.00]c")
        assertEquals(-1, l.activeLine(500))
        assertEquals(0, l.activeLine(1500))
        assertTrue(l.isInGap(4000))
        assertEquals(1, l.activeLine(6000))
        assertFalse(l.isInGap(6000))
        assertEquals(2, l.activeLine(100_000))
    }

    @Test
    fun syncRecorderTimingsAreIndependentOfSpeed() {
        // Same song, line sung from 10.0s to 12.0s and from 13.0s to 15.0s (song time).
        // The user always reacts 200 ms (real time) late.
        fun record(speed: Float): Lyrics {
            val r = SyncRecorder(listOf("Подводная лодка", "Мы ляжем на дно"), reactionMs = 200)
            val lag = (200 * speed).toLong() // 200 ms of real time is this much song time
            r.press(10_000 + lag, speed); r.release(12_000 + lag, speed)
            r.press(13_000 + lag, speed); r.release(15_000 + lag, speed)
            assertTrue(r.isComplete)
            return r.result()
        }
        for (speed in listOf(0.5f, 1f, 2f)) {
            val l = record(speed)
            assertEquals(listOf(10_000L, 13_000L), l.lines.map { it.startMs }, "speed $speed")
            assertEquals(listOf(12_000L, 15_000L), l.lines.map { it.endMs }, "speed $speed")
        }
    }

    @Test
    fun wordModeProducesEnhancedLrc() {
        val r = SyncRecorder(listOf("ну и пусть"), SyncRecorder.Mode.WORDS)
        r.press(1000, 1f); r.release(1300, 1f)
        r.press(1350, 1f); r.release(1500, 1f)
        r.press(1600, 1f); r.release(2100, 1f)
        val l = r.result()
        assertEquals(1, l.lines.size)
        assertEquals(3, l.lines[0].words.size)
        assertEquals(2100, l.lines[0].endMs)
        val text = Lrc.write(l)
        assertTrue(text.contains("<00:01.35>и"), text)
        val back = Lrc.parse(text)
        assertEquals(listOf(1000L, 1350L, 1600L), back.lines[0].words.map { it.startMs })
    }

    @Test
    fun undoReturnsToRetryPoint() {
        val r = SyncRecorder(listOf("a", "b"))
        r.press(5000, 1f); r.release(6000, 1f)
        r.press(8000, 1f); r.release(9000, 1f)
        assertEquals(5500, r.undo())
        assertEquals(1, r.cursor)
        r.skip()
        assertTrue(r.isComplete)
        assertEquals(1, r.result().lines.size)
    }

    @Test
    fun scoreRejectsOtherVersions() {
        val good = LyricsFinder.score("Подводная лодка", "Свидетельство о смерти", 245.0, "Подводная лодка", "Свидетельство о смерти", 246.0, true)
        val live = LyricsFinder.score("Подводная лодка", "Свидетельство о смерти", 245.0, "Подводная лодка", "Свидетельство о смерти", 310.0, true)
        val translit = LyricsFinder.score("Podvodnaya lodka", "Svidetelstvo o smerti", 245.0, "Подводная лодка", "Свидетельство о смерти", 245.0, true)
        assertTrue(good > 0.9, "$good")
        assertTrue(live < 0.7, "$live")
        assertTrue(translit > 0.85, "$translit")
    }

    @Test
    fun searchTitleDropsYoutubeJunk() {
        val w = app.malachite.core.text.Watermarks
        kotlin.test.assertEquals("Лёха", w.searchTitle("Алена Апина - \"Лёха\" (Official Video)", "Алёна Апина"))
        kotlin.test.assertEquals("Когда я ответил да", w.searchTitle("Когда я ответил да (Sefon.Pro)", "Свидетельство о смерти"))
        kotlin.test.assertEquals("Лёха", w.searchTitle("«Лёха»", ""))
        // another artist in front stays (it may be a duet / cover), quotes inside stay
        kotlin.test.assertEquals("Кино - Группа крови", w.searchTitle("Кино - Группа крови", "Сплин"))
        kotlin.test.assertEquals("\"a\" и \"b\"", w.unquote("\"a\" и \"b\""))
        val q = app.malachite.core.net.QueryVariants.build("Алёна Апина", "Алена Апина - \"Лёха\" (Official Video)", isTitle = true)
        kotlin.test.assertEquals("Лёха", q.first().second)
        kotlin.test.assertEquals(
            "Лёха",
            app.malachite.core.library.FolderHints.stripArtistPrefix("Алена Апина - \"Лёха\"", "Алёна Апина"),
        )
    }

    @Test
    fun geniusPageIsParsed() {
        val html = """<html><div class="x"><div data-lyrics-container="true" class="Lyrics__Container">""" +
            """<div data-exclude-from-selection="true"><span>12 Contributors</span><div>Лёха Lyrics</div></div>""" +
            """[Куплет 1]<br/>Лёха, Лёха, мне без тебя<br><a href="#"><span>так плохо &amp; грустно</span></a><br/>""" +
            """</div><div>noise</div><div data-lyrics-container="true">[Припев]<br/>Лёха&#x27;s</div></div></html>"""
        val text = HtmlLyrics.genius(html)!!
        kotlin.test.assertEquals("[Куплет 1]\nЛёха, Лёха, мне без тебя\nтак плохо & грустно\n\n[Припев]\nЛёха's", text)
        val clean = LyricsCleaner.clean(text, LyricsCleaner.Options())
        kotlin.test.assertTrue(!clean.contains("Куплет") && !clean.contains("Припев") && clean.contains("мне без тебя"), clean)
    }

    @Test
    fun syncedLyricsWinOverPlain() {
        fun c(synced: Boolean, score: Double) = LyricsFinder.Candidate(
            if (synced) Lyrics(listOf(LyricLine(1000, text = "a")), synced = true) else Lyrics.plain("a"),
            "t", "a", "", 0.0, score, "x",
        )
        kotlin.test.assertTrue(LyricsFinder.pick(listOf(c(false, 0.95), c(true, 0.75)))!!.synced)
        kotlin.test.assertTrue(!LyricsFinder.pick(listOf(c(false, 0.95), c(true, 0.5)))!!.synced)
        kotlin.test.assertEquals(null, LyricsFinder.pick(listOf(c(false, 0.5))))
        kotlin.test.assertTrue(app.malachite.core.cover.AlbumFinder.isCompilation("Лучшие песни"))
        kotlin.test.assertTrue(app.malachite.core.cover.AlbumFinder.isCompilation("Greatest Hits"))
        kotlin.test.assertTrue(!app.malachite.core.cover.AlbumFinder.isCompilation("Танцевать до утра"))
        kotlin.test.assertTrue(!app.malachite.core.cover.AlbumFinder.isCompilation("Всем смертям назло"))
    }

    @Test
    fun markedRepeatsAreWrittenOut() {
        val text = """
            [Куплет 1]
            Раз строка
            Два строка (x2)

            [Припев x2]
            Ла-ла-ла
            На-на-на

            [Куплет 2]
            Третья строка
            (x2)

            [Припев]

            Конец ×3
        """.trimIndent()
        val lines = LyricsCleaner.singableLines(text)
        assertEquals(
            listOf(
                "Раз строка", "Два строка", "Два строка",
                "Ла-ла-ла", "На-на-на", "Ла-ла-ла", "На-на-на",
                "Третья строка", "Третья строка",
                "Ла-ла-ла", "На-на-на", // the empty "[Припев]" is the chorus again
                "Конец", "Конец", "Конец",
            ),
            lines,
        )
        // backing vocals in brackets and real words with "x" stay
        assertEquals(listOf("Мы идём (идём)", "Xbox 2", "Я сказал тебе 2 раза"), LyricsCleaner.singableLines("Мы идём (идём)\nXbox 2\nЯ сказал тебе 2 раза"))
        assertEquals(listOf("Эй", "Эй"), LyricsCleaner.singableLines("Эй (2 раза)"))
    }

    @Test
    fun musicLinesMarkThemselvesWhenNothingIsPressed() {
        val r = SyncRecorder(listOf("Первая строка", "♪", "Вторая строка", "♪", "Третья строка"))
        r.autoPauses = true
        r.press(1000, 1f); r.release(4000, 1f)
        // a breath between lines: nothing happens before the wait is over
        assertFalse(r.autoPause(5000, 1f, 3000))
        // pressed early: the "♪" is not wanted, the press is for the next sung line
        r.press(5500, 1f); r.release(9000, 1f)
        // silence for long enough: the "♪" starts where the singing stopped and ends with the next press
        assertTrue(r.autoPause(12500, 1f, 3000))
        r.press(20000, 1f); r.release(23000, 1f)
        val l = r.result()
        assertEquals(listOf("Первая строка", "Вторая строка", "♪", "Третья строка"), l.lines.map { it.text })
        val pause = l.lines[2]
        assertEquals(9000, pause.startMs)
        assertEquals(20000, pause.endMs)
        // at a slower speed the wait is real time: 3 s of waiting is 1.5 s of song at 0.5x
        val slow = SyncRecorder(listOf("a", "♪", "b")).also { it.autoPauses = true }
        slow.press(0, 0.5f); slow.release(1000, 0.5f)
        assertFalse(slow.autoPause(2400, 0.5f, 3000))
        assertTrue(slow.autoPause(2600, 0.5f, 3000))
    }

    @Test
    fun takingBackTheLineAfterMusicReopensTheMusic() {
        val r = SyncRecorder(listOf("a", "♪", "b")).also { it.autoPauses = true }
        r.press(0, 1f); r.release(1000, 1f)
        assertTrue(r.autoPause(6000, 1f, 3000))
        r.press(8000, 1f); r.release(9000, 1f)
        r.undo()
        r.press(8500, 1f); r.release(9500, 1f)
        val l = r.result()
        assertEquals(8500, l.lines.first { it.text == "♪" }.endMs)
        assertTrue(SyncRecorder.isPauseText("♪ ♪"))
        assertTrue(SyncRecorder.isPauseText("(проигрыш)"))
        assertFalse(SyncRecorder.isPauseText("Музыка"))
        assertFalse(SyncRecorder.isPauseText("Я слышу ♪ музыку"))
    }

    @Test
    fun wordsInAnotherAlphabetAreNotTheSongsWords() {
        val latin = Lyrics.plain("I dve tysyachi let voyna\nVoyna bez osobykh prichin\nVoyna – delo molodykh\nLekarstvo protiv morshchin", "LRCLIB")
        val cyrillic = Lyrics.plain("И две тысячи лет война\nВойна без особых причин\nВойна – дело молодых\nЛекарство против морщин", "LRCLIB")
        assertTrue(LyricsScript.foreignLetters("Звезда по имени Солнце", "Кино", latin))
        assertFalse(LyricsScript.foreignLetters("Звезда по имени Солнце", "Кино", cyrillic))
        // a song named in Latin letters is not judged: it may well be sung in Russian or in English
        assertFalse(LyricsScript.foreignLetters("Zvezda po imeni Solntse", "Kino", latin))
        assertFalse(LyricsScript.foreignLetters("Zvezda po imeni Solntse", "Kino", cyrillic))
        // a title without words: the artist tells
        assertTrue(LyricsScript.foreignLetters("45", "Кино", latin))
        // a Russian song with an English chorus is still the song
        val mixed = Lyrics.plain("И две тысячи лет война\nВойна без особых причин\nOh yeah, oh yeah\nЛекарство против морщин", "LRCLIB")
        assertFalse(LyricsScript.foreignLetters("Звезда по имени Солнце", "Кино", mixed))
        // two lines are too little to judge
        assertFalse(LyricsScript.foreignLetters("Звезда", "Кино", Lyrics.plain("La la", "LRCLIB")))
    }

    @Test
    fun aPerfectMatchInAnotherAlphabetIsNeverPickedByItself() {
        fun c(l: Lyrics, score: Double, foreign: Boolean) = LyricsFinder.Candidate(l, "Звезда по имени Солнце", "Кино", "", 0.0, score, "LRCLIB", foreign)
        val latin = Lyrics(listOf(LyricLine(0, text = "I dve tysyachi let voyna")), synced = true, source = "LRCLIB")
        val cyr = Lyrics(listOf(LyricLine(0, text = "И две тысячи лет война")), synced = true, source = "LRCLIB")
        val perfect = 1.0 - LyricsFinder.FOREIGN_LETTERS_PENALTY
        assertTrue(perfect < LyricsFinder.MIN_SCORE)
        assertEquals(null, LyricsFinder.pick(listOf(c(latin, perfect, true))))
        assertEquals(cyr, LyricsFinder.pick(listOf(c(latin, perfect, true), c(cyr, 0.9, false)))?.lyrics)
    }

    @Test
    fun theChorusIsTheBestMoment() {
        fun l(ms: Long, text: String) = LyricLine(ms, text = text)
        val song = Lyrics(
            listOf(
                l(10_000, "Белый снег, серый лёд"),
                l(14_000, "На растрескавшейся земле"),
                l(18_000, "♪"),
                l(40_000, "И две тысячи лет война"),
                l(44_000, "Война без особых причин"),
                l(48_000, "Война — дело молодых"),
                l(60_000, "Красная-красная кровь"),
                l(64_000, "Через час уже просто земля"),
                l(80_000, "И две тысячи лет война"),
                l(84_000, "Война без особых причин"),
                l(88_000, "Война - дело молодых!"),
            ),
            synced = true, offsetMs = 500,
        )
        assertEquals(40_500L, BestMoment.guess(song))
        // nothing is sung again: no mark
        assertEquals(null, BestMoment.guess(Lyrics(song.lines.take(7), synced = true)))
        // words without times: no mark either
        assertEquals(null, BestMoment.guess(song.copy(synced = false)))
    }
}
