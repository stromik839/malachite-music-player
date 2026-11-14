package app.malachite.core

import app.malachite.core.fs.DataProtection
import app.malachite.core.fs.Sealed
import app.malachite.core.library.LibraryData
import app.malachite.core.library.LibraryStore
import app.malachite.core.library.TrackUserData
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SealedTest {
    private val password = "малахит-2026".toCharArray()

    @AfterTest
    fun reset() { Sealed.key = null; Sealed.sealNew = false }

    // fewer rounds in tests: the same code, just faster
    private fun create() = DataProtection.create(password.copyOf(), iterations = 2000)

    @Test
    fun sealedTextOpensOnlyWithTheRightKey() {
        val (d, key) = create()
        val sealed = Sealed.seal("история прослушиваний".toByteArray(), key)
        assertTrue(Sealed.isSealed(sealed))
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("история"))
        assertEquals("история прослушиваний", String(Sealed.open(sealed, key)))
        val other = DataProtection.create("другой".toCharArray(), iterations = 2000).second
        assertFailsWith<Sealed.Locked> { Sealed.open(sealed, other) }
        assertFailsWith<Sealed.Locked> { Sealed.open(sealed, null) }
        // a changed byte is noticed, not read as rubbish
        val broken = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<Sealed.Locked> { Sealed.open(broken, key) }
        assertNotNull(DataProtection.keyFor(d, password.copyOf()))
        assertNull(DataProtection.keyFor(d, "не тот".toCharArray()))
    }

    @Test
    fun theLibraryIsSealedAndNeverReplacedWhileLocked() {
        val root = Files.createTempDirectory("mal").toFile()
        try {
            val store = LibraryStore(root).apply { ensureCreated("test") }
            store.saveLibrary(LibraryData(tracks = mapOf("Кино/Группа крови.mp3" to TrackUserData(favorite = true, playCount = 7))))
            val (d, key) = create()
            DataProtection.write(store.dataDir, d)
            Sealed.key = key
            Sealed.sealNew = true
            val files = DataProtection.personalFiles(root)
            assertEquals(1, DataProtection.rewrite(files, readKey = null, writeKey = key))
            val raw = File(store.dataDir, "library.json").readBytes()
            assertTrue(Sealed.isSealed(raw))
            assertFalse(String(raw, Charsets.ISO_8859_1).contains("crovi") || String(raw, Charsets.UTF_8).contains("Группа"))
            assertEquals(7, store.loadLibrary().user("Кино/Группа крови.mp3").playCount)

            // locked (a new install): reading fails loudly instead of giving an empty library
            Sealed.key = null
            assertFailsWith<Sealed.Locked> { store.loadLibrary() }
            assertFailsWith<Sealed.Locked> { store.updateLibrary { it } }
            assertTrue(Sealed.isSealed(File(store.dataDir, "library.json")))

            // unlocked by the password again, and switched off: plain once more
            val again = DataProtection.keyFor(DataProtection.read(store.dataDir)!!, password.copyOf())!!
            Sealed.key = again
            Sealed.sealNew = false
            DataProtection.rewrite(DataProtection.personalFiles(root), readKey = again, writeKey = null)
            assertFalse(Sealed.isSealed(File(store.dataDir, "library.json")))
            Sealed.key = null
            assertEquals(7, store.loadLibrary().user("Кино/Группа крови.mp3").playCount)
        } finally {
            root.deleteRecursively()
        }
    }
}
