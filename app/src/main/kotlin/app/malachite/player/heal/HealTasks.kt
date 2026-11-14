package app.malachite.player.heal

import app.malachite.core.heal.AlbumNumbering
import app.malachite.core.heal.BrokenFile
import app.malachite.core.heal.CaseFix
import app.malachite.core.heal.CompilationFix
import app.malachite.core.heal.CoverIssue
import app.malachite.core.heal.CoverJob
import app.malachite.core.heal.DuplicateGroup
import app.malachite.core.heal.FileRename
import app.malachite.core.heal.FormatFix
import app.malachite.core.heal.HealKind
import app.malachite.core.heal.JunkItem
import app.malachite.core.heal.LayoutMove
import app.malachite.core.heal.NameFix
import app.malachite.core.heal.TagSuggestion
import app.malachite.core.heal.TitleNumberFix
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A healing task as data, so that it can be kept on disk and go on after the app was closed or
 * updated. Tasks with a ready list ([resumable]) go on from the item they stopped at; the others
 * make their list again when they start, and what is done is simply not in it any more.
 */
@Serializable
sealed class HealTask {
    abstract val kind: HealKind
    open val resumable: Boolean get() = true

    @Serializable @SerialName("formats")
    data class Formats(val list: List<FormatFix>) : HealTask() { override val kind get() = HealKind.FORMATS }

    @Serializable @SerialName("names")
    data class Names(val list: List<NameFix>) : HealTask() { override val kind get() = HealKind.NAMES }

    @Serializable @SerialName("junk")
    data class Junk(val list: List<JunkItem>) : HealTask() { override val kind get() = HealKind.JUNK }

    @Serializable @SerialName("missing")
    data class Missing(val list: List<TagSuggestion>) : HealTask() { override val kind get() = HealKind.MISSING }

    @Serializable @SerialName("filenames")
    data class Filenames(val list: List<FileRename>) : HealTask() { override val kind get() = HealKind.FILENAMES }

    @Serializable @SerialName("duplicates")
    data class Duplicates(val list: List<DuplicateGroup>) : HealTask() { override val kind get() = HealKind.DUPLICATES }

    @Serializable @SerialName("covers")
    data class Covers(val list: List<CoverJob>) : HealTask() { override val kind get() = HealKind.COVERS }

    @Serializable @SerialName("layout")
    data class Layout(val list: List<LayoutMove>) : HealTask() { override val kind get() = HealKind.LAYOUT }

    /** The folders of these songs, planned when the task starts (after the compilations got their album artist). */
    @Serializable @SerialName("layout_of")
    data class LayoutOf(val paths: List<String>) : HealTask() {
        override val kind get() = HealKind.LAYOUT
        override val resumable get() = false
    }

    @Serializable @SerialName("compilations")
    data class Compilations(val list: List<CompilationFix>) : HealTask() { override val kind get() = HealKind.COMPILATIONS }

    @Serializable @SerialName("broken")
    data class Broken(val list: List<BrokenFile>) : HealTask() { override val kind get() = HealKind.BROKEN }

    @Serializable @SerialName("cover_quality")
    data class CoverQuality(val list: List<CoverIssue>) : HealTask() { override val kind get() = HealKind.COVER_QUALITY }

    @Serializable @SerialName("track_numbers")
    data class Numbering(val list: List<AlbumNumbering>) : HealTask() { override val kind get() = HealKind.TRACK_NUMBERS }

    @Serializable @SerialName("title_numbers")
    data class TitleNumbers(val list: List<TitleNumberFix>) : HealTask() { override val kind get() = HealKind.TITLE_NUMBERS }

    @Serializable @SerialName("letter_case")
    data class LetterCase(val list: List<CaseFix>) : HealTask() { override val kind get() = HealKind.LETTER_CASE }

    @Serializable @SerialName("lyrics")
    data class Lyrics(val paths: List<String>) : HealTask() { override val kind get() = HealKind.LYRICS }

    /** Songs measured already are skipped when it goes on (each result is kept as it comes). */
    @Serializable @SerialName("loudness")
    data class Loudness(val paths: List<String>) : HealTask() {
        override val kind get() = HealKind.LOUDNESS
        override val resumable get() = false
    }

    /**
     * A step of "heal everything": planned on the library as it is when the step starts. With
     * [only]: the step of "heal the chosen songs", for these songs alone.
     */
    @Serializable @SerialName("fresh")
    data class Fresh(override val kind: HealKind, val only: List<String>? = null) : HealTask() {
        override val resumable get() = false
    }

    /** Songs recognised by their sound, as ticked by the user. */
    @Serializable @SerialName("sound_ids")
    data class SoundIds(val list: List<app.malachite.core.heal.SoundMatch>) : HealTask() {
        override val kind get() = HealKind.SOUND_ID
    }

    /** Junk albums and artists of the chosen songs looked up online; sure answers are written. */
    @Serializable @SerialName("odd_tags")
    data class OddTags(val paths: List<String>, val everything: Boolean = false) : HealTask() {
        override val kind get() = HealKind.MISSING
        override val resumable get() = false
    }

    /** Taking back an earlier task. */
    @Serializable @SerialName("undo")
    data class Undo(val record: UndoRecord) : HealTask() {
        override val kind get() = runCatching { HealKind.valueOf(record.kind) }.getOrDefault(HealKind.LAYOUT)
        override val resumable get() = false
    }
}

/** A task kept on disk, with where it was: [loop] and [done] as in HealExecutor.resumeAt. */
@Serializable
data class SavedHealJob(val id: String, val task: HealTask, val loop: Int = 0, val done: Int = 0)
