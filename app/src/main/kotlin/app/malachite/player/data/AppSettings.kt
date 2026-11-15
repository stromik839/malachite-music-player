package app.malachite.player.data

import app.malachite.core.i18n.tr
import android.content.Context
import app.malachite.core.library.ImportFilter
import app.malachite.core.library.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class ThemeMode { DARK, AMOLED, LIGHT, SYSTEM }

/** Built-in accent colours: the default malachite green plus the rainbow. */
@Serializable
enum class AccentPreset(val title: String, val argb: Long) {
    MALACHITE(tr("Малахит (по умолчанию)"), 0xFF2E8B57),
    RED(tr("Красный"), 0xFFE53935),
    ORANGE(tr("Оранжевый"), 0xFFFB8C00),
    YELLOW(tr("Жёлтый"), 0xFFFDD835),
    GREEN(tr("Зелёный"), 0xFF43A047),
    CYAN(tr("Голубой"), 0xFF29B6F6),
    BLUE(tr("Синий"), 0xFF1E88E5),
    VIOLET(tr("Фиолетовый"), 0xFF8E24AA),
    CUSTOM(tr("Свой цвет"), 0xFF2E8B57),
}

@Serializable
enum class AlbumSort(val title: String) {
    TITLE(tr("По названию")), ARTIST(tr("По исполнителю")), YEAR(tr("По году")), SIZE(tr("По числу песен")), RECENT(tr("Недавно добавленные")),
}

@Serializable
enum class PlaylistSort(val title: String) {
    NAME(tr("По названию")), CREATED(tr("По дате создания")), UPDATED(tr("Недавно изменённые")), SIZE(tr("По числу песен")),
}

/** How a favourite lyric line is marked. */
@Serializable
enum class LineMarkStyle(val title: String) {
    UNDERLINE(tr("Подчёркивание")), MARKER(tr("Маркер")), BOTH(tr("Маркер и подчёркивание")),
}

/** Which colour the icon on the home screen has. */
@Serializable
enum class IconColor(val title: String) {
    DEFAULT(tr("Малахит (обычный)")),
    ACCENT(tr("Как цвет акцента плеера")),
    SYSTEM(tr("Как цвета телефона")),
}

@Serializable
enum class FontChoice(val title: String) { SYSTEM(tr("Системный")), SANS(tr("Без засечек")), SERIF(tr("С засечками")), MONO(tr("Моноширинный")), CURSIVE(tr("Рукописный")), CUSTOM(tr("Свой файл шрифта")) }

@Serializable
enum class NavPosition(val title: String) { BOTTOM(tr("Снизу")), TOP(tr("Сверху")), LEFT(tr("Слева")), RIGHT(tr("Справа")) }

@Serializable
enum class LyricsAlign(val title: String) { START(tr("Слева")), CENTER(tr("По центру")), END(tr("Справа")) }

/** How a sung word of word-synced lyrics gets its colour. */
enum class WordFill(val title: String) {
    INSTANT(tr("Сразу")), FAST(tr("Быстро")), SMOOTH(tr("Плавно, пока слово поётся")),

    /** The sung part runs through the letters with a soft edge while the word is sung. */
    SWEEP(tr("По буквам, с мягким краем")),
}

/** How a line looks once it has been sung (and the lines around the one being sung). */
/** How the pill of the tab panel glides to a tab. */
enum class TabSlideAnim(val title: String) {
    SPRING(tr("Пружина")), SMOOTH(tr("Плавно")), FAST(tr("Быстро")), NONE(tr("Без анимации")),
}

enum class LyricsPass(val title: String) {
    /** Smaller and dimmer than the line being sung. */
    SHRINK(tr("Уменьшается и тускнеет")),
    /** Keeps its size and simply moves on up. */
    SLIDE(tr("Просто уезжает")),
    /** Fades away more than the lines still to come. */
    FADE(tr("Тает")),
    /** Blurs the further it is from the line being sung (Android 12 and newer; else it dims). */
    BLUR(tr("Размывается")),
}

