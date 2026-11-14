package app.malachite.core.fs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password protection of the player's own data files (listening history, favourites, playlists,
 * statistics…). The music files are not touched.
 *
 * A file is sealed with AES-256-GCM: `MALSEAL1` + a random 12-byte nonce + the encrypted text
 * with its 16-byte tag. The key comes from the password through PBKDF2-HMAC-SHA256 with a random
 * salt and [ITERATIONS] rounds, so guessing passwords is slow. A wrong key or a changed byte is
 * detected (the tag), never read as rubbish.
 *
 * Reading accepts sealed and plain files alike, so a folder can be half-way converted (a dead
 * battery during the conversion) and still read correctly.
 */
object Sealed {
    private val MAGIC = "MALSEAL1".toByteArray(Charsets.US_ASCII)
    private const val NONCE = 12
    private const val TAG_BITS = 128
    const val ITERATIONS = 600_000

    /** The data is sealed and the key is not known (the password was not entered). */
    class Locked : IOException("The player's data is protected with a password and is locked")

    /** The key of the unlocked folder, null while locked or unprotected. */
    @Volatile var key: SecretKey? = null

    /** True while protection is on: files the player writes are sealed. */
    @Volatile var sealNew: Boolean = false

    private val random = SecureRandom()

    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size + NONCE + TAG_BITS / 8 && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun isSealed(f: File): Boolean = f.isFile && runCatching {
        f.inputStream().use { s -> val b = ByteArray(MAGIC.size); s.read(b) == MAGIC.size && b.contentEquals(MAGIC) }
    }.getOrDefault(false)

    fun seal(plain: ByteArray, k: SecretKey): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(MAGIC)
        return MAGIC + nonce + c.doFinal(plain)
    }

    /** The plain content of [bytes]; plain input is returned as it is. Throws [Locked] without the right key. */
    fun open(bytes: ByteArray, k: SecretKey? = key): ByteArray {
        if (!isSealed(bytes)) return bytes
        val kk = k ?: throw Locked()
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, kk, GCMParameterSpec(TAG_BITS, bytes, MAGIC.size, NONCE))
            c.updateAAD(MAGIC)
            c.doFinal(bytes, MAGIC.size + NONCE, bytes.size - MAGIC.size - NONCE)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw Locked()
        }
    }

    fun readText(f: File): String = String(open(f.readBytes()), Charsets.UTF_8)

    /** What to write for [text]: sealed while protection is on. Throws [Locked] if it is on without a key. */
    fun encodeForWrite(text: String): ByteArray {
        val plain = text.toByteArray(Charsets.UTF_8)
        if (!sealNew) return plain
        return seal(plain, key ?: throw Locked())
    }

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * The protection of one main folder: `.malachite/protection.json` keeps what is needed to check
 * a password (salt, rounds, a sealed check text) – never the password or the key.
 */
object DataProtection {
    @Serializable
    data class Descriptor(val version: Int = 1, val salt: String, val iterations: Int, val check: String)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private const val CHECK = "malachite-protection-check"

    fun descriptorFile(dataDir: File) = File(dataDir, "protection.json")

    /**
     * Rounds a descriptor may ask for. The upper bound is what matters: a file replaced by another
     * app cannot make unlocking take hours. (Fewer rounds give an attacker nothing: a replaced file
     * only stops the right password from working, it never reveals it.)
     */
    val ROUNDS = 1_000..5_000_000

    fun read(dataDir: File): Descriptor? =
        runCatching { descriptorFile(dataDir).takeIf { it.isFile }?.let { json.decodeFromString(Descriptor.serializer(), it.readText()) } }
            .getOrNull()?.takeIf { d -> d.iterations in ROUNDS && runCatching { Base64.getDecoder().decode(d.salt).size in 16..64 }.getOrDefault(false) }

    fun write(dataDir: File, d: Descriptor) = app.malachite.core.library.LibraryStore.writeAtomic(descriptorFile(dataDir), json.encodeToString(Descriptor.serializer(), d))

    /** A new descriptor and the key for [password]. */
    fun create(password: CharArray, iterations: Int = Sealed.ITERATIONS): Pair<Descriptor, SecretKey> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Sealed.deriveKey(password, salt, iterations)
        val check = Sealed.seal(CHECK.toByteArray(), key)
        val enc = Base64.getEncoder()
        return Descriptor(salt = enc.encodeToString(salt), iterations = iterations, check = enc.encodeToString(check)) to key
    }

    /** The key if [password] is right, else null. Slow on purpose (the rounds). */
    fun keyFor(d: Descriptor, password: CharArray): SecretKey? {
        if (d.iterations !in ROUNDS) return null
        val key = Sealed.deriveKey(password, Base64.getDecoder().decode(d.salt), d.iterations)
        return key.takeIf { fits(d, it) }
    }

    fun fits(d: Descriptor, key: SecretKey): Boolean = runCatching {
        String(Sealed.open(Base64.getDecoder().decode(d.check), key)) == CHECK
    }.getOrDefault(false)

    /** The player's files that hold personal data (the music, lyrics and covers are not among them). */
    fun personalFiles(root: File): List<File> {
        val data = File(root, app.malachite.core.library.LibraryLayout.DATA_DIR)
        val playlists = File(root, app.malachite.core.library.LibraryLayout.PLAYLISTS_DIR)
        return listOf(File(data, "library.json"), File(data, "stats.json"), File(data, "shelf.json"), File(data, "heal-queue.json")).filter { it.isFile } +
            File(data, "sync-drafts").listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty() +
            playlists.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
    }

    /**
     * Rewrites [files]: read with [readKey] (plain files need none), written sealed with
     * [writeKey] or plain when it is null. One file at a time, each through a temporary file, so
     * an interruption leaves every file whole – sealed or plain, both readable.
     */
    fun rewrite(files: List<File>, readKey: SecretKey?, writeKey: SecretKey?, progress: (Int, Int, String) -> Unit = { _, _, _ -> }): Int {
        var done = 0
        for ((i, f) in files.withIndex()) {
            progress(i, files.size, f.name)
            if (!f.isFile) continue
            val plain = Sealed.open(f.readBytes(), readKey)
            val out = if (writeKey != null) Sealed.seal(plain, writeKey) else plain
            app.malachite.core.library.LibraryStore.writeAtomicBytes(f, out)
            done++
        }
        progress(files.size, files.size, "")
        return done
    }
}
