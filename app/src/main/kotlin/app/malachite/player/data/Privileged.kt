package app.malachite.player.data

import app.malachite.core.i18n.tr
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional extras for phones with root (Magisk/KernelSU/APatch – anything that provides `su`)
 * or Shizuku. Nothing here is vendor-specific and nothing runs unless the user enables it.
 *
 *  - find music inside other apps' private folders (Android/data), e.g. "Telegram Audio"
 *    which Android 11+ hides even from apps with all-files access;
 *  - keep the player from being killed in the background (doze whitelist + background appops);
 *  - grant the player's own permissions in one tap.
 *
 * Only the player's own package and music files are ever touched.
 */
object Privileged {

    enum class Mode(val title: String) { NONE(tr("Выключено")), ROOT("Root (su)"), SHIZUKU("Shizuku") }

    data class Exec(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    // ------------------------------------------------------------------ availability

    /** Runs `su -c id`. May show the root manager's prompt – call only on user action. */
    fun checkRoot(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val done = p.waitFor(8, TimeUnit.SECONDS)
        val out = p.inputStream.bufferedReader().readText()
        done && p.exitValue() == 0 && out.contains("uid=0")
    }.getOrDefault(false)

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizuku(code: Int = 7) {
        runCatching { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(code) }
    }

    fun isUsable(mode: Mode): Boolean = when (mode) {
        Mode.NONE -> false
        Mode.ROOT -> true // verified on demand
        Mode.SHIZUKU -> shizukuGranted()
    }

    // ------------------------------------------------------------------ shell

    /** Runs [cmd] with elevated rights. Never call with user-typed text. */
    fun exec(mode: Mode, cmd: String, timeoutSec: Long = 60): Exec {
        val p: Process = when (mode) {
            Mode.NONE -> return Exec(-1, "", "disabled")
            Mode.ROOT -> runCatching { ProcessBuilder("su", "-c", cmd).start() }.getOrElse { return Exec(-1, "", it.message ?: "su failed") }
            Mode.SHIZUKU -> shizukuProcess(arrayOf("sh", "-c", cmd)) ?: return Exec(-1, "", "Shizuku is not available")
        }
        return runCatching {
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
            val tErr = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) p.destroy()
            tOut.join(2000); tErr.join(2000)
            Exec(if (finished) p.exitValue() else -2, out.toString(), err.toString())
        }.getOrElse { Exec(-1, "", it.message ?: "error") }
    }

    /** Shizuku's process API is not public in v13, it is reached via reflection (stable since v11). */
    private fun shizukuProcess(cmd: Array<String>): Process? = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        m.invoke(null, cmd, null, null) as Process
    }.getOrNull()

    /** Shell-safe single-quoting. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------ features

    private val EXT = listOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "ape", "wv", "aiff", "aif", "dsf", "mka", "webm")

    /** Music files inside Android/data of other apps (paths only, nothing is read yet). */
    fun findInAppData(mode: Mode, root: File): List<String> {
        val base = File(root, "Android/data").absolutePath
        val names = EXT.joinToString(" -o ") { "-iname '*.$it'" }
        val r = exec(mode, "find ${q(base)} -type f \\( $names \\) -size +300k 2>/dev/null", 120)
        return r.out.lineSequence().map { it.trim() }.filter { it.startsWith("/") }.toList()
    }

    /** Copies a protected file to [dest] (a place the player can read). */
    fun copyOut(mode: Mode, src: String, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val r = exec(mode, "cp ${q(src)} ${q(dest.absolutePath)} && chmod 666 ${q(dest.absolutePath)}", 300)
        return r.ok && dest.isFile && dest.length() > 0
    }

    fun delete(mode: Mode, path: String): Boolean = exec(mode, "rm -f ${q(path)}").ok

    /** Keeps the player alive in the background (works on every Android 9+ ROM). */
    fun keepAlive(mode: Mode, pkg: String): Exec = exec(
        mode,
        "dumpsys deviceidle whitelist +$pkg; cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; cmd appops set $pkg RUN_IN_BACKGROUND allow",
    )

    /** Grants the player's own permissions without going through system screens. */
    fun grantOwnPermissions(mode: Mode, pkg: String): Exec = exec(
        mode,
        "appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow; pm grant $pkg android.permission.POST_NOTIFICATIONS; " +
            "pm grant $pkg android.permission.READ_MEDIA_AUDIO; pm grant $pkg android.permission.READ_EXTERNAL_STORAGE",
    )
}
