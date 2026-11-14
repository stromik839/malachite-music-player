package app.malachite.core.fs

import java.io.File

/**
 * Paths that come from files other apps can change (the plans and records in the main folder)
 * or from other apps (a media controller) must never lead the player outside the folder they
 * belong to: "../../DCIM/photo.jpg" or a link would otherwise let them move, delete, read or
 * play any file the player can reach, including its own private files.
 */
object PathGuard {
    /** True when [f] is [root] itself or lies inside it, after "..", "." and links are resolved. */
    fun inside(root: File, f: File): Boolean = runCatching {
        val r = root.canonicalPath.trimEnd(File.separatorChar)
        val p = f.canonicalPath
        p == r || p.startsWith(r + File.separatorChar)
    }.getOrDefault(false)

    /** [rel] resolved against [root], or null when it leads outside of it. */
    fun child(root: File, rel: String): File? = File(root, rel).takeIf { rel.isNotEmpty() && inside(root, it) }
}
