package com.cosmos.unreddit.data.feed

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: a manual pull-to-refresh arriving while a refresh cycle is
 * ALREADY running must be dropped, not cancel + restart it (2026-10-02,
 * Simo: "I've cancelled so many refreshes by mistake already. The refresh
 * feature should only work if the refresh isn't already happening").
 *
 * The policy lives in [admitRefresh]; [FeedCoordinator.refresh] consults it
 * first and returns the running cycle's Job untouched on DROP. These tests
 * pin the full decision table so either side can't regress silently:
 * a loosened DROP would re-open the accidental-cancel bug, a tightened one
 * would make deliberate sort/sub/profile changes stop superseding an
 * in-flight refresh.
 */
class AdmitRefreshTest {

    private fun runningCycle(): Job = Job() // isActive == true

    private fun finishedCycle(): Job = Job().apply { cancel() } // isActive == false

    @Test
    fun manualPullWhileRefreshIsRunningIsDropped() {
        // THE bug case: user pulls again (or double-pulls) while the first
        // refresh is still fanning out. It must NOT restart the refresh.
        assertEquals(
            RefreshAdmission.DROP,
            admitRefresh(manual = true, activeCycle = runningCycle())
        )
    }

    @Test
    fun manualPullWithNoRefreshRunningProceeds() {
        // Idle feed: the first pull must always start a refresh.
        assertEquals(
            RefreshAdmission.PROCEED,
            admitRefresh(manual = true, activeCycle = null)
        )
    }

    @Test
    fun manualPullRightAfterCompletionProceeds() {
        // The previous cycle finished (job no longer active): the user's new
        // pull must start a fresh refresh — a stale finished job must not
        // block it.
        assertEquals(
            RefreshAdmission.PROCEED,
            admitRefresh(manual = true, activeCycle = finishedCycle())
        )
    }

    @Test
    fun nonManualTriggerWhileRefreshIsRunningStillSupersedes() {
        // A deliberate change (sort, sub list, profile, NSFW) arriving while a
        // cycle is in flight must still cancel + restart it, exactly as before
        // this guard: those triggers carry the new user intent.
        assertEquals(
            RefreshAdmission.PROCEED,
            admitRefresh(manual = false, activeCycle = runningCycle())
        )
    }

    @Test
    fun nonManualTriggerIdleProceeds() {
        assertEquals(
            RefreshAdmission.PROCEED,
            admitRefresh(manual = false, activeCycle = null)
        )
    }

    @Test
    fun aGenuinelyActiveCoroutineJobCountsAsRunning() {
        // Use a real in-flight coroutine (not just a Job sentinel): while its
        // body is suspended, isActive is true, so a manual pull is dropped.
        val dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        val inFlight = kotlinx.coroutines.CoroutineScope(dispatcher).launch {
            delay(30_000L)
        }
        try {
            assertTrue("precondition: the job must actually be running", inFlight.isActive)
            assertEquals(RefreshAdmission.DROP, admitRefresh(manual = true, activeCycle = inFlight))
            assertEquals(RefreshAdmission.PROCEED, admitRefresh(manual = false, activeCycle = inFlight))
        } finally {
            inFlight.cancel()
        }
    }
}
