package app.malachite.player.heal

import app.malachite.core.heal.HealKind

/**
 * One thing healing can do by itself, in the order the steps are done when several are chosen
 * (formats before names, covers before the folders, the words last). [ODD_TAGS] is the online
 * check of odd albums and artists ("AyuGram", sites); it writes like "empty albums and artists".
 */
enum class HealStep(val kind: HealKind?) {
    JUNK(HealKind.JUNK),
    FORMATS(HealKind.FORMATS),
    ODD_TAGS(null),
    SOUND_ID(HealKind.SOUND_ID),
    TITLE_NUMBERS(HealKind.TITLE_NUMBERS),
    MISSING(HealKind.MISSING),
    NAMES(HealKind.NAMES),
    FILENAMES(HealKind.FILENAMES),
    DUPLICATES(HealKind.DUPLICATES),
    BROKEN(HealKind.BROKEN),
    COVERS(HealKind.COVERS),
    COVER_QUALITY(HealKind.COVER_QUALITY),
    TRACK_NUMBERS(HealKind.TRACK_NUMBERS),
    LETTER_CASE(HealKind.LETTER_CASE),
    COMPILATIONS(HealKind.COMPILATIONS),
    LAYOUT(HealKind.LAYOUT),
    LYRICS(HealKind.LYRICS),
    LOUDNESS(HealKind.LOUDNESS),
    ;

    companion object {
        /** Never ticked by "everything": many artists write their names in capitals on purpose. */
        val ONLY_BY_HAND = setOf(LETTER_CASE)

        /** "Everything": each step that fixes only what it is sure of. */
        val ALL = entries.toSet() - ONLY_BY_HAND

        /** Healing the whole library unless chosen otherwise (the words and the lists of albums take long). */
        val USUAL_LIBRARY = ALL - setOf(LYRICS, TRACK_NUMBERS, LOUDNESS)

        /** Healing chosen songs unless chosen otherwise. */
        val USUAL_SONGS = ALL - setOf(LOUDNESS)

        /** A remembered choice ([names] of the steps); nothing remembered: [usual]. */
        fun parse(names: List<String>, usual: Set<HealStep>): Set<HealStep> {
            val steps = names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet()
            return steps.ifEmpty { usual }
        }
    }
}
