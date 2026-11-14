package app.malachite.player.heal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What one healing task changed, so that it can be taken back: where the originals of changed
 * files were kept, which files moved where, which files went to the trash.
 */
@Serializable
data class UndoRecord(
    val id: String,
    /** [app.malachite.core.heal.HealKind] name. */
    val kind: String,
    val time: Long,
    /** Folder with the originals of the files this task changed; null when copies were not kept. */
    val backupDir: String? = null,
    /** Old relative path -> new relative path. */
    val moves: Map<String, String> = emptyMap(),
    /** Old relative path -> absolute path in the trash. */
    val trashed: Map<String, String> = emptyMap(),
    val changed: Int = 0,
)

/** The records live next to the backups in the main folder (`.malachite/undo`). */
class UndoStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(r: UndoRecord) {
        runCatching {
            dir.mkdirs()
            File(dir, "${r.id}.json").writeText(json.encodeToString(UndoRecord.serializer(), r))
            // only the last few tasks can be taken back; older records go
            list().drop(MAX).forEach { File(dir, "${it.id}.json").delete() }
        }
    }

    /** Newest first. */
    fun list(): List<UndoRecord> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> runCatching { json.decodeFromString(UndoRecord.serializer(), f.readText()) }.getOrNull() }
            // a record is named after its id; one whose id is not ours (a changed file) is ignored
            .filter { it.id.matches(ID) && f(it.id).isFile }
            .sortedByDescending { it.time }

    private fun f(id: String) = File(dir, "$id.json")

    fun delete(id: String) { if (id.matches(ID)) runCatching { f(id).delete() } }

    fun clear() { runCatching { dir.deleteRecursively() } }

    companion object {
        private const val MAX = 10
        /** The ids this app gives (random UUIDs): nothing like "../" can be one. */
        private val ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
