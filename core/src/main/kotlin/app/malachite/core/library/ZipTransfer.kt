package app.malachite.core.library

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import of the whole main folder as a ZIP **without compression** (entries are
 * STORED): the files inside are byte-for-byte the same, the archive is just a container.
 * Unpacking it on another phone recreates the same main folder, which the player finds
 * on its own.
 */
object ZipTransfer {

    /** Parts of `.malachite` that are machine-local and not exported. */
    private val SKIP = setOf("work", "journal", "backup", "trash")

    fun filesToExport(root: File): List<File> = root.walkTopDown()
        .onEnter { d ->
            val rel = d.relativeTo(root).invariantSeparatorsPath
            !(rel.startsWith(LibraryLayout.DATA_DIR + "/") && rel.removePrefix(LibraryLayout.DATA_DIR + "/").substringBefore('/') in SKIP)
        }
        .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".malachite-old") }
        .toList()

    /**
     * Writes the archive to [out]. The top-level folder inside the zip is the main folder's name.
     * [progress] gets (bytesDone, bytesTotal).
     */
    fun export(root: File, out: OutputStream, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }) {
        val files = filesToExport(root)
        val total = files.sumOf { it.length() }
        var done = 0L
        val top = root.name
        ZipOutputStream(BufferedOutputStream(out, 512 * 1024)).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            val buf = ByteArray(256 * 1024)
            for (f in files) {
                if (cancelled()) throw IOException("cancelled")
                val crc = CRC32()
                FileInputStream(f).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                    }
                }
                val entry = ZipEntry(top + "/" + f.relativeTo(root).invariantSeparatorsPath).apply {
                    method = ZipEntry.STORED
                    size = f.length()
                    compressedSize = f.length()
                    this.crc = crc.value
                    time = f.lastModified()
                }
                zip.putNextEntry(entry)
                FileInputStream(f).use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                        done += n
                    }
                }
                zip.closeEntry()
                progress(done, total)
            }
        }
    }

    /**
     * Unpacks an exported archive into [destParent]. Returns the main folder that was created.
     * Protects against "zip slip" (entries escaping the destination).
     */
    fun import(input: InputStream, destParent: File, progress: (Long) -> Unit = {}): File? {
        var rootName: String? = null
        var done = 0L
        val destCanon = destParent.canonicalPath.trimEnd('/') + "/"
        ZipInputStream(BufferedInputStream(input, 512 * 1024)).use { zip ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (rootName == null) rootName = name.substringBefore('/')
                val target = File(destParent, name)
                if (!target.canonicalPath.startsWith(destCanon)) throw IOException("unsafe entry ${e.name}")
                if (e.isDirectory) { target.mkdirs(); continue }
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, ".${target.name}.part")
                FileOutputStream(tmp).use { out ->
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                    }
                    out.fd.sync()
                }
                if (target.exists() && target.length() == tmp.length()) tmp.delete()
                else if (!tmp.renameTo(target)) throw IOException("cannot write ${target.name}")
                if (e.time > 0) target.setLastModified(e.time)
                progress(done)
            }
        }
        return rootName?.let { File(destParent, it) }
    }
}