/** How the visualizer on the song screen looks. */
enum class VisualizerStyle(val title: String) {
    /** Bars with a glow, a falling cap over each and a reflection. */
    BARS(tr("Полосы")),
    /** Lit segments like the meter of a mixing desk. */
    LEDS(tr("Светодиоды")),
    /** A smooth wave mirrored around its middle. */
    WAVE(tr("Волна")),
    /** An area graph: hills of the spectrum standing on the floor. */
    MOUNTAINS(tr("Горы")),
    /** Round lights in columns, like a matrix display. */
    DOTS(tr("Точки")),
    /** The sound wave itself, as on an oscilloscope (not the spectrum). */
    SCOPE(tr("Осциллограф")),
    /** The loudness of the last seconds running across: every beat a spike. */
    PULSE(tr("Пульс")),
}

/** In which order the bands stand in the visualizer. */
enum class VisualizerOrder(val title: String) {
    FREQUENCY(tr("Басы слева, высокие справа")),
    REVERSED(tr("Басы справа, высокие слева")),
    /** The bass in the middle, the highs towards both edges (each band twice). */
    MIRROR(tr("Басы в центре, высокие по краям")),
    /** By strength at this moment: the loudest in the middle, quieter towards the edges. */
    STRONG_CENTER(tr("Сильные в центре, слабые по краям")),
    STRONG_LEFT(tr("Сильные слева, слабые справа")),
    STRONG_RIGHT(tr("Сильные справа, слабые слева")),
}

/** Speed of scrolling and of the current-line highlight. */
enum class LyricsAnim(val title: String, val ms: Int) { OFF(tr("Без анимации"), 0), FAST(tr("Быстро"), 280), NORMAL(tr("Обычно"), 600), SLOW(tr("Медленно"), 1000) }

/** Tabs that can be shown in the navigation, in the user's order. */
@Serializable
enum class Tab(val title: String) {
    SONGS(tr("Песни")), ALBUMS(tr("Альбомы")), ARTISTS(tr("Исполнители")), PLAYLISTS(tr("Плейлисты")),
    FAVORITES(tr("Избранное")), FOLDERS(tr("Папки")), HEAL(tr("Исцеление")), SEARCH(tr("Поиск")),

    /** Exists only while the online section is switched on in the settings. */
    ONLINE(tr("Онлайн")),

    /** What is running and what was left unfinished. */
    PROCESSES(tr("Процессы")),

    /** Most played songs, listening time, what healing has fixed. */
    STATS(tr("Статистика")),

    /** Songs added by hand (a path or the file manager) and songs turned into another format. */
    ADD(tr("Добавить")),
}

/** Look of one part of the menu (navigation bar, mini player, top bar). */
@Serializable
data class PartStyle(
    /** ARGB, null = from theme. */
    val color: Long? = null,
    val alpha: Float = 1f,
    /** Frosted look: the blurred cover of the current song behind the part (Android 12+). */
    val blur: Boolean = false,
    /** Relative size of the part (0.7 .. 1.6). */
    val scale: Float = 1f,
)

@Serializable
data class WidgetSettings(
    val backgroundColor: Long? = null,
    val backgroundAlpha: Float = 0.85f,
    val useCoverColors: Boolean = true,
    val showLyrics: Boolean = true,
    val showSeekBar: Boolean = true,
    val showCover: Boolean = true,
    val lightText: Boolean = true,
    /** Lines without word timing: the current one in the accent colour. Off = like the app, plain text colour. */
    val accentCurrentLine: Boolean = true,
    /** Colour the words of the line being sung one by one, like the player does (needs word-synced lyrics). */
    val wordFill: Boolean = true,
    /**
     * The lyric lines are drawn as pictures with the player's own typeface, so they look exactly
     * like inside the app (a font from a file included). Off = plain text of the launcher.
     */
    val exactText: Boolean = true,
    /** false = the lyric lines follow "Вид текста песни" of the player; true = the widget's own look below. */
    val ownLook: Boolean = false,
    val font: FontChoice = FontChoice.SYSTEM,
    val fontFile: String? = null,
    val bold: Boolean = true,
    val align: LyricsAlign = LyricsAlign.CENTER,
    val fill: WordFill = WordFill.FAST,
    /** The old line fades into the new one. */
    val fade: Boolean = true,
    val scaleActive: Boolean = true,
    val inactiveAlpha: Float = 0.5f,
    val sungColor: Long? = null,
    val activeColor: Long? = null,
    /** Size of the lyric lines relative to what fits the widget (0.7 .. 1.4). */
    val textScale: Float = 1f,
)

