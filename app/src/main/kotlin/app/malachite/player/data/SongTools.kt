package app.malachite.player.data

import app.malachite.core.i18n.tr
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.malachite.core.audio.AudioFormat
import app.malachite.core.audio.TagEdit
import app.malachite.core.audio.TagIO
import app.malachite.core.fs.SafeFiles
import app.malachite.core.library.ImportFilter
import app.malachite.core.library.LibraryScanner
import app.malachite.player.MalachiteApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Songs added by hand – by a path typed in, or files picked in the phone's file manager – and
 * songs turned into another format. Nothing is ever lost: an added file is copied (the original
 * stays), and a converted song replaces the old one only after the result has been checked.
 */
object SongTools {
    /** A song chosen for converting: a file of the library ([libraryPath]) or one from elsewhere. */
    data class Picked(val file: File, val name: String, val libraryPath: String?, val temporary: Boolean = false)

    private val g get() = MalachiteApp.graph

    /**
     * "/storage/emulated/0/Download/x.mp3", "/sdcard/Download/x.mp3", "Download/x.mp3",
     * "file:///storage/…", with or without quotes: all lead to the same file.
     */
    fun resolve(input: String): File {
        var t = input.trim().trim('"', '\'', '«', '»').trim()
        if (t.startsWith("file://")) t = Uri.decode(t.removePrefix("file://"))
        if (t.startsWith("~/")) t = t.removePrefix("~/")
        if (t.startsWith("/storage/self/primary")) t = "/storage/emulated/0" + t.removePrefix("/storage/self/primary")
        return Storage.resolveUserPath(t)
    }

    /** Where music usually is on this phone (shown as hints): internal storage, Download, SD cards. */
    fun knownPlaces(context: Context): List<String> {
        val primary = Storage.primaryRoot().absolutePath
        val out = mutableListOf("$primary/", "$primary/Download/", "$primary/Music/")
        Storage.roots(context).map { it.absolutePath }.filter { it != primary }.forEach { out += "$it/" }
        return out
    }

