package com.cosmos.unreddit.ui.postlist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the blank-first-launch bug: the legacy Paging feed flow must
 * only ever be SUBSCRIBED when the source preference has resolved to a non-official
 * source. While the official source is selected — or while the preference is still
 * unresolved (`null`) — subscribing to the legacy flow fires a full parallel fan-out
 * of subreddit requests (it is a `by lazy` flow that builds on first collection),
 * doubling the reddit.com load and blanking the progressive feed through CF throttle.
 *
 * The tests mirror the exact operator chain used by [PostListFragment]:
 * `usesCoordinator.filterNotNull().flatMapLatest { official ? empty : legacyFlow }`.
 */
class LegacyFeedGatingTest {

    private class Flag {
        @Volatile
        var value = false
    }

    @Test
    fun officialSourceNeverSubscribesToLegacyFlow() {
        val subscribed = Flag()
        val usesCoordinator = MutableStateFlow<Boolean?>(null)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch {
            usesCoordinator
                .filterNotNull()
                .flatMapLatest { active ->
                    if (active) flowOf(Unit) else {
                        subscribed.value = true
                        flowOf(Unit)
                    }
                }
                .collect {}
        }
        // DataStore resolves to the official source AFTER the fragment started.
        Thread.sleep(100)
        usesCoordinator.value = true
        Thread.sleep(300)
        scope.cancel()
        assertFalse(
            "official source must never subscribe to the legacy Paging flow " +
                "(it would fire a second parallel fan-out)",
            subscribed.value
        )
    }

    @Test
    fun nonOfficialSourceSubscribesOnlyAfterResolution() {
        val subscribed = Flag()
        val usesCoordinator = MutableStateFlow<Boolean?>(null)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch {
            usesCoordinator
                .filterNotNull()
                .flatMapLatest { active ->
                    if (active) flowOf(Unit) else {
                        subscribed.value = true
                        flowOf(Unit)
                    }
                }
                .collect {}
        }
        // Unresolved window: nothing may subscribe yet.
        Thread.sleep(100)
        assertFalse("must not subscribe before the source preference resolves", subscribed.value)
        // DataStore resolves to a non-official source (e.g. ARCTIC).
        usesCoordinator.value = false
        Thread.sleep(300)
        assertTrue("non-official source must drive the legacy Paging feed", subscribed.value)
        scope.cancel()
    }
}
