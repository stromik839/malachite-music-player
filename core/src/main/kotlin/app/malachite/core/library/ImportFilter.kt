package app.malachite.core.library

import app.malachite.core.audio.AudioFormat
import app.malachite.core.audio.TagData
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Decides which audio files found on the device are *music* and may be imported into the
 * main folder. Voice messages (Telegram, WhatsApp, Viber, VK...), call and dictaphone
 * recordings, ringtones, alarms and notification sounds are rejected.
 */
object ImportFilter {

    @Serializable
    data class Rules(
        /** Shorter files are notification sounds / jingles. */
        val minDurationSec: Int = 30,
        /** Extra folders (absolute or a name fragment) the user excluded. */
        val excludedFolders: List<String> = emptyList(),
        val importPodcasts: Boolean = false,
        val importAudiobooks: Boolean = false,
        /**
         * Everything audio that came through messengers. Off by default (mostly clutter): songs saved
         * on purpose (Music/Telegram) and songs that are tagged or named like one are taken anyway.
         */
        val importFromMessengers: Boolean = false,
    )

    enum class Verdict { MUSIC, VOICE_MESSAGE, RECORDING, SYSTEM_SOUND, TOO_SHORT, EXCLUDED_FOLDER, NOT_AUDIO, PODCAST, VIDEO, MESSENGER }

    /** Folder fragments (lower-case, '/'-separated) that never contain music. */
    private val VOICE_DIRS = listOf(
        "telegram voice", "voice notes", "whatsapp voice notes", "whatsapp business voice notes", "viber voice",
        "vk/voice", "vkontakte/voice", "voice messages", "voicemessages", "voice", "voices", "signal/voice",
        "org.telegram.messenger/cache", "org.telegram.messenger/files", "audio_messages", "ptt", "голосовые",
        "голосовые сообщения",
    )
    private val RECORDING_DIRS = listOf(
        "recordings", "recording", "call", "callrecord", "call recordings", "callrecordings", "miui/sound_recorder",
        "sound_recorder", "soundrecorder", "voice recorder", "voicerecorder", "recorder", "sounds/callrecord",
        "диктофон", "записи", "звукозапись", "audiorecorder", "voice_recorder", "record/call", "record/sound",
        "sound recordings",
    )
    private val SYSTEM_DIRS = listOf(
        "notifications", "ringtones", "alarms", "ui", "system/media", "android/data", "android/obb",
        ".thumbnails", "miui/.ringtone", ".trash", ".recycle", "lost.dir", "уведомления", "рингтоны", "будильники",
        "звуки уведомлений",
    )
    private val PODCAST_DIRS = listOf("podcasts", "audiobooks", "подкасты", "аудиокниги")

    /** Folders of messengers (and their Android package folders). */
    private val MESSENGER_DIRS = listOf(
        "telegram", "telegram x", "exteragram", "nekogram", "nagram", "plus messenger", "telegram audio",
        "telegram documents", "telegram video", "telegram images", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.telegram.plus", "com.exteragram.messenger", "tw.nekomimi.nekogram", "org.thunderdog.challegram",
        "whatsapp", "whatsapp business", "com.whatsapp", "com.whatsapp.w4b", "viber", "com.viber.voip",
        "vk", "vkontakte", "com.vkontakte.android", "kate mobile", "com.perm.kate", "signal", "discord", "messenger",
    )

    /** Cache-like names messengers give to files: "4_5913724166165551837@kndved.ogg", "a0994bcb…@x.mp3". */
    private val MESSENGER_NAME = listOf(
        Regex("^-?\\d{1,4}_\\d{6,}(_\\d+)?(@[a-z0-9]+)?$", RegexOption.IGNORE_CASE),
        Regex("^[0-9a-f]{16,}(@[a-z0-9]+)?$", RegexOption.IGNORE_CASE),
        Regex("^\\d{6,}@[a-z0-9]+$", RegexOption.IGNORE_CASE),
        Regex("^(telegram|tg|file)_\\d{3,}", RegexOption.IGNORE_CASE),
    )

    fun isMessengerName(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        return MESSENGER_NAME.any { it.containsMatchIn(base) }
    }

    fun isFromMessenger(path: String): Boolean = MESSENGER_DIRS.any { hasSegments(dirsOf(path), it) }

    /** The folders of a file, lower-case, with slashes around: "/storage/emulated/0/music/telegram/". */
    private fun dirsOf(path: String): String {
        val norm = "/" + path.replace('\\', '/').lowercase().trimStart('/')
        return norm.substringBeforeLast('/') + "/"
    }

    /** Standard folders a file is saved into on purpose ("Music/Telegram" is Telegram's "Save to music"). */
    private val SAVED_INTO = listOf("music", "download", "downloads", "documents", "audio", "музыка", "загрузки")

