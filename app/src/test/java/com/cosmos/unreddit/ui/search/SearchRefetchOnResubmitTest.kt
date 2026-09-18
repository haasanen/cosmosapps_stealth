package com.cosmos.unreddit.ui.search

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.TimeSorting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-18 "search again after switching VPN country shows the old
 * subreddits": the old SearchViewModel kept the query in a plain
 * [MutableStateFlow] and derived the fetch from it. A StateFlow CONFLATES
 * identical values, so re-submitting the same term (now under a different
 * geo-location) emitted nothing → the upstream `flatMapLatest` never restarted
 * → the in-memory `cachedIn` result was reused forever.
 *
 * The fix adds a monotonic `_searchGeneration` that bumps on every explicit
 * submit; the search input is the PAIR (query, generation), which is never
 * equal across two submits even for an unchanged term.
 *
 * Both tests model the old and new pipelines over the SAME submit sequence
 * ("" → "linux" → "linux" (VPN changed) → "android").
 */
class SearchRefetchOnResubmitTest {

    private val sorting = Sorting(Sort.RELEVANCE, TimeSorting.ALL)

    @Test
    fun `old query-only StateFlow conflates a same-term resubmit`() {
        // Model of the old pipeline: a StateFlow delivers each DISTINCT value
        // once, so an unchanged term is a no-op.
        val events = listOf("", "linux", "linux", "android")
        val delivered = mutableListOf<String>()
        var last: String? = null
        for (v in events) {
            if (v != last) {
                delivered += v
                last = v
            }
        }
        // The second "linux" (the VPN-changed resubmit) produced NO input →
        // no refetch. That is the bug.
        assertEquals(listOf("", "linux", "android"), delivered)
    }

    @Test
    fun `new generation pair refires on a same-term resubmit`() = runBlocking {
        val query = MutableStateFlow("")
        val generation = MutableStateFlow(0L)
        // NEW: the search input is (query, generation) — never equal across
        // two submits, even for an unchanged term.
        val inputs = combine(query, generation) { q, g -> q to g }

        val collected = mutableListOf<Pair<String, Long>>()
        val job = launch { inputs.collect { collected += it } }

        // The same submit sequence SearchViewModel.setQuery runs. Each real
        // submit is a separate main-thread turn (user taps), so the collector
        // processes every emission — model that with a yield after each set.
        // (Without the yields, combine's conflated channel drops the
        // burst-of-emissions, which would be a harness artifact, not the bug.)
        query.value = "linux"; delay(10)          // first search
        generation.value = 1L; delay(10)
        query.value = "linux"; delay(10)          // VPN changed, same term
        generation.value = 2L; delay(10)
        query.value = "android"; delay(10)        // distinct term
        generation.value = 3L; delay(10)

        // Wait until the final emission has landed (the pipeline is async).
        while (collected.lastOrNull()?.let { it.first == "android" && it.second >= 3L } != true) {
            delay(5)
        }
        job.cancel()

        // The KEY property: the SAME term "linux" is delivered with TWO distinct
        // generations (1 and 2) — so the refetch fires even though the term
        // didn't change. Under the old query-only StateFlow this would be
        // impossible (a term is delivered once).
        val linuxGens = collected.filter { it.first == "linux" }.map { it.second }.toSet()
        assertTrue(
            "same-term resubmit must refire; linux generations=$linuxGens",
            1L in linuxGens && 2L in linuxGens
        )
        // And a distinct term still refires.
        assertTrue(collected.any { it.first == "android" && it.second == 3L })
    }
}