    /** Adds the song at [input] (or every song in that folder) to the main folder. Returns what happened. */
    suspend fun addPath(context: Context, input: String): String = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext tr("Впишите путь к песне или к папке")
        val f = resolve(input)
        if (!Storage.hasAllFilesAccess(context)) return@withContext tr("Нет «Доступа ко всем файлам»: без него плеер не может читать файлы по пути. Выдайте его или выберите файл кнопкой «Выбрать файлы»")
        if (!f.exists()) return@withContext tr("По пути ${f.absolutePath} ничего нет. Проверьте буквы и регистр: «Download» и «download» — разные папки")
        val root = g.library.root ?: return@withContext tr("Главная папка не открыта")
        if (app.malachite.core.fs.PathGuard.inside(root, f)) return@withContext tr("Это уже в главной папке — песни оттуда видны во вкладке «Песни»")
        val files = if (f.isDirectory) {
            f.walkTopDown().maxDepth(4).filter { it.isFile && LibraryScanner.isAudioName(it) }.take(5000).toList()
        } else listOf(f)
        if (files.isEmpty()) return@withContext tr("В папке ${f.absolutePath} нет песен")
        add(files.map { it to it.absolutePath })
    }

    /** Adds files picked in the system's file manager: each is copied out first, then added. */
    suspend fun addUris(context: Context, uris: List<Uri>): String = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext ""
        val dir = File(context.cacheDir, "picked").apply { mkdirs() }
        val copies = ArrayList<Pair<File, String>>()
        val problems = ArrayList<String>()
        try {
            for (uri in uris) {
                val name = displayName(context, uri) ?: "song"
                val copy = File(File(dir, UUID.randomUUID().toString()).apply { mkdirs() }, safeName(name))
                val ok = runCatching { context.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { i.copyTo(it) } } }.getOrNull()
                if (ok == null || !copy.isFile) problems += tr("$name: не удалось прочитать") else copies += copy to name
            }
            val result = if (copies.isEmpty()) "" else add(copies)
            (listOf(result) + problems).filter { it.isNotBlank() }.joinToString("\n")
        } finally {
            dir.deleteRecursively()
        }
    }

    private suspend fun add(files: List<Pair<File, String>>): String {
        val candidates = ArrayList<DeviceScanner.Candidate>()
        var notAudio = 0
        for ((file, original) in files) {
            val format = AudioFormat.detect(file)
            if (format == AudioFormat.UNKNOWN) { notAudio++; continue }
            val tags = TagIO.read(file, format)
            candidates += DeviceScanner.Candidate(file, g.deviceScanner.describe(file, original, format, tags), ImportFilter.Verdict.MUSIC)
        }
        if (candidates.isEmpty()) return if (notAudio == 1) tr("Это не аудиофайл (или формат, который Android не читает)") else tr("Среди файлов нет аудио")
        val r = g.deviceScanner.import(candidates)
        g.library.rescan()
        val parts = ArrayList<String>()
        parts += tr("Добавлено в главную папку: ${r.imported}")
        if (r.skipped > 0) parts += tr("уже были в библиотеке: ${r.skipped}")
        if (r.failed > 0) parts += tr("не получилось: ${r.failed}")
        if (notAudio > 0) parts += tr("не аудио: $notAudio")
        return parts.joinToString(", ") + if (r.errors.isNotEmpty()) "\n" + r.errors.take(3).joinToString("\n") else ""
    }

    /** A picked file copied out of the file manager, to be converted. */
    suspend fun pickForConverting(context: Context, uri: Uri): Picked? = withContext(Dispatchers.IO) {
        // the copy picked before is not needed any more
        File(context.cacheDir, "convert-in").deleteRecursively()
        val name = displayName(context, uri) ?: "song"
        val copy = File(File(context.cacheDir, "convert-in/" + UUID.randomUUID()).apply { mkdirs() }, safeName(name))
        val ok = runCatching { context.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { o -> i.copyTo(o) } } }.getOrNull()
        if (ok == null || !copy.isFile) null else Picked(copy, name, null, temporary = true)
    }

    /** A song of the library or a file at a path, to be converted. */
    fun pick(path: String): Picked? {
        val root = g.library.root
        g.library.track(path)?.let { t -> return Picked(File(root, t.path), "${t.displayArtist} — ${t.displayTitle}", t.path) }
        val f = resolve(path)
        if (!f.isFile) return null
        val rel = root?.let { SafeFiles.relativeOrNull(f, it) }
        return Picked(f, f.name, rel?.takeIf { g.library.track(it) != null })
    }

    /** "MP3 · 320 кбит/с · 44.1 кГц · 3:45", and what the file really is when its name says otherwise. */
    fun describe(p: Picked): String {
        val format = AudioFormat.detect(p.file)
        val t = TagIO.read(p.file, format)
        val parts = ArrayList<String>()
        parts += if (format == AudioFormat.UNKNOWN) tr("неизвестный формат") else format.name.replace('_', ' ')
        if ((t?.bitrateKbps ?: 0) > 0) parts += tr("${t!!.bitrateKbps} кбит/с")
        if ((t?.sampleRate ?: 0) > 0) parts += tr("%.1f кГц").format(t!!.sampleRate / 1000f)
        if ((t?.durationMs ?: 0) > 0) parts += app.malachite.player.ui.components.formatTime(t!!.durationMs)
        var text = parts.joinToString(" · ")
        if (wrongExtension(p) != null) text += " — " + tr("расширение .${p.file.extension} не совпадает с настоящим форматом")
        return text
    }

    /** The extension the file should have, when its name lies about its format. */
    fun wrongExtension(p: Picked): String? {
        val format = AudioFormat.detect(p.file)
        if (format == AudioFormat.UNKNOWN || AudioFormat.extensionMatches(p.file.extension, format)) return null
        return format.extension
    }

    /** Gives the file the extension of its real format: nothing in the sound changes. */
    suspend fun fixExtension(p: Picked): String = withContext(Dispatchers.IO) {
        val ext = wrongExtension(p) ?: return@withContext tr("Расширение и так правильное")
        val root = g.library.root
        val safe = g.library.safe ?: return@withContext tr("Главная папка не открыта")
        val dst = File(p.file.parentFile, p.file.nameWithoutExtension + "." + ext)
        when (val res = safe.move(p.file, dst)) {
            is SafeFiles.Result.Ok -> {
                if (p.libraryPath != null && root != null) {
                    g.library.swapFile(p.libraryPath, res.file)?.let { g.player.remapQueue(mapOf(p.libraryPath to it)) }
                } else if (p.temporary) {
                    // a picked copy: the fixed file goes into the main folder
                    return@withContext add(listOf(res.file to p.name))
                }
                tr("Готово: теперь файл называется ${res.file.name}")
            }
            is SafeFiles.Result.Skipped -> res.reason
            is SafeFiles.Result.Failed -> tr("Не получилось переименовать: ${res.reason}")
        }
    }

    /**
     * Converts the song. A song of the library is replaced by the converted file (the old one goes
     * to the main folder's trash, from where it can be put back); a file from elsewhere stays where
     * it is and the converted copy is added to the main folder. Returns what happened; nothing is
     * changed when anything fails.
     */
    suspend fun convert(
        context: Context, p: Picked, target: Converter.Target,
        /** Asked as the work goes on: true stops it, and the original stays as it was. */
        stopped: () -> Boolean = { false },
        progress: (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        val root = g.library.root ?: return@withContext Result.failure(Converter.Failed(tr("Главная папка не открыта")))
        val safe = g.library.safe ?: return@withContext Result.failure(Converter.Failed(tr("Главная папка не открыта")))
        val src = p.file
        if (!src.isFile) return@withContext Result.failure(Converter.Failed(tr("Файл не найден: ${src.absolutePath}")))
        val work = File(File(context.cacheDir, "convert-out/" + UUID.randomUUID()).apply { mkdirs() }, src.nameWithoutExtension.ifBlank { "song" } + "." + target.extension)
        try {
            val before = Converter.durationUs(src)
            Converter.convert(src, work, target) { f ->
                if (stopped()) throw Converter.Stopped()
                progress(f)
            }
            Converter.check(work, before)?.let { return@withContext Result.failure(Converter.Failed(it)) }
            // the words and the picture of the song come along
            val tags = TagIO.read(src)
            val art = runCatching { TagIO.readArtwork(src) }.getOrNull()
            val edit = TagEdit(
                title = tags?.title?.takeIf { it.isNotBlank() }, artist = tags?.artist?.takeIf { it.isNotBlank() },
                album = tags?.album?.takeIf { it.isNotBlank() }, albumArtist = tags?.albumArtist?.takeIf { it.isNotBlank() },
                year = tags?.year?.takeIf { it.isNotBlank() }, genre = tags?.genre?.takeIf { it.isNotBlank() },
                track = tags?.track?.takeIf { it.isNotBlank() }, lyrics = tags?.lyrics?.takeIf { it.isNotBlank() },
                artwork = art?.let { TagEdit.Artwork(it, imageMime(it)) },
            )
            if (p.libraryPath == null) {
                // from elsewhere: tagged on the side, then added like a picked file
                if (!edit.isEmpty) safe.writeTags(work, edit, null)
                return@withContext Result.success(add(listOf(work to p.name)))
            }
            val placed = when (val res = safe.copyInto(work, File(src.parentFile, src.nameWithoutExtension + "." + target.extension))) {
                is SafeFiles.Result.Ok -> res.file
                is SafeFiles.Result.Skipped -> return@withContext Result.failure(Converter.Failed(res.reason))
                is SafeFiles.Result.Failed -> return@withContext Result.failure(Converter.Failed(tr("Не удалось положить новый файл: ${res.reason}")))
            }
            var note = ""
            if (!edit.isEmpty) {
                val tagged = safe.writeTags(placed, edit, root)
                if (tagged is SafeFiles.Result.Failed) note = " " + tr("Теги перенести не удалось: ${tagged.reason}.")
            }
            // the old file goes to the trash only now, when the new one is in place
            val trashed = safe.trash(src, root)
            if (trashed is SafeFiles.Result.Failed) note += " " + tr("Старый файл остался рядом: ${trashed.reason}.")
            val newPath = g.library.swapFile(p.libraryPath, placed)
            if (newPath != null) g.player.remapQueue(mapOf(p.libraryPath to newPath))
            Result.success(tr("Готово: ${placed.name}. Старый файл — в корзине главной папки (Настройки → Перенос и копии).") + note)
        } catch (e: Converter.Failed) {
            Result.failure(e)
        } catch (e: Converter.Stopped) {
            Result.failure(Converter.Failed(tr("Остановлено. Исходный файл не тронут.")))
        } catch (e: Exception) {
            Result.failure(Converter.Failed(tr("Перевод прервался: ${e.message ?: e.javaClass.simpleName}")))
        } finally {
            work.parentFile?.deleteRecursively()
        }
    }

    private fun imageMime(b: ByteArray) =
        if (b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"

    private fun safeName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|\\u0000]"), "_").take(150).ifBlank { "song" }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}
