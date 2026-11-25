package app.malachite.player.heal

import app.malachite.player.data.AppLog
import app.malachite.player.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * New songs in the main folder heal themselves («Новые песни исцеляются сами», on unless switched
 * off): as soon as there is internet, the steps that only fill in what is missing and fix what they
 * are sure of run for them – names and numbers in titles, empty tags, covers, lyrics, file names and
 * folders (and recognition by sound, when its key is set). Nothing is deleted here and no clone is
 * touched; the doubtful cases wait in «Исцеление» as always, and everything can be undone there.
 * Songs that come together (an import, an album downloaded song by song) are healed together.
 */
class AutoHeal(private val settings: SettingsRepository, private val healing: HealingEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = LinkedHashSet<String>()
    private var waiting: Job? = null

    /** Songs that have just appeared in the main folder (paths inside it). */
    fun newSongs(paths: List<String>) {
        if (paths.isEmpty() || !settings.value.autoHealNew) return
        synchronized(pending) { pending += paths }
        synchronized(this) {
            // more may follow (an import copies one file after another): they are healed together
            waiting?.cancel()
            waiting = scope.launch {
                delay(GATHER_MS)
                app.malachite.player.data.Net.awaitOnline()
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (batch.isEmpty() || !settings.value.autoHealNew) return@launch
                AppLog.i("heal", "Новые песни исцеляются сами: ${batch.size}")
                healing.heal(steps(), batch)
            }
        }
    }

    private fun steps(): Set<HealStep> = buildSet {
        addAll(SAFE_STEPS)
        // only with a key: without it the step would ask for one each time songs come
        if (app.malachite.player.data.SoundIdKey.present) add(HealStep.SOUND_ID)
    }

    private companion object {
        const val GATHER_MS = 8_000L

        /** What can be done without asking: nothing here deletes a file or merges songs. */
        val SAFE_STEPS = setOf(
            HealStep.ODD_TAGS, HealStep.TITLE_NUMBERS, HealStep.MISSING, HealStep.NAMES,
            HealStep.COVERS, HealStep.FILENAMES, HealStep.LAYOUT, HealStep.LYRICS,
        )
    }
}
