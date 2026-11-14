package app.malachite.core.heal

import app.malachite.core.audio.AudioPayload
import app.malachite.core.audio.TagEdit
import app.malachite.core.audio.TagIO
import app.malachite.core.cover.CoverFinder
import app.malachite.core.fs.SafeFiles
import app.malachite.core.library.FolderHints
import app.malachite.core.library.ImportFilter
import app.malachite.core.library.LibraryScanner
import app.malachite.core.library.LibraryStore
import app.malachite.core.library.Playlist
import app.malachite.core.library.PlaylistEntry
import app.malachite.core.library.ZipTransfer
import app.malachite.core.lyrics.Lyrics
import app.malachite.core.lyrics.LyricsFinder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealTest {
    private lateinit var root: File
    private lateinit var safe: SafeFiles
    private lateinit var store: LibraryStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("main").toFile()
        TagIO.configure(false)
        store = LibraryStore(root).apply { ensureCreated("test") }
        safe = SafeFiles(store.dataDir).apply { protect(root, store.dataDir) }
    }

    @AfterTest
    fun tearDown() { root.deleteRecursively() }

    private fun song(rel: String, fixture: String, edit: TagEdit): File {
        val f = File(root, rel).apply { parentFile.mkdirs() }
        javaClass.getResourceAsStream("/audio/$fixture")!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        if (edit.isEmpty) return f
        val tmpSafe = SafeFiles(File(root, ".malachite"))
        assertTrue(tmpSafe.writeTags(f, edit).isOk)
        return f
    }

    private fun cover() = javaClass.getResourceAsStream("/audio/cover.jpg")!!.readBytes()

    @Test
    fun healsAMessyLibrary() {
        song("Downloads/a.mp3", "sine.mp3", TagEdit(title = "Подводная лодка", artist = "Свидетельство о смерти", album = "Всем смертям назло", artwork = TagEdit.Artwork(cover(), "image/jpeg")))
        song("x/b.mp3", "sine.mp3", TagEdit(title = "Другая песня (muzofond.fm)", artist = "Свидетельство о смерти", album = "Всемсмертямназло"))
        song("x/c.flac", "sine.flac", TagEdit(title = "Третья", artist = "Свидетельство о смерти", album = "Всем смертям назло"))
        song("y/d.mp3", "sine.mp3", TagEdit(title = "Песня", artist = "Svidetelstvo o smerti", album = "Всем смертям назло"))
        song("z/e.opus", "sine.opus", TagEdit(title = "latex fruit", artist = "𝗹𝗮𝘁𝗲𝘅 𝗳𝗿𝘂𝗶𝘁", album = "Single"))
        song("z/f.opus", "sine.opus", TagEdit(title = "latex fruit 2", artist = "latex fruit", album = "Single"))
        // MP3 disguised as WAV
        song("weird/g.wav", "actually_mp3.wav", TagEdit())

        val scanner = LibraryScanner(root)
        var tracks = scanner.scan(emptyList())
        assertEquals(7, tracks.size)
        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(tracks)

        // 1) formats
        val formats = HealPlanner.formats(tracks)
        assertEquals(listOf("weird/g.mp3"), formats.map { it.newPath })
        assertEquals(1, exec.applyFormats(formats).changed)
        tracks = exec.index.values.toList()

        // 2) names
        val names = HealPlanner.names(tracks)
        val albumFix = names.single { it.field == NameField.ALBUM && it.canonical == "Всем смертям назло" }
        assertTrue(albumFix.selected)
        val artistFix = names.single { it.field == NameField.ARTIST && it.canonical == "Свидетельство о смерти" }
        assertTrue(artistFix.variants.any { it.raw == "Svidetelstvo o smerti" })
        val latex = names.single { it.field == NameField.ARTIST && it.canonical == "latex fruit" }
        assertTrue(latex.selected)
        assertTrue(names.any { it.field == NameField.TITLE && it.canonical == "Другая песня" })
        val rep = exec.applyNames(names.map { it.copy(selected = true) })
        assertEquals(0, rep.failed, rep.log.joinToString("\n"))

        tracks = scanner.scan(emptyList())
        val paths = tracks.map { it.path }.toSet()
        assertTrue("Свидетельство о смерти/Всем смертям назло/Подводная лодка.mp3" in paths, paths.toString())
        assertTrue("Свидетельство о смерти/Всем смертям назло/Другая песня.mp3" in paths, paths.toString())
        assertTrue("Свидетельство о смерти/Всем смертям назло/Третья.flac" in paths, paths.toString())
        assertTrue("Свидетельство о смерти/Всем смертям назло/Песня.mp3" in paths, paths.toString())
        assertTrue("latex fruit/Single/latex fruit.opus" in paths, paths.toString())
        assertEquals(1, tracks.map { it.album }.filter { it.startsWith("Всем") }.distinct().size)
        assertFalse(File(root, "Downloads").exists(), "empty source folders are removed")

        // 3) covers: the album already has one cover → reused for the others without internet
        exec.load(tracks)
        val jobs = HealPlanner.covers(tracks).filter { it.donor != null }
        assertEquals(1, jobs.size)
        val cr = exec.applyCovers(jobs)
        assertEquals(4, cr.changed, cr.log.joinToString("\n")) // incl. the former ".wav"
        val coverFile = File(root, "Свидетельство о смерти/Всем смертям назло/Третья.flac")
        assertNotNull(TagIO.readArtwork(coverFile))

        // 4) duplicates: identical audio with different tags is found, the better copy kept
        tracks = scanner.scan(emptyList())
        val dups = HealPlanner.duplicates(tracks) { t -> AudioPayload.fingerprint(File(root, t.path)) }
        assertTrue(dups.any { it.identicalAudio && it.remove.isNotEmpty() })
    }

    @Test
    fun junkNamesAreRecognised() {
        for (j in listOf("2_3049492992", "001", "Track 01", "AUD-20240101-WA0003", "audio_2024-01-01_12-00-00", "3f9a1c0e77b2",
            "Неизвестный трек", "Без названия", "y7Hk2Pq09LmX3t8", "")) {
            assertTrue(HealPlanner.isJunkName(j), j)
        }
        for (ok in listOf("Подводная лодка", "Music", "Песня", "latex fruit", "Mr. Brightside", "1979", "Всем смертям назло", "Song 2")) {
            assertFalse(HealPlanner.isJunkName(ok), ok)
        }
    }

    @Test
    fun meaninglessFileNamesGetTheTitleFromInsideTheFile() {
        song("Свидетельство о смерти/Альбом/2_3049492992.mp3", "sine.mp3", TagEdit(title = "Подводная лодка", artist = "Свидетельство о смерти", album = "Альбом"))
        song("Свидетельство о смерти/Альбом/Подводная лодка.flac", "sine.flac", TagEdit(title = "Подводная лодка", artist = "Свидетельство о смерти", album = "Альбом"))
        song("x/Кино - Группа крови.mp3", "sine.mp3", TagEdit(title = "Track 01", artist = "Кино"))
        val tracks = LibraryScanner(root).scan(emptyList())
        val plan = HealPlanner.filenames(tracks)
        assertEquals(listOf("Свидетельство о смерти/Альбом/Подводная лодка.mp3"), plan.map { it.newPath })
        // junk title + good file name → the title is suggested from the file name instead
        val names = HealPlanner.names(tracks)
        assertTrue(names.any { it.field == NameField.TITLE && it.canonical == "Группа крови" }, names.toString())

        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(tracks)
        val r = exec.applyFilenames(plan)
        assertEquals(1, r.changed, r.log.joinToString())
        assertTrue(File(root, "Свидетельство о смерти/Альбом/Подводная лодка.mp3").isFile)
        assertFalse(File(root, "Свидетельство о смерти/Альбом/2_3049492992.mp3").exists())
        assertEquals(mapOf("Свидетельство о смерти/Альбом/2_3049492992.mp3" to "Свидетельство о смерти/Альбом/Подводная лодка.mp3"), r.moves)
    }

    @Test
    fun albumsAreReadFromFolders() {
        val h1 = FolderHints.fromPath("/storage/emulated/0/FINALALLMUSIC/Boris_Kuzmin/Время выткало нить/Закружилась пряжа.m4a", "Борис Кузьмин")!!
        assertEquals("Время выткало нить", h1.album); assertTrue(h1.confident)
        val h2 = FolderHints.fromPath("/storage/emulated/0/AllMusic/Свидетельство о смерти/Петля.mp3", "Свидетельство О Смерти")!!
        assertEquals(null, h2.album)
        assertEquals(null, FolderHints.fromPath("/storage/emulated/0/Download/Петля.mp3", "X"))
        assertEquals(null, FolderHints.fromPath("/storage/emulated/0/Telegram/Telegram Audio/Петля.mp3", "X"))
        val h3 = FolderHints.fromPath("/storage/emulated/0/Music/Свидетельство о смерти - Всем смертям назло/x.mp3", "Свидетельство о смерти")!!
        assertEquals("Всем смертям назло", h3.album); assertTrue(h3.confident)
        val h4 = FolderHints.fromPath("/storage/emulated/0/AllMusic/2005 - Всем смертям назло/x.mp3", "Кто-то")!!
        assertEquals("Всем смертям назло", h4.album); assertFalse(h4.confident)
        val h5 = FolderHints.fromPath("/storage/1A2B-3C4D/Music/Boris_Kuzmin/Время выткало нить (2019)/x.mp3", "")!!
        assertEquals("Boris Kuzmin", h5.artist); assertEquals("Время выткало нить", h5.album)
        // untagged single in "Artist/синглы 📀/"
        val h6 = FolderHints.fromPath("/storage/emulated/0/FINALALLMUSIC/Boris_Kuzmin/Вещие Ослы/синглы 📀/Будет лучше.mp3", "")!!
        assertEquals("Вещие Ослы", h6.artist); assertEquals(null, h6.album)
        assertEquals("Подводная лодка", FolderHints.titleFromFileName("Свидетельство О Смерти — Подводная лодка.m4a", "Свидетельство о смерти"))
        assertEquals("Когда я ответил да", FolderHints.titleFromFileName("Когда я ответил да (Sefon.Pro).mp3", "Свидетельство о смерти"))
    }

    @Test
    fun songsCollectedWithoutAlbumGetItBackAndMove() {
        song("Свидетельство о смерти/Без альбома/Петля.mp3", "sine.mp3", TagEdit(title = "Петля", artist = "Свидетельство О Смерти", album = ""))
        val t0 = LibraryScanner(root).scan(emptyList()).single()
        store.updateLibrary { it.update(t0.path) { u -> u.copy(importedFrom = "/storage/emulated/0/AllMusic/Свидетельство о смерти/Всем смертям назло/Петля.mp3") } }
        val plan = HealPlanner.missingTags(listOf(t0), store.loadLibrary()) { null }
        val sug = plan.single()
        assertEquals("Всем смертям назло", sug.album)
        assertTrue(sug.selected)
        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(listOf(t0))
        val r = exec.applyMissingTags(plan)
        assertEquals(1, r.changed, r.log.joinToString())
        val moved = File(root, "Свидетельство О Смерти/Всем смертям назло/Петля.mp3")
        assertTrue(moved.isFile, LibraryScanner(root).scan(emptyList()).map { it.path }.toString())
        assertEquals("Всем смертям назло", TagIO.read(moved)!!.album)
        // the favourite / lyrics link followed the file
        assertEquals("/storage/emulated/0/AllMusic/Свидетельство о смерти/Всем смертям назло/Петля.mp3",
            store.loadLibrary().user("Свидетельство О Смерти/Всем смертям назло/Петля.mp3").importedFrom)
    }

    @Test
    fun layoutFolderFillsEmptyAlbumAndSameTitleOnOtherAlbumIsNoDuplicate() {
        song("Кино/Группа крови/Звезда.mp3", "sine.mp3", TagEdit(title = "Звезда", artist = "Кино", album = ""))
        song("Кино/Последний герой/Звезда.flac", "sine.flac", TagEdit(title = "Звезда", artist = "Кино", album = "Последний герой"))
        val tracks = LibraryScanner(root).scan(emptyList())
        val a = tracks.single { it.path.startsWith("Кино/Группа крови") }
        assertEquals("Группа крови", a.album)
        assertEquals("album", a.inferred)
        // different albums -> different recordings, not "likely duplicates"
        val dups = HealPlanner.duplicates(tracks) { null }
        assertTrue(dups.isEmpty(), dups.toString())
        // the inferred album is offered for writing into the file
        assertEquals("Группа крови", HealPlanner.missingTags(tracks, store.loadLibrary()) { null }.single().album)
    }

    /** An ID3v2.3 tag (title "Wrong") as some apps glue it in front of M4A files they took for MP3. */
    private fun id3Header(): ByteArray {
        val text = "Wrong".toByteArray(Charsets.ISO_8859_1)
        val frame = ByteArrayOutputStream().apply {
            write("TIT2".toByteArray(Charsets.ISO_8859_1))
            val size = text.size + 1
            write(byteArrayOf(0, 0, 0, size.toByte(), 0, 0, 0)) // size + flags
            write(text)
        }.toByteArray()
        val body = frame + ByteArray(64) // padding
        val n = body.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
        )
        return header + body
    }

    @Test
    fun m4aWithGluedOnId3TagIsReadAndCleaned() {
        // the manual archive had "Моя Маленькая Дрянь.mp3": an M4A with an ID3 tag in front
        val plain = song("plain.m4a", "sine.m4a", TagEdit(title = "Дрянь", artist = "Свидетельство о смерти", album = "Дрянь"))
        val fp = AudioPayload.fingerprint(plain)
        assertNotNull(fp)
        val f = File(root, "Свидетельство о смерти/Синглы/Дрянь.mp3").apply { parentFile.mkdirs() }
        f.writeBytes(id3Header() + plain.readBytes())
        plain.delete()
        assertEquals(app.malachite.core.audio.AudioFormat.M4A, app.malachite.core.audio.AudioFormat.detect(f))
        assertTrue(TagIO.id3PrefixLength(f) > 0)
        assertEquals("Дрянь", TagIO.read(f)?.title) // was: "does not appear to be an Mp4 file"
        assertEquals(fp, AudioPayload.fingerprint(f)) // the glued-on tag is not music

        val tracks = LibraryScanner(root).scan(emptyList())
        assertEquals("Дрянь", tracks.single().title)
        val fixes = HealPlanner.formats(tracks)
        assertEquals("Свидетельство о смерти/Синглы/Дрянь.m4a", fixes.single().newPath)
        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(tracks)
        val r = exec.applyFormats(fixes)
        assertEquals(1, r.changed, r.log.joinToString())
        val fixed = File(root, "Свидетельство о смерти/Синглы/Дрянь.m4a")
        assertEquals(0L, TagIO.id3PrefixLength(fixed), r.log.joinToString())
        assertEquals("Дрянь", TagIO.read(fixed)?.title)
        assertEquals(fp, AudioPayload.fingerprint(fixed))

        // writing tags into a prefixed file also leaves a clean M4A
        val g = File(root, "x/g.m4a").apply { parentFile.mkdirs() }
        g.writeBytes(id3Header() + fixed.readBytes())
        assertTrue(safe.writeTags(g, TagEdit(album = "Новый")).isOk)
        assertEquals(0L, TagIO.id3PrefixLength(g))
        assertEquals("Новый", TagIO.read(g)?.album)
        assertEquals(fp, AudioPayload.fingerprint(g))
    }

    @Test
    fun releaseYearFromYoutubeDescriptionAndAlbumYear() {
        val a = song("Вещие Ослы/Там/Там.m4a", "sine.m4a", TagEdit(title = "Там", artist = "Вещие Ослы", album = "Там", year = "20250227"))
        val af = org.jaudiotagger.audio.AudioFileIO.read(a)
        val mp4 = af.tag as org.jaudiotagger.tag.mp4.Mp4Tag
        mp4.setField(
            org.jaudiotagger.tag.mp4.Mp4FieldKey.DESCRIPTION,
            "Provided to YouTube by Believe\n\nТам · Вещие Ослы\n\n℗ 2019\n\nReleased on: 2019-03-01\n\nAuto-generated by YouTube.",
        )
        af.commit()
        assertEquals("2019", TagIO.read(a)?.releaseYear)
        // (m4a keeps "20251118" as it is, like the downloaded files; ID3v2.3 would split it into TYER/TDAT)
        song("Вещие Ослы/Безнадёжность/Ботинки.m4a", "sine.m4a", TagEdit(title = "Ботинки", artist = "Вещие Ослы", album = "Безнадёжность", year = "2022"))
        song("Вещие Ослы/Безнадёжность/Никак.m4a", "sine.m4a", TagEdit(title = "Никак", artist = "Вещие Ослы", album = "Безнадёжность", year = "20251118"))
        song("Вещие Ослы/Безнадёжность/Ночь.m4a", "sine.m4a", TagEdit(title = "Ночь", artist = "Вещие Ослы", album = "Безнадёжность", year = "2022"))

        val tracks = LibraryScanner(root).scan(emptyList())
        val tam = tracks.single { it.title == "Там" }
        assertEquals("2019", tam.year) // not the download date
        assertTrue("year" in tam.inferred.split(','))

        val plan = HealPlanner.missingTags(tracks, store.loadLibrary()) { null }
        assertEquals(mapOf("Там" to "2019", "Никак" to "2022"), plan.associate { s -> tracks.single { it.path == s.path }.title to s.year })
        assertTrue(plan.all { it.selected && it.album == null && it.artist == null }, plan.toString())

        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(tracks)
        val r = exec.applyMissingTags(plan)
        assertEquals(2, r.changed, r.log.joinToString())
        val after = LibraryScanner(root).scan(emptyList())
        assertEquals("2019", TagIO.read(File(root, after.single { it.title == "Там" }.path))?.year)
        assertEquals("2022", TagIO.read(File(root, after.single { it.title == "Никак" }.path))?.year)
        assertTrue(after.single { it.title == "Никак" }.path.startsWith("Вещие Ослы/Безнадёжность (2022)/"), after.map { it.path }.toString())
        // a real year is never replaced
        assertTrue(HealPlanner.missingTags(after, store.loadLibrary()) { null }.none { it.year != null })
    }

    @Test
    fun coverFromTheLinkInsideTheFile() {
        val f = CoverFinder()
        for (link in listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share",
            "Downloaded from https://www.youtube.com/watch?list=PL123&v=dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
        )) {
            val c = assertNotNull(f.youtubeFromLink(link, "a", "b"), link)
            assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", c.imageUrl)
            assertTrue(c.square && c.score >= CoverFinder.AUTO_THRESHOLD)
        }
        assertEquals(null, f.youtubeFromLink("", "a", "b"))
        assertEquals(null, f.youtubeFromLink("https://example.com/watch?v=short", "a", "b"))
    }

    @Test
    fun layoutLikeTheManualLibrary() {
        fun t(p: String, title: String, artist: String, album: String, year: String = "", fmt: String = "MP3") =
            app.malachite.core.library.TrackInfo(path = p, title = title, artist = artist, album = album, year = year, format = fmt)
        val ts = listOf(
            t("a.mp3", "Ботинки", "Вещие Ослы", "Безнадёжность", "2022"),
            t("b.mp3", "Никак", "Вещие Ослы", "Безнадёжность", "20251118"),
            t("c.mp3", "Там", "Вещие Ослы", "Там", "20230601"),
            t("d.mp3", "Апокалипсис", "Вещие Ослы", "Там"),
            t("e.m4a", "обосранный герой", "мой друг магнитофон, свидетельство о смерти, Никита Савра, Борис Кузьмин", "обосранный герой", "20201118", "M4A"),
            t("f.mp3", "Петля", "Свидетельство О Смерти", "Привет", "2001"),
            t("g.mp3", "Будет лучше", "Вещие Ослы", ""),
            t("h.mp3", "Привет", "Свидетельство О Смерти", "Привет", "2001"),
            t("i.mp3", "Ботинки", "Âåùèå Îñëû", "Áåçíàä¸æíîñòü", "2022"),
        )
        val ctx = app.malachite.core.library.LibraryLayout.Context(ts)
        val o = app.malachite.core.library.LibraryLayout.Options()
        fun path(i: Int) = app.malachite.core.library.LibraryLayout.expectedPath(ts[i], o, ctx)
        assertEquals("Вещие Ослы/Безнадёжность (2022)/Никак.mp3", path(1)) // year from the other songs of the album
        assertEquals("Вещие Ослы/Там/Там.mp3", path(2)) // title track of a real album, no reliable year
        assertEquals("Свидетельство О Смерти/Синглы/обосранный герой.m4a", path(4)) // known artist + single
        assertEquals("Вещие Ослы/Синглы/Будет лучше.mp3", path(6))
        assertEquals("Свидетельство О Смерти/Привет (2001)/Привет.mp3", path(7))
        assertEquals("Вещие Ослы/Безнадёжность (2022)/Ботинки.mp3", path(8)) // mojibake tags
        assertEquals("Безнадёжность", app.malachite.core.library.LibraryLayout.albumFromFolder("Безнадёжность (2022)"))
        assertEquals(null, app.malachite.core.library.LibraryLayout.albumFromFolder("Синглы"))
    }

    @Test
    fun artistInsideTitleAndFolderCovers() {
        song("Свидетельство О Смерти/Привет (2001)/x.m4a", "sine.m4a", TagEdit(title = "Свидетельство О Смерти — Подводная лодка", artist = "Свидетельство О Смерти", album = "Привет"))
        val img = File(root, "Свидетельство О Смерти/Привет (2001)/1bb3c748e6357881296c46797c0ffe7f.1000x1000x1.jpg")
        val bytes = cover() + ByteArray(9000)
        img.writeBytes(bytes)
        val tracks = LibraryScanner(root).scan(emptyList())
        val names = HealPlanner.names(tracks)
        val fix = names.single { it.field == NameField.TITLE }
        assertEquals("Подводная лодка", fix.canonical)
        assertTrue(fix.selected)
        val jobs = HealPlanner.covers(tracks, root, store.loadLibrary())
        assertEquals(img.absolutePath, jobs.single().imageFile)
        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(tracks)
        assertEquals(1, exec.applyCovers(jobs).changed)
        assertTrue(TagIO.readArtwork(File(root, tracks.single().path))!!.contentEquals(bytes))
    }

    @Test
    fun importFilterRejectsVoiceAndSystemSounds() {
        val rules = ImportFilter.Rules()
        fun v(p: String) = ImportFilter.checkPath(p, rules, "/storage/emulated/0/Malachite")
        assertEquals(ImportFilter.Verdict.VOICE_MESSAGE, v("/storage/emulated/0/Telegram/Telegram Voice/123.ogg"))
        assertEquals(ImportFilter.Verdict.VOICE_MESSAGE, v("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202401/PTT-20240101-WA0001.opus"))
        assertEquals(ImportFilter.Verdict.SYSTEM_SOUND, v("/storage/emulated/0/Notifications/ding.mp3"))
        assertEquals(ImportFilter.Verdict.SYSTEM_SOUND, v("/system/media/audio/ringtones/a.ogg"))
        assertEquals(ImportFilter.Verdict.RECORDING, v("/storage/emulated/0/MIUI/sound_recorder/call_rec/x.mp3"))
        // from messenger folders only songs are taken by default (the rest is mostly clutter), all audio can be enabled
        val tg = "/storage/emulated/0/Android/media/org.telegram.messenger/Telegram/Telegram Audio/"
        fun full(path: String, tags: app.malachite.core.audio.TagData?, r: ImportFilter.Rules = rules) =
            ImportFilter.check(File(path), app.malachite.core.audio.AudioFormat.MP3, tags, r, "/storage/emulated/0/Malachite")
        val song = app.malachite.core.audio.TagData(title = "Тестостерон", artist = "НАВЕРНОЕ ПОЭТ, ЯКОРЪ, EVEN CUTE", durationMs = 114_000)
        val clip = app.malachite.core.audio.TagData(durationMs = 114_000)
        assertEquals(ImportFilter.Verdict.MUSIC, v(tg + "track.mp3")) // the path alone cannot tell
        assertEquals(ImportFilter.Verdict.MUSIC, full(tg + "track.mp3", song))
        assertEquals(ImportFilter.Verdict.MUSIC, full("/storage/emulated/0/Telegram/x.mp3", song))
        assertEquals(ImportFilter.Verdict.MUSIC, full(tg + "Кино - Группа крови.mp3", clip))
        assertEquals(ImportFilter.Verdict.MUSIC, full(tg + "XIVIVIDE_-_Trolit_Menya_Vova_KH.mp3", clip))
        assertEquals(ImportFilter.Verdict.MESSENGER, full(tg + "track.mp3", clip))
        assertEquals(ImportFilter.Verdict.MESSENGER, full(tg + "Кино - Группа крови.mp3", song.copy(durationMs = 45_000)))
        assertEquals(ImportFilter.Verdict.MUSIC, full(tg + "track.mp3", clip, rules.copy(importFromMessengers = true)))
        // songs saved from Telegram on purpose ("Сохранить в музыку" / "в загрузки") are music, tags or not
        assertEquals(ImportFilter.Verdict.MUSIC, full("/storage/emulated/0/Music/Telegram/track.mp3", clip))
        assertEquals(ImportFilter.Verdict.MUSIC, full("/storage/emulated/0/Download/Telegram/track.mp3", clip))
        assertEquals(ImportFilter.Verdict.MUSIC, ImportFilter.check(File("/storage/emulated/0/Telegram/Telegram Documents/a.flac"),
            app.malachite.core.audio.AudioFormat.FLAC, app.malachite.core.audio.TagData(title = "Звезда", artist = "Кино", durationMs = 200_000), rules, null))
        // messenger cache names anywhere
        assertEquals(ImportFilter.Verdict.MESSENGER, v("/storage/emulated/0/Download/4_5913724166165551837@kndved.ogg"))
        assertEquals(ImportFilter.Verdict.MESSENGER, v("/storage/emulated/0/Music/2_5285074063658627085@kemtej.m4a"))
        assertEquals(ImportFilter.Verdict.MESSENGER, v("/storage/emulated/0/Music/a0994bcb23ba48fda0991980948ee769@abc.mp3"))
        assertEquals(ImportFilter.Verdict.MUSIC, v("/storage/emulated/0/Download/song.mp3"))
        assertEquals(ImportFilter.Verdict.EXCLUDED_FOLDER, v("/storage/emulated/0/Malachite/A/B/c.mp3"))
    }

    @Test
    fun videosAreNotMusic() {
        val video = File(root, "v.mp4").also { f -> javaClass.getResourceAsStream("/audio/video.mp4")!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
        val audio = File(root, "a.m4a").also { f -> javaClass.getResourceAsStream("/audio/sine.m4a")!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
        val withPic = File(root, "p.m4a").also { f -> javaClass.getResourceAsStream("/audio/audio_with_pic.m4a")!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
        assertTrue(app.malachite.core.audio.Mp4Probe.isVideo(video))
        assertFalse(app.malachite.core.audio.Mp4Probe.isVideo(audio))
        assertFalse(app.malachite.core.audio.Mp4Probe.isVideo(withPic))
        assertEquals(-1, app.malachite.core.audio.Mp4Probe.videoFrames(File(root, "nope.mp3").also { it.writeBytes(ByteArray(100)) }))
    }

    @Test
    fun junkInMainFolderIsFound() {
        song("A/B/v.m4a", "video.mp4", TagEdit())
        song("A/B/4_5913724166165551837@kndved.mp3", "sine.mp3", TagEdit(title = "", artist = ""))
        song("Кино/Группа крови/Звезда.mp3", "sine.mp3", TagEdit(title = "Звезда", artist = "Кино"))
        val tracks = LibraryScanner(root).scan(emptyList())
        val junk = HealPlanner.junk(tracks, store.loadLibrary(), ImportFilter.Rules()) { t -> app.malachite.core.audio.Mp4Probe.isVideo(File(root, t.path)) }
        assertEquals(setOf("A/B/v.m4a", "A/B/4_5913724166165551837@kndved.mp3"), junk.filter { it.selected }.map { it.path }.toSet())
        val exec = HealExecutor(root, safe, store, CoverFinder(), LyricsFinder(), ImagePrep { TagEdit.Artwork(it, "image/jpeg") })
        exec.load(tracks)
        assertEquals(2, exec.applyJunk(junk).changed)
        assertEquals(listOf("Кино/Группа крови/Звезда.mp3"), LibraryScanner(root).scan(emptyList()).map { it.path })
    }

    @Test
    fun parallelScanReadsTheSameAsSequential() {
        repeat(40) { i ->
            song("X/Y/s$i.mp3", "sine.mp3", TagEdit()); song("X/Y/f$i.flac", "sine.flac", TagEdit())
            song("X/Y/o$i.opus", "sine.opus", TagEdit()); song("X/Y/m$i.m4a", "sine.m4a", TagEdit())
        }
        val seq = LibraryScanner(root).scan(emptyList(), threads = 1).sortedBy { it.path }
        val par = LibraryScanner(root).scan(emptyList(), threads = 6).sortedBy { it.path }
        assertEquals(seq, par)
        assertTrue(seq.all { it.durationMs > 0 })
    }

    @Test
    fun zipRoundTripKeepsBytesAndData() {
        song("Artist/Album/Song.mp3", "sine.mp3", TagEdit())
        val t = LibraryScanner(root).scan(emptyList()).single()
        store.saveLyrics(t, Lyrics.plain("ля-ля"), "test")
        store.savePlaylist(Playlist("Любимое", 1L, entries = listOf(PlaylistEntry(1, "", t.path))), mapOf(t.path to t))
        File(store.dataDir, "backup/x").apply { mkdirs(); File(this, "big.bin").writeBytes(ByteArray(10)) }
        val out = ByteArrayOutputStream()
        ZipTransfer.export(root, out)
        val dest = Files.createTempDirectory("dest").toFile()
        try {
            val newRoot = ZipTransfer.import(ByteArrayInputStream(out.toByteArray()), dest)!!
            assertTrue(LibraryStore.isLibraryRoot(newRoot))
            assertTrue(File(newRoot, "Artist/Album/Song.mp3").readBytes().contentEquals(File(root, "Artist/Album/Song.mp3").readBytes()))
            assertFalse(File(newRoot, ".malachite/backup").exists(), "backups are not exported")
            val s2 = LibraryStore(newRoot)
            assertEquals("ля-ля", s2.loadLyrics(t)!!.plainText)
            assertEquals(t.path, s2.listPlaylists().single().entries.single().relativePath)
        } finally {
            dest.deleteRecursively()
        }
    }

    @Test
    fun movesRemapUserData() {
        song("A/B/s.mp3", "sine.mp3", TagEdit())
        val t = LibraryScanner(root).scan(emptyList()).single()
        store.updateLibrary { it.update(t.path) { u -> u.copy(favorite = true) } }
        store.savePlaylist(Playlist("P", 1L, entries = listOf(PlaylistEntry(1, "", t.path))), mapOf(t.path to t))
        store.applyMoves(mapOf(t.path to "X/Y/s.mp3"), emptyMap())
        assertTrue(store.loadLibrary().user("X/Y/s.mp3").favorite)
        assertEquals("X/Y/s.mp3", store.listPlaylists().single().entries.single().relativePath)
    }
}
