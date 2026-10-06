package app.morsecode.core.storage.transfer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafCommitProcessLocksTest {
    @Test
    fun `same commit serializes while a different commit can proceed and entries are released`() {
        val pool = Executors.newFixedThreadPool(3)
        val firstHasLock = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondHasLock = CountDownLatch(1)
        val commitId = PartialIdentity("process-lock-one")
        val otherCommitId = PartialIdentity("process-lock-two")

        try {
            val first = pool.submit<String> {
                SafCommitProcessLocks.withCommitLock(commitId) {
                    firstHasLock.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    "first"
                }
            }
            assertTrue(firstHasLock.await(5, TimeUnit.SECONDS))

            val second = pool.submit<String> {
                secondAttempting.countDown()
                SafCommitProcessLocks.withCommitLock(commitId) {
                    secondHasLock.countDown()
                    "second"
                }
            }
            assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
            assertFalse("the second holder must wait for the same key", secondHasLock.await(100, TimeUnit.MILLISECONDS))

            val unrelated = pool.submit<String> {
                SafCommitProcessLocks.withCommitLock(otherCommitId) { "unrelated" }
            }
            assertEquals("unrelated", unrelated.get(5, TimeUnit.SECONDS))

            releaseFirst.countDown()
            assertEquals("first", first.get(5, TimeUnit.SECONDS))
            assertTrue(secondHasLock.await(5, TimeUnit.SECONDS))
            assertEquals("second", second.get(5, TimeUnit.SECONDS))
            assertEquals(0, SafCommitProcessLocks.activeKeyCount())
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
