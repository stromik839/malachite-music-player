package app.malachite.core

import app.malachite.core.library.LibraryStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** "Heal everything" wrote its queue file from ten tasks at once and some writes failed. */
class AtomicWriteTest {
    @Test
    fun manyWritersOfOneFileAllSucceed() {
        val dir = Files.createTempDirectory("atomic").toFile()
        val f = File(dir, "heal-queue.json")
        val payloads = (0 until 10).map { i -> "payload-$i-".repeat(2000).toByteArray() }
        val pool = Executors.newFixedThreadPool(10)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        for (p in payloads) {
            pool.execute {
                start.await()
                repeat(20) { runCatching { LibraryStore.writeAtomicBytes(f, p) }.onFailure { errors += it } }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertTrue(errors.isEmpty(), "writes failed: ${errors.firstOrNull()}")
        val written = f.readBytes()
        assertTrue(payloads.any { it.contentEquals(written) }, "the file holds a mix of writes")
        dir.deleteRecursively()
    }
}