/** How the lyric lines of the widget look: the player's own settings, or the widget's separate ones. */
data class LyricLook(
    val font: FontChoice,
    val fontFile: String?,
    val bold: Boolean,
    val align: LyricsAlign,
    val fill: WordFill,
    val fade: Boolean,
    val scaleActive: Boolean,
    val inactiveAlpha: Float,
    val sungColor: Long?,
    val activeColor: Long?,
    val textScale: Float,
)

fun AppSettings.widgetLyricLook(): LyricLook = if (widget.ownLook) {
    LyricLook(
        widget.font, widget.fontFile, widget.bold, widget.align, widget.fill, widget.fade, widget.scaleActive,
        widget.inactiveAlpha, widget.sungColor, widget.activeColor, widget.textScale,
    )
} else {
    LyricLook(
        lyricsFont, lyricsFontFile, lyricsBold, lyricsAlign, lyricsWordFill, lyricsAnim != LyricsAnim.OFF, lyricsScaleActive,
        lyricsInactiveAlpha, lyricsSungColor, lyricsActiveColor,
        // a bigger text in the player means a bigger text in the widget, within what the widget can hold
        (lyricsFontSize / 24f).coerceIn(0.75f, 1.35f),
    )
}

/**
 * How much of word [i] of [line] is sung at [posMs] (the lyrics' own timeline), 0..1 – one
 * formula for the player and the widget, so both colour a word at the same moment.
 */
fun wordFillProgress(
    line: app.malachite.core.lyrics.LyricLine, i: Int, posMs: Long, nextLineStartMs: Long?, mode: WordFill,
    /** When the highlight moves to the next line (Lyrics.switchMs): the last word is filled by then. */
    switchAtMs: Long? = null,
): Float {
    val w = line.words[i]
    var end = w.endMs ?: line.words.getOrNull(i + 1)?.startMs ?: line.endMs ?: nextLineStartMs ?: (w.startMs + 400)
    if (i == line.words.lastIndex && switchAtMs != null) end = minOf(end, switchAtMs).coerceAtLeast(w.startMs + 120)
    // "smooth": the word fills in over the time it is sung; "fast": a short flash; "instant": at once;
    // "letter by letter": the edge runs through the word for as long as it is sung (a long held
    // word fills slowly, the way the singer holds it)
    val span = when (mode) {
        WordFill.SMOOTH -> (end - w.startMs).coerceIn(120L, 1500L)
        WordFill.SWEEP -> (end - w.startMs).coerceIn(120L, 4000L)
        WordFill.FAST -> 140L
        WordFill.INSTANT -> 1L
    }
    return ((posMs - w.startMs + 1).toFloat() / span).coerceIn(0f, 1f)
}

