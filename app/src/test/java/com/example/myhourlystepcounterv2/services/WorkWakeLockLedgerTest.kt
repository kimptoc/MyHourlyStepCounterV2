package com.example.myhourlystepcounterv2.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the short-lived work wake lock's reference ledger.
 *
 * These cover the failure modes that a continuously-held lock was replaced with: overlapping
 * hour-boundary and missed-boundary work sharing one lock, one work item stalling, and a
 * stalled item finishing later and releasing a token that has already been retired.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkWakeLockLedgerTest {

    private val timeoutMs = 120_000L

    /** Records the underlying framework lock's held state, the way the service's callbacks do. */
    private class FakeLock {
        var held = false
            private set
        var acquireCount = 0
            private set
        var releaseCount = 0
            private set

        fun acquire() {
            held = true
            acquireCount++
        }

        fun release() {
            held = false
            releaseCount++
        }
    }

    private fun ledgerWith(lock: FakeLock, scope: kotlinx.coroutines.CoroutineScope) =
        WorkWakeLockLedger(
            scope = scope,
            timeoutMs = timeoutMs,
            onFirstAcquire = { lock.acquire() },
            onLastRelease = { lock.release() }
        )

    @Test
    fun singleWorkItem_holdsLockForWorkThenReleases() = runTest {
        val lock = FakeLock()
        val ledger = ledgerWith(lock, this)

        val token = ledger.acquire("hour boundary")!!
        assertTrue("lock must be held while work runs", lock.held)

        ledger.release(token)
        assertFalse("lock must be released when work finishes", lock.held)
        assertEquals(0, ledger.referenceCount)
        assertEquals(1, lock.acquireCount)
        assertEquals(1, lock.releaseCount)
    }

    @Test
    fun overlappingWorkItems_shareOneLock_releasedOnlyAfterBoth() = runTest {
        val lock = FakeLock()
        val ledger = ledgerWith(lock, this)

        val boundary = ledger.acquire("hour boundary")!!
        val backfill = ledger.acquire("missed-boundary check")!!
        assertEquals(2, ledger.referenceCount)
        assertEquals("overlapping work must share one framework lock", 1, lock.acquireCount)

        ledger.release(boundary)
        assertTrue("sibling still working — lock must stay held", lock.held)

        ledger.release(backfill)
        assertFalse(lock.held)
        assertEquals(1, lock.releaseCount)
    }

    @Test
    fun stalledWorkItem_doesNotTearDownLiveSiblingsLock() = runTest {
        val lock = FakeLock()
        val ledger = ledgerWith(lock, this)

        // Backfill stalls; a boundary save starts a minute later and finishes normally.
        val stalled = ledger.acquire("missed-boundary check")!!
        advanceTimeBy(60_000)
        val live = ledger.acquire("hour boundary")!!

        // The stalled item's own backstop fires. It must retire only that reference.
        advanceTimeBy(60_001)
        assertEquals("only the stalled reference is retired", 1, ledger.referenceCount)
        assertTrue("live sibling must keep the lock", lock.held)
        assertEquals(0, lock.releaseCount)

        ledger.release(live)
        assertFalse(lock.held)

        // The stalled item eventually completes and releases its already-retired token.
        ledger.release(stalled)
        assertEquals("stale release must not double-release", 1, lock.releaseCount)
        assertEquals(0, ledger.referenceCount)
    }

    @Test
    fun timedOutToken_cannotReleaseALaterLockGeneration() = runTest {
        val lock = FakeLock()
        val ledger = ledgerWith(lock, this)

        val stalled = ledger.acquire("missed-boundary check")!!
        advanceTimeBy(timeoutMs + 1)
        assertFalse("stalled work must not pin the CPU past the timeout", lock.held)
        assertEquals(0, ledger.referenceCount)

        // A new generation of work takes a fresh lock.
        val next = ledger.acquire("hour boundary")!!
        assertTrue(lock.held)

        // The stalled item finally finishes and releases its stale token: it must not touch
        // the new generation's lock.
        ledger.release(stalled)
        assertTrue("stale token must not release a lock it never acquired", lock.held)
        assertEquals(1, ledger.referenceCount)

        ledger.release(next)
        assertFalse(lock.held)
    }

    @Test
    fun normalRelease_cancelsBackstop_soLaterTimeoutIsNoop() = runTest {
        val lock = FakeLock()
        val ledger = ledgerWith(lock, this)

        val first = ledger.acquire("hour boundary")!!
        ledger.release(first)
        assertFalse(lock.held)

        // A later work item must survive the first item's would-be backstop deadline.
        val second = ledger.acquire("missed-boundary check")!!
        advanceTimeBy(timeoutMs - 1)
        assertTrue("a completed item's timer must not release a newer lock", lock.held)
        assertEquals(1, ledger.referenceCount)

        ledger.release(second)
        assertFalse(lock.held)
        assertEquals(2, lock.acquireCount)
        assertEquals(2, lock.releaseCount)
    }

    @Test
    fun eachReferenceExpiresOnItsOwnClock() = runTest {
        val lock = FakeLock()
        val ledger = ledgerWith(lock, this)

        val first = ledger.acquire("hour boundary")!!
        // A second item starts just before the first one's deadline; it must still get a full
        // timeout of its own rather than inheriting the first item's near-expired clock.
        advanceTimeBy(timeoutMs - 1_000)
        ledger.acquire("missed-boundary check")

        advanceTimeBy(1_001)
        assertTrue("second item's own clock still has time to run", lock.held)
        assertEquals(1, ledger.referenceCount)

        advanceTimeBy(timeoutMs)
        assertFalse("second item's own backstop must eventually fire", lock.held)
        assertEquals(0, ledger.referenceCount)

        // First item's token is long retired; releasing it changes nothing.
        ledger.release(first)
        assertEquals(1, lock.releaseCount)
    }

    @Test
    fun releaseAll_dropsEveryReferenceAndCancelsBackstops() = runTest {
        val lock = FakeLock()
        val ledger = ledgerWith(lock, this)

        ledger.acquire("hour boundary")
        ledger.acquire("missed-boundary check")

        ledger.releaseAll()
        assertFalse("service destroy must drop the lock", lock.held)
        assertEquals(0, ledger.referenceCount)

        // Cancelled backstops must not fire afterwards against a later lock.
        val later = ledger.acquire("hour boundary")!!
        advanceTimeBy(timeoutMs - 1)
        assertTrue(lock.held)
        ledger.release(later)
        assertFalse(lock.held)
    }

    @Test
    fun acquireAfterScopeCancelled_refusesRatherThanHoldingAnUnreleasableLock() = runTest {
        val lock = FakeLock()
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined
        )
        val ledger = ledgerWith(lock, scope)

        // Service teardown cancels the scope, so no backstop scheduled afterwards can ever
        // fire. Taking the lock here would leave it held with nothing to drop it — exactly
        // the runaway wake lock this whole mechanism replaced.
        scope.cancel()

        val token = ledger.acquire("hour boundary")
        assertNull("a reference with no live backstop must be refused", token)
        assertFalse("no lock may be held once nothing can release it", lock.held)
        assertEquals(0, ledger.referenceCount)
        assertEquals(0, lock.acquireCount)
    }
}
