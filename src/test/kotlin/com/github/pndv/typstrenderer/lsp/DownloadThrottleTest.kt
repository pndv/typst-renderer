package com.github.pndv.typstrenderer.lsp

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the tinymist download throttling — [decideDownloadAttempt] and
 * [shouldNotifyDownloadFailure].
 *
 * The regression they exist for (issue #105): with the binary missing, every attempt failed and
 * every failure raised its own balloon, with no back-off between attempts. One session logged
 * **836 download attempts and 830 failure notifications inside seven seconds**.
 *
 * Fixture-free: both functions are pure, so the throttle is driven with explicit clock values
 * rather than by sleeping.
 */
class DownloadThrottleTest {

    private val base = 30_000L
    private val max = 600_000L

    // ---- decideDownloadAttempt ----

    @Test
    fun firstAttempt_proceeds() {
        val d = decideDownloadAttempt(
            isDownloading = false, consecutiveFailures = 0,
            lastFailureAtMs = 0, nowMs = 1_000, baseBackoffMs = base, maxBackoffMs = max,
        )
        assertEquals(DownloadAttempt.Proceed, d)
    }

    @Test
    fun downloadInFlight_isReportedAsAlreadyRunning() { // Distinct from BackOff: a concurrent request is redundant, not throttled, and the
        // caller must not be told to wait for a window that does not apply.
        val d = decideDownloadAttempt(
            isDownloading = true, consecutiveFailures = 0,
            lastFailureAtMs = 0, nowMs = 1_000, baseBackoffMs = base, maxBackoffMs = max,
        )
        assertEquals(DownloadAttempt.AlreadyRunning, d)
    }

    @Test
    fun immediatelyAfterAFailure_backsOff() { // The exact case that produced the storm: a retry arriving milliseconds later.
        val d = decideDownloadAttempt(
            isDownloading = false, consecutiveFailures = 1,
            lastFailureAtMs = 1_000, nowMs = 1_050, baseBackoffMs = base, maxBackoffMs = max,
        )
        assertTrue("expected BackOff, got $d", d is DownloadAttempt.BackOff)
        assertEquals(base - 50, (d as DownloadAttempt.BackOff).remainingMs)
    }

    @Test
    fun afterTheWindowElapses_proceedsAgain() {
        val d = decideDownloadAttempt(
            isDownloading = false, consecutiveFailures = 1,
            lastFailureAtMs = 1_000, nowMs = 1_000 + base, baseBackoffMs = base, maxBackoffMs = max,
        )
        assertEquals(DownloadAttempt.Proceed, d)
    }

    @Test
    fun windowDoublesWithEachConsecutiveFailure() {
        // 1 failure -> base, 2 -> 2x, 3 -> 4x. A persistent failure (offline, unsupported
        // platform, 404 on the pinned asset) must not be retried at a fixed short interval.
        fun windowFor(failures: Int): Long {
            val d = decideDownloadAttempt(
                isDownloading = false, consecutiveFailures = failures,
                lastFailureAtMs = 0, nowMs = 0, baseBackoffMs = base, maxBackoffMs = max,
            )
            return (d as DownloadAttempt.BackOff).remainingMs
        }
        assertEquals(base, windowFor(1))
        assertEquals(base * 2, windowFor(2))
        assertEquals(base * 4, windowFor(3))
    }

    @Test
    fun windowIsCappedAndCannotOverflow() { // A long-running IDE with tinymist permanently unavailable must not shift its way to a
        // negative or absurd window.
        val d = decideDownloadAttempt(
            isDownloading = false, consecutiveFailures = 5_000,
            lastFailureAtMs = 0, nowMs = 0, baseBackoffMs = base, maxBackoffMs = max,
        )
        assertEquals(max, (d as DownloadAttempt.BackOff).remainingMs)
    }

    @Test
    fun successResettingTheStreak_restoresImmediateRetry() { // A success sets consecutiveFailures back to 0, so the next failure is treated as fresh
        // rather than inheriting an old back-off.
        val d = decideDownloadAttempt(
            isDownloading = false, consecutiveFailures = 0,
            lastFailureAtMs = 1_000, nowMs = 1_001, baseBackoffMs = base, maxBackoffMs = max,
        )
        assertEquals(DownloadAttempt.Proceed, d)
    }

    // ---- shouldNotifyDownloadFailure ----

    @Test
    fun onlyTheFirstFailureOfAStreakNotifies() {
        assertTrue("the first failure is the actionable one", shouldNotifyDownloadFailure(1))
        assertFalse(shouldNotifyDownloadFailure(2))
        assertFalse(shouldNotifyDownloadFailure(3))
        assertFalse("this is where the 830 balloons came from", shouldNotifyDownloadFailure(830))
    }
}