@Serializable
data class AppSettings(
    // ---- library
    val mainFolder: String = "",
    val onboardingDone: Boolean = false,
    /** "Full move": after a song is copied into the main folder, delete the original. */
    val deleteOriginals: Boolean = false,
    val autoImport: Boolean = true,
    val scanIntervalMin: Int = 10,
    val deepScan: Boolean = true,
    val importRules: ImportFilter.Rules = ImportFilter.Rules(),
    val trackNumberPrefix: Boolean = false,
    /**
     * When a song has no album in its tags, take it from the folder it lies in
     * (like Mi Music / Android's media index do).
     */
    val albumFromFolders: Boolean = true,
    /** "Безнадёжность (2022)" – the year of the album in its folder name. */
    val yearInAlbumFolder: Boolean = true,
    /** Folder for songs without an album and for singles. */
    val singlesFolder: String = app.malachite.core.library.LibraryLayout.SINGLES,
    val keepBackups: Boolean = true,
    // ---- appearance
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: AccentPreset = AccentPreset.MALACHITE,
    val customAccent: Long = 0xFF2E8B57,
    /** Custom menu theme colours (null = derived from the accent). */
    val customBackground: Long? = null,
    val customSurface: Long? = null,
    val coverColorsInPlayer: Boolean = true,
    val iconColor: IconColor = IconColor.ACCENT,
    val menuFont: FontChoice = FontChoice.SYSTEM,
    val menuFontFile: String? = null,
    val lyricsFont: FontChoice = FontChoice.MONO,
    val lyricsFontFile: String? = null,
    val fontScale: Float = 1f,
    val lyricsFontSize: Int = 24,
    val lyricsAlign: LyricsAlign = LyricsAlign.CENTER,
    val lyricsBold: Boolean = true,
    val lyricsWordFill: WordFill = WordFill.SWEEP,
    val lyricsAnim: LyricsAnim = LyricsAnim.NORMAL,
    /** The current line is slightly bigger than the others. */
    val lyricsScaleActive: Boolean = true,
    val lyricsPass: LyricsPass = LyricsPass.BLUR,
    /**
     * Where the line being sung sits, as a share of the lyrics frame (from the album line at the
     * top to the shift capsule at the bottom): 0 = the middle, −0.4 higher … +0.4 lower.
     */
    val lyricsAnchorShift: Float = 0f,
    /** Colour of sung words (null = accent colour). */
    val lyricsSungColor: Long? = null,
    /** Colour of the current line (null = text colour). */
    val lyricsActiveColor: Long? = null,
    /** Favourite lines: how they are marked, in which colour (null = accent), how thick the line is (dp). */
    val lineMarkStyle: LineMarkStyle = LineMarkStyle.UNDERLINE,
    val lineMarkColor: Long? = null,
    val lineMarkThickness: Float = 3f,
    /** Brightness of the other lines, 0.15..0.9. */
    val lyricsInactiveAlpha: Float = 0.55f,
    // ---- menu layout
    val navPosition: NavPosition = NavPosition.RIGHT,
    /** On wide or low windows (tablet, laptop, phone on its side) the panel moves to the side by itself. */
    val navAuto: Boolean = true,
    val tabs: List<Tab> = listOf(Tab.SONGS, Tab.ALBUMS, Tab.ARTISTS, Tab.FAVORITES, Tab.PLAYLISTS, Tab.HEAL, Tab.STATS, Tab.ADD, Tab.PROCESSES),
    /** The statistics tab was put on the panel once (for those who had their own set of tabs already). */
    val statsTabAdded: Boolean = true,
    /** The «Добавить» tab was put on the panel once (settings from before it existed get it too). */
    val addTabAdded: Boolean = false,
    /** The short tour was shown (after the first start; "show again" sets it back). */
    val tourShown: Boolean = false,
    /** The "what's new" window seen up to this list ([app.malachite.player.ui.help.WhatsNew.ID]). */
    val whatsNewSeen: Int = 0,
    val showTabLabels: Boolean = true,
    val navStyle: PartStyle = PartStyle(),
    val miniPlayerStyle: PartStyle = PartStyle(),
    val topBarStyle: PartStyle = PartStyle(),
    val miniPlayerOnTop: Boolean = false,
    /** Holding a finger on the tab panel lifts the pill; it follows the finger to another tab. */
    val tabSlide: Boolean = true,
    /** How long the finger is held before the pill lifts (a shorter touch is a tap). */
    val tabSlideDelayMs: Int = 350,
    /** How the pill glides to a tab. */
    val tabSlideAnim: TabSlideAnim = TabSlideAnim.SPRING,
    /** What healing did last time, remembered (names of [app.malachite.player.heal.HealStep]; empty: the usual set). */
    val healSteps: List<String> = emptyList(),
    val healStepsSongs: List<String> = emptyList(),
    /** Ask the phone for its fastest screen refresh (90 / 120 Hz) while the app is open. */
    val highRefreshRate: Boolean = true,
    val albumSort: AlbumSort = AlbumSort.SIZE,
    val playlistSort: PlaylistSort = PlaylistSort.NAME,
    val albumGridColumns: Int = 2,
    // ---- lyrics
    val lyricsAutoSearch: Boolean = true,
    val lyricsRemoveAllParentheses: Boolean = false,
    val lyricsEmbedInFile: Boolean = true,
    val syncReactionMs: Int = 0,
    /** Songs play equally loud, by the loudness healing measured. */
    val levelLoudness: Boolean = true,
    /** "Плавный переход": seconds the end of a song and the start of the next one overlap (0: off). */
    val crossfadeSec: Int = 0,
    /**
     * Interface animations: screens gliding, the tab pill, the song screen sliding up. Off: all of it
     * changes at once (the visualizer and the lyrics keep their own switches).
     */
    val animations: Boolean = true,
    /** Soft shadows (gradients) at the edges of the bars and panels. */
    val gradients: Boolean = true,
    /** Frosted glass: the blurred cover behind the top bar, the tab panel and the mini player (Android 12+). */
    val blur: Boolean = false,
    /** Bars that move with the music on the song screen. */
    val visualizer: Boolean = true,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.WAVE,
    val visualizerOrder: VisualizerOrder = VisualizerOrder.FREQUENCY,
    /** How long the visualizer takes to appear when the music starts and to go when it stops (ms). */
    val visualizerFadeMs: Int = 900,
    /** The DJ mixer in front of the speaker (Equalizer screen) and where its knobs stand. */
    val mixerOn: Boolean = true,
    val mixer: app.malachite.player.playback.MixerKnobs = app.malachite.player.playback.MixerKnobs(),
    /** Songs from the online section that were heard are kept up to this size for playing offline (MB; 0: not kept). */
    val onlineCacheMb: Int = 1024,
    /** Protected data: the password is asked at every start instead of the key being kept on this phone. */
    val askPasswordEveryStart: Boolean = true,
    /** An unfinished manual sync is saved this often while it is being recorded. */
    val syncAutosaveSec: Int = 10,
    /** "♪" lines mark themselves after this many seconds without a press (0: pressed by hand). */
    val syncPauseSec: Int = 4,
    /** The "hold while it is sung" button: width and height (dp; a width larger than the screen fills it) and corners (0…50 %). */
    val syncButtonWidthDp: Int = 150,
    val syncButtonHeightDp: Int = 150,
    val syncButtonCorner: Int = 50,
    // ---- playback
    val speed: Float = 1f,
    val pitchSemitones: Float = 0f,
    val linkPitchToSpeed: Boolean = true,
    val rememberSpeedPerSong: Boolean = true,
    /** Colour the media notification from the cover where the system supports it. */
    val notificationCoverColors: Boolean = true,
    /** The online section (search, listen, download from open catalogues). Off: the player stays offline. */
    val onlineEnabled: Boolean = false,
    // ---- agreement and diagnostics
    /** Version of the user agreement that was accepted (0 = not yet). */
    val termsAccepted: Int = 0,
    /** Write a log file into Download. Off unless the user turns it on. */
    val logEnabled: Boolean = false,
    /** With the log on: also frames per second, late frames, slow work of the main thread, memory. */
    val logDetails: Boolean = false,
    /** Keep the report of a crash and ask at the next start what to do with it. Off unless allowed. */
    val crashReports: Boolean = false,
    val skipSilence: Boolean = false,
    val resumeLastSession: Boolean = true,
    // ---- widgets
    val widget: WidgetSettings = WidgetSettings(),
    // ---- root / Shizuku extras
    val privilegedMode: Privileged.Mode = Privileged.Mode.NONE,
    /** Also look for music in other apps' private folders (Android/data). */
    val scanAppData: Boolean = false,
)

