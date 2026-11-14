package app.malachite.player.data

import app.malachite.core.i18n.tr
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.malachite.core.library.LibraryStore
import java.io.File

/** Storage access that works the same on every vendor ROM (no vendor-specific APIs). */
object Storage {

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    /** Intent for the "All files access" screen (Android 11+). */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
        }

    /** Fallback when a ROM has no per-app screen for all-files access. */
    fun allFilesAccessFallbackIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) else null

    fun legacyPermissions(): Array<String> = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    @Suppress("DEPRECATION")
    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    /** Internal storage and SD cards. */
    fun roots(context: Context): List<File> {
        val out = LinkedHashSet<File>()
        out += primaryRoot()
        context.getExternalFilesDirs(null).filterNotNull().forEach { d ->
            val p = d.absolutePath
            val i = p.indexOf("/Android/data/")
            if (i > 0) out += File(p.substring(0, i))
        }
        return out.filter { it.isDirectory }
    }

    fun defaultMainFolder(): File = File(primaryRoot(), "Malachite")

    enum class FolderStatus(val message: String) {
        OK(tr("Папка доступна")),
        NOT_FOUND(tr("Папка не найдена")),
        NOT_DIRECTORY(tr("Это не папка")),
        NO_PERMISSION(tr("Нет разрешения читать эту папку — выдайте плееру «Доступ ко всем файлам»")),
        NOT_WRITABLE(tr("Папку можно читать, но нельзя изменять (защищённое место или SD-карта без доступа)")),
    }

    fun check(context: Context, path: String): FolderStatus {
        if (!hasAllFilesAccess(context)) return FolderStatus.NO_PERMISSION
        val f = File(path)
        if (!f.exists()) return FolderStatus.NOT_FOUND
        if (!f.isDirectory) return FolderStatus.NOT_DIRECTORY
        if (!f.canRead() || f.list() == null) return FolderStatus.NO_PERMISSION
        if (!f.canWrite()) return FolderStatus.NOT_WRITABLE
        return FolderStatus.OK
    }

    /** Accepts "Malachite", "/Malachite", "storage/emulated/0/Malachite" or a full path. */
    fun resolveUserPath(input: String): File {
        val t = input.trim().trimEnd('/')
        if (t.startsWith("/storage/") || t.startsWith("/sdcard") || t.startsWith("/mnt/")) return File(t)
        if (t.startsWith("storage/")) return File("/$t")
        return File(primaryRoot(), t.trimStart('/'))
    }

    /**
     * Finds an existing main folder: the saved one, the default one, or any folder on
     * internal storage / SD cards (two levels deep) that contains our marker – e.g. a folder
     * that was unpacked from a zip made on another phone.
     */
    fun locateMainFolder(context: Context, saved: String): File? {
        if (saved.isNotEmpty() && LibraryStore.isLibraryRoot(File(saved))) return File(saved)
        val def = defaultMainFolder()
        if (LibraryStore.isLibraryRoot(def)) return def
        for (root in roots(context)) {
            val level1 = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name != "Android" } ?: continue
            for (d in level1) {
                if (LibraryStore.isLibraryRoot(d)) return d
            }
            for (d in level1) {
                val level2 = d.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: continue
                for (e in level2) if (LibraryStore.isLibraryRoot(e)) return e
            }
        }
        return null
    }
}
