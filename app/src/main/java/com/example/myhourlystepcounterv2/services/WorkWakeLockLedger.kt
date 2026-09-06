package com.example.myhourlystepcounterv2.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reference ledger for a short-lived work wake lock.
 *
 * Each unit of work (hour-boundary save, missed-boundary backfill) calls [acquire] and gets
 * back a token, then passes that exact token to [release] when it finishes. The underlying
 * lock is taken on the first outstanding reference ([onFirstAcquire]) and dropped when the
 * last one retires ([onLastRelease]), so overlapping work shares one lock.
 *
 * Two properties matter, and both come from tokens rather than from a bare counter:
 *
 * - **A reference can only be retired by its own owner.** [release] removes the entry for the
 *   token it was given; a token that is already gone (retired by its own timeout) is a no-op.
 *   No code path can retire a reference it did not create, so a completed or timed-out work
 *   item can never decrement a live sibling's reference or reach into a later lock generation.
 * - **Each reference expires on its own clock.** Every [acquire] schedules its own backstop
 *   for [timeoutMs] from that acquire, which retires only that one reference. A stalled work
 *   item therefore cannot pin the CPU awake indefinitely, and a healthy sibling is never torn
 *   down early by someone else's timer.
 *
 * All mutation is serialized on the ledger's monitor; the callbacks run under that lock and
 * must not block or suspend.
 */
class WorkWakeLockLedger(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val onFirstAcquire: () -> Unit,
    private val onLastRelease: () -> Unit,
    private val onTimeout: (reason: String) -> Unit = {}
) {
    private val lock = Any()
    private val outstanding = LinkedHashMap<Long, Job>()
    private var nextToken = 0L

    /** Number of references currently outstanding. */
    val referenceCount: Int
        get() = synchronized(lock) { outstanding.size }

    /**
     * Take a reference, taking the underlying lock if this is the first one. Returns the token
     * that must be handed to [release]; the caller owns exactly this reference and no other.
     */
    fun acquire(reason: String): Long = synchronized(lock) {
        val token = nextToken++
        if (outstanding.isEmpty()) {
            onFirstAcquire()
        }
        outstanding[token] = scope.launch {
            delay(timeoutMs)
            synchronized(lock) {
                // Only fires if this reference is still outstanding: a normal release for this
                // token cancels this job, so a stale timer can never retire anything.
                if (outstanding.remove(token) != null) {
                    onTimeout(reason)
                    releaseIfIdle()
                }
            }
        }
        token
    }

    /**
     * Retire the reference identified by [token], dropping the underlying lock if it was the
     * last one. A token that is not outstanding — already released, or retired by its own
     * timeout — is a no-op.
     */
    fun release(token: Long) = synchronized(lock) {
        val backstop = outstanding.remove(token) ?: return@synchronized
        backstop.cancel()
        releaseIfIdle()
    }

    /**
     * Drop every outstanding reference and the underlying lock (service stop/destroy).
     */
    fun releaseAll() = synchronized(lock) {
        outstanding.values.forEach { it.cancel() }
        outstanding.clear()
        // Unconditional: [onLastRelease] is idempotent, so this also clears a lock that
        // somehow outlived its references.
        onLastRelease()
    }

    private fun releaseIfIdle() {
        if (outstanding.isEmpty()) {
            onLastRelease()
        }
    }
}
