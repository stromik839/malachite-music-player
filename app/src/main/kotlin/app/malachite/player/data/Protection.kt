package app.malachite.player.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.malachite.core.fs.DataProtection
import app.malachite.core.fs.Sealed
import app.malachite.core.library.LibraryLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password protection of the player's own data in the main folder: listening history and
 * favourites, playlists, statistics, favourite lines, unfinished syncs. Music files, their
 * names, lyrics and covers stay as they are.
 *
 * The key comes from the password (see [Sealed]). So that the player opens without asking every
 * time, the key is also kept on this phone, wrapped by a key of the Android keystore that never
 * leaves the phone's secure storage. After a reinstall or on another phone that wrapped copy is
 * gone and the password is asked once – the data itself is never lost with the app.
 */
object Protection {
    enum class State { OFF, LOCKED, ON }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Progress of switching on / off / changing the password: done, total, file name; null when idle. */
    data class Work(val title: String, val done: Int, val total: Int, val item: String)

    private val _work = MutableStateFlow<Work?>(null)
    val work: StateFlow<Work?> = _work.asStateFlow()

    private const val ALIAS = "malachite_data_protection"
    private fun dataDir(root: File) = File(root, LibraryLayout.DATA_DIR)
    private fun wrappedFile(context: Context) = File(context.filesDir, "protection.key")

    fun isProtected(root: File?): Boolean = root != null && DataProtection.descriptorFile(dataDir(root)).isFile

    /**
     * Before a main folder is opened: an unprotected folder is simply OFF; a protected one is
     * unlocked with the key kept on this phone, or stays LOCKED until the password is entered.
     */
    fun prepare(context: Context, root: File, askEveryStart: Boolean): State {
        val d = DataProtection.read(dataDir(root))
        if (d == null) {
            Sealed.key = null
            Sealed.sealNew = false
            return set(State.OFF)
        }
        val kept = if (askEveryStart) null else loadWrapped(context)?.takeIf { DataProtection.fits(d, it) }
        if (kept == null && Sealed.key?.let { DataProtection.fits(d, it) } == true) return set(State.ON) // unlocked in this run
        if (kept == null) {
            Sealed.key = null
            Sealed.sealNew = false
            return set(State.LOCKED)
        }
        Sealed.key = kept
        Sealed.sealNew = true
        return set(State.ON)
    }

    private fun set(s: State): State { _state.value = s; return s }

    /** True when [password] fits; the folder is unlocked then. Slow on purpose – call off the main thread. */
    fun unlock(context: Context, root: File, password: CharArray, askEveryStart: Boolean): Boolean {
        val d = DataProtection.read(dataDir(root)) ?: return true
        val key = DataProtection.keyFor(d, password) ?: return false
        Sealed.key = key
        Sealed.sealNew = true
        if (askEveryStart) wrappedFile(context).delete() else saveWrapped(context, key)
        set(State.ON)
        return true
    }

    /** Switches protection on: every personal file is sealed with a key made from [password]. */
    fun enable(context: Context, root: File, password: CharArray, askEveryStart: Boolean, resavePlaylists: () -> Unit) {
        val (d, key) = DataProtection.create(password)
        // the descriptor and the key first: from now on whatever the player writes is sealed,
        // and an interrupted conversion is finished by simply going on
        DataProtection.write(dataDir(root), d)
        if (!askEveryStart) saveWrapped(context, key)
        Sealed.key = key
        Sealed.sealNew = true
        set(State.ON)
        convert(tr("Включаю защиту"), root, readKey = key, writeKey = key)
        // playlists lose their readable .m3u8 twins
        resavePlaylists()
    }

    /** Switches protection off: the files are written plain again. Needs the folder unlocked. */
    fun disable(context: Context, root: File, resavePlaylists: () -> Unit) {
        val key = Sealed.key ?: throw Sealed.Locked()
        Sealed.sealNew = false
        convert(tr("Выключаю защиту"), root, readKey = key, writeKey = null)
        DataProtection.descriptorFile(dataDir(root)).delete()
        wrappedFile(context).delete()
        disableBiometric(context)
        Sealed.key = null
        set(State.OFF)
        // the playlists get their .m3u8 twins back
        resavePlaylists()
    }

    /** A new password: the files are sealed again with the new key. False if [old] is wrong. */
    fun changePassword(context: Context, root: File, old: CharArray, new: CharArray, askEveryStart: Boolean): Boolean {
        val d = DataProtection.read(dataDir(root)) ?: return false
        val oldKey = DataProtection.keyFor(d, old) ?: return false
        val (nd, newKey) = DataProtection.create(new)
        // files sealed with the old key are read with it; the new descriptor goes last, after all are converted
        Sealed.key = oldKey
        Sealed.sealNew = false
        convert(tr("Меняю пароль"), root, readKey = oldKey, writeKey = newKey)
        DataProtection.write(dataDir(root), nd)
        Sealed.key = newKey
        Sealed.sealNew = true
        // anything the player wrote plain during the change is sealed now as well
        convert(tr("Меняю пароль"), root, readKey = newKey, writeKey = newKey)
        if (askEveryStart) wrappedFile(context).delete() else saveWrapped(context, newKey)
        // the fingerprint kept the old key: it is switched on again with the new one
        disableBiometric(context)
        return true
    }