    /**
     * Saved there by the user ("Сохранить в музыку" / "в загрузки" of Telegram puts songs into
     * Music/Telegram and Download/Telegram): music like any other, not messenger clutter.
     */
    fun isDeliberateSave(path: String): Boolean {
        val dirs = dirsOf(path)
        if (dirs.contains("/android/")) return false
        return SAVED_INTO.any { parent -> MESSENGER_DIRS.any { hasSegments(dirs, "$parent/$it") } }
    }

    private val SONG_NAME = Regex("\\S\\s+[-–—]\\s+\\S")

    /**
     * A song rather than a meme or a clip (what lies in a messenger's folders: songs from music
     * channels next to jingles): at least a minute long (or of unknown length) and tagged with its
     * title and artist, or named "Artist - Title" ("XIVIVIDE_-_Trolit_Menya" too).
     */
    fun isSongLike(fileName: String, title: String?, artist: String?, durationMs: Long): Boolean {
        if (durationMs in 1 until 60_000) return false
        if (!title.isNullOrBlank() && !artist.isNullOrBlank()) return true
        return SONG_NAME.containsMatchIn(fileName.substringBeforeLast('.').replace('_', ' '))
    }

    private val VOICE_NAME = Regex(
        "^(ptt-\\d|voice[_ -]?\\d|voice[_ -]?(note|message|msg)|audio_\\d{4}-\\d{2}-\\d{2}|rec[_-]?\\d|recording[_ -]|" +
            "call[_ -]|запись[_ ]|голосов|аудиосообщение|dictaphone|voice_\\d)",
        RegexOption.IGNORE_CASE,
    )

    private fun hasSegments(dirs: String, fragment: String) = dirs.contains("/" + fragment.trim('/') + "/")

    fun checkPath(path: String, rules: Rules, mainRoot: String? = null): Verdict {
        val norm = "/" + path.replace('\\', '/').lowercase().trimStart('/')
        if (mainRoot != null) {
            val r = "/" + mainRoot.replace('\\', '/').lowercase().trim('/') + "/"
            if (norm.startsWith(r)) return Verdict.EXCLUDED_FOLDER
        }
        // "/storage/emulated/0/telegram/telegram voice/" – the folders of the file, with slashes around
        val dirs = norm.substringBeforeLast('/') + "/"
        if (SYSTEM_DIRS.any { hasSegments(dirs, it) }) return Verdict.SYSTEM_SOUND
        if (VOICE_DIRS.any { hasSegments(dirs, it) }) return Verdict.VOICE_MESSAGE
        if (RECORDING_DIRS.any { hasSegments(dirs, it) }) return Verdict.RECORDING
        if (!rules.importPodcasts && PODCAST_DIRS.any { hasSegments(dirs, it) }) return Verdict.PODCAST
        for (ex in rules.excludedFolders) {
            val e = ex.replace('\\', '/').lowercase().trim().trim('/')
            if (e.isEmpty()) continue
            if (norm.startsWith("/$e/") || hasSegments(dirs, e)) return Verdict.EXCLUDED_FOLDER
        }
        val name = norm.substringAfterLast('/')
        if (VOICE_NAME.containsMatchIn(name)) return Verdict.VOICE_MESSAGE
        // cache-like names are never songs; what else lies in a messenger's folders [check] decides by the tags
        if (!rules.importFromMessengers && isMessengerName(name)) return Verdict.MESSENGER
        return Verdict.MUSIC
    }

    /** Full check once the file's format and tags are known. */
    fun check(file: File, format: AudioFormat, tags: TagData?, rules: Rules, mainRoot: String?, hasVideo: Boolean = false): Verdict {
        val byPath = checkPath(file.absolutePath, rules, mainRoot)
        if (byPath != Verdict.MUSIC) return byPath
        if (format == AudioFormat.UNKNOWN) return Verdict.NOT_AUDIO
        if (hasVideo) return Verdict.VIDEO
        if (format.voiceLike) return Verdict.VOICE_MESSAGE
        val dur = tags?.durationMs ?: 0
        if (dur in 1 until rules.minDurationSec * 1000L) return Verdict.TOO_SHORT
        // Voice notes are mono Opus/Vorbis without any tags.
        val untagged = tags == null || (tags.title.isBlank() && tags.artist.isBlank() && tags.album.isBlank())
        if ((format == AudioFormat.OPUS || format == AudioFormat.OGG_VORBIS) && untagged && (tags?.channels ?: 1) <= 1) {
            return Verdict.VOICE_MESSAGE
        }
        if (format == AudioFormat.M4A && untagged && (tags?.channels ?: 1) <= 1 && (tags?.bitrateKbps ?: 0) in 1..96 &&
            dur < 20 * 60_000
        ) {
            return Verdict.RECORDING
        }
        // from a messenger's folders: only the songs (saved on purpose, or tagged / named like one)
        val path = file.absolutePath
        if (!rules.importFromMessengers && isFromMessenger(path) && !isDeliberateSave(path) &&
            !isSongLike(file.name, tags?.title, tags?.let { it.artist.ifBlank { it.albumArtist } }, dur)
        ) {
            return Verdict.MESSENGER
        }
        return Verdict.MUSIC
    }
}