fun AppSettings.layoutOptions() = app.malachite.core.library.LibraryLayout.Options(
    trackNumberPrefix = trackNumberPrefix,
    yearInAlbumFolder = yearInAlbumFolder,
    singlesFolder = singlesFolder.ifBlank { app.malachite.core.library.LibraryLayout.SINGLES },
)

/**
 * Settings live in the app's private storage and are mirrored into
 * `<main>/.malachite/settings.json`, so moving the main folder (zip) to a new phone
 * brings them along.
 */
class SettingsRepository(private val context: Context) {
    private val file = File(context.filesDir, "settings.json")
    // an unknown value (a setting of a newer or older version) takes its default instead of failing the whole file
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; coerceInputValues = true; isLenient = true }
    private val backup = File(context.filesDir, "settings.backup.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value get() = _state.value

    /**
     * The settings as they were left. A file that cannot be read (cut off when the phone died in
     * the middle of writing it) does not mean starting from scratch: the copy of the last file that
     * was read is used, and the unreadable one is kept aside to see what happened.
     */
    private fun load(): AppSettings {
        for (f in listOf(file, backup)) {
            if (!f.isFile) continue
            val read = runCatching { json.decodeFromString(AppSettings.serializer(), f.readText()) }
            read.getOrNull()?.let { ok ->
                if (f == file) runCatching { f.copyTo(backup, overwrite = true) }
                return ok
            }
            android.util.Log.w("Malachite", "settings: ${f.name} is not readable", read.exceptionOrNull())
            if (f == file) runCatching { f.copyTo(File(context.filesDir, "settings.unreadable.json"), overwrite = true) }
        }
        return AppSettings()
    }

    @Synchronized
    fun update(f: (AppSettings) -> AppSettings) {
        val n = f(_state.value)
        if (n == _state.value) return
        _state.value = n
        val text = json.encodeToString(AppSettings.serializer(), n)
        runCatching { LibraryStore.writeAtomic(file, text) }
        mirror(n, text)
    }

    private fun mirror(s: AppSettings, text: String) {
        if (s.mainFolder.isEmpty()) return
        runCatching {
            val store = LibraryStore(File(s.mainFolder))
            if (store.dataDir.isDirectory) store.saveSettingsJson(text)
        }
    }

    /** Restores settings saved in a main folder that came from another phone. */
    fun restoreFrom(mainFolder: File): Boolean {
        val text = LibraryStore(mainFolder).loadSettingsJson() ?: return false
        val s = runCatching { json.decodeFromString(AppSettings.serializer(), text) }.getOrNull() ?: return false
        // The file lies in the main folder, where other apps can write. What could harm or
        // expose the user is never taken from it: deleting originals, going online, keeping a
        // log, the agreement. Font files count only from the app's own font folder.
        val fonts = File(context.filesDir, "fonts")
        fun ownFont(path: String?) = path?.takeIf { app.malachite.core.fs.PathGuard.inside(fonts, File(it)) && File(it).isFile }
        update { cur ->
            s.copy(
                mainFolder = mainFolder.absolutePath,
                onboardingDone = cur.onboardingDone,
                deleteOriginals = cur.deleteOriginals,
                onlineEnabled = cur.onlineEnabled,
                logEnabled = cur.logEnabled,
                logDetails = cur.logDetails,
                crashReports = cur.crashReports,
                termsAccepted = cur.termsAccepted,
                lyricsFontFile = ownFont(s.lyricsFontFile),
                menuFontFile = ownFont(s.menuFontFile),
                widget = s.widget.copy(fontFile = ownFont(s.widget.fontFile)),
            )
        }
        return true
    }
}