    /** "Ask the password at every start" switched: the copy of the key on this phone is kept or dropped. */
    fun rememberOnThisPhone(context: Context, remember: Boolean) {
        val key = Sealed.key
        if (remember && key != null && _state.value == State.ON) saveWrapped(context, key) else if (!remember) wrappedFile(context).delete()
    }

    /**
     * The password is forgotten: the protected files are put aside (`.malachite/locked-…`, they stay
     * unreadable without the password) and the player starts its data anew. Music is not touched.
     */
    fun startOver(context: Context, root: File) {
        val aside = File(dataDir(root), "locked-" + System.currentTimeMillis())
        aside.mkdirs()
        for (f in DataProtection.personalFiles(root)) if (Sealed.isSealed(f)) f.renameTo(File(aside, f.name))
        DataProtection.descriptorFile(dataDir(root)).renameTo(File(aside, "protection.json"))
        wrappedFile(context).delete()
        disableBiometric(context)
        Sealed.key = null
        Sealed.sealNew = false
        set(State.OFF)
    }

    private fun convert(title: String, root: File, readKey: SecretKey?, writeKey: SecretKey?) {
        try {
            DataProtection.rewrite(DataProtection.personalFiles(root), readKey, writeKey) { done, total, item ->
                _work.value = Work(title, done, total, item)
            }
        } finally {
            _work.value = null
        }
    }

    // ------------------------------------------------------------------ fingerprint

    private const val BIO_ALIAS = "malachite_data_biometric"
    private fun bioFile(context: Context) = File(context.filesDir, "protection.bio")

    /** The phone has a strong biometric (fingerprint and the like) set up, and Android can use it here (10+). */
    fun biometricAvailable(context: Context): Boolean = android.os.Build.VERSION.SDK_INT >= 29 && runCatching {
        val bm = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java) ?: return false
        when {
            android.os.Build.VERSION.SDK_INT >= 30 ->
                bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            android.os.Build.VERSION.SDK_INT == 29 ->
                @Suppress("DEPRECATION") (bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS)
            else -> false
        }
    }.getOrDefault(false)

    fun biometricOn(context: Context): Boolean = bioFile(context).isFile

    /**
     * A key of the Android keystore that works only right after a fingerprint: the copy of the
     * data key kept with it opens only through the system's fingerprint prompt. A new fingerprint
     * added to the phone makes it unusable (the password is needed then).
     */
    private fun biometricKey(fresh: Boolean): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (fresh) runCatching { ks.deleteEntry(BIO_ALIAS) }
        (ks.getKey(BIO_ALIAS, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (android.os.Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    /** The cipher the fingerprint prompt confirms, for switching fingerprint unlock on. */
    fun biometricCipherToEnable(): Cipher? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, biometricKey(fresh = true)) }
    }.getOrNull()

    /** After the fingerprint was confirmed: the data key is kept behind it. */
    fun finishBiometricEnable(context: Context, cipher: Cipher): Boolean = runCatching {
        val key = Sealed.key ?: return false
        app.malachite.core.library.LibraryStore.writeAtomicBytes(bioFile(context), cipher.iv + cipher.doFinal(key.encoded))
        true
    }.getOrDefault(false)

    /** The cipher for unlocking by fingerprint, or null when it is off or no longer valid (a new fingerprint was added). */
    fun biometricCipherToUnlock(context: Context): Cipher? {
        val f = bioFile(context)
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, biometricKey(fresh = false), GCMParameterSpec(128, bytes, 0, 12)) }
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            disableBiometric(context)
            null
        } catch (e: Exception) {
            null
        }
    }

    /** After the fingerprint was confirmed: the data key is taken out and the folder unlocked. */
    fun finishBiometricUnlock(context: Context, root: File, cipher: Cipher): Boolean = runCatching {
        val bytes = bioFile(context).takeIf { it.isFile }?.readBytes() ?: return false
        val key = SecretKeySpec(cipher.doFinal(bytes, 12, bytes.size - 12), "AES")
        val d = DataProtection.read(dataDir(root)) ?: return false
        if (!DataProtection.fits(d, key)) return false
        Sealed.key = key
        Sealed.sealNew = true
        set(State.ON)
        true
    }.getOrDefault(false)

    fun disableBiometric(context: Context) {
        bioFile(context).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(BIO_ALIAS) }
    }

    // ------------------------------------------------------------------ the key kept on this phone

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun saveWrapped(context: Context, key: SecretKey) {
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, keystoreKey())
            val out = c.iv + c.doFinal(key.encoded)
            app.malachite.core.library.LibraryStore.writeAtomicBytes(wrappedFile(context), out)
        }.onFailure { AppLog.e("protection", "Ключ не сохранился на телефоне — пароль будут спрашивать при запуске", it) }
    }

    private fun loadWrapped(context: Context): SecretKey? = runCatching {
        val f = wrappedFile(context)
        if (!f.isFile) return null
        val bytes = f.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes, 0, 12))
        SecretKeySpec(c.doFinal(bytes, 12, bytes.size - 12), "AES")
    }.getOrNull()

    private fun tr(s: String) = app.malachite.core.i18n.tr(s)
}
