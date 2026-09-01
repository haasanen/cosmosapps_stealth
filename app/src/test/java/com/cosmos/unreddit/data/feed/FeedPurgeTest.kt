package com.cosmos.unreddit.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline test of the feed-cache purge policy (the leak guard). The coordinator is the
 * single writer and runs this plan after every refresh cycle; these tests pin the rules:
 * TTL expiry, row cap, and that fresh rows always survive.
 */
class FeedPurgeTest {

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `rows older than the ttl are expired`() {
        val rows = listOf(
            "p1" to now - 13 * hour, // 13 h old  -> expired
            "p2" to now - 11 * hour, // 11 h old  -> kept
            "p3" to now,             // fresh     -> kept
        )
        val plan = FeedPurge.plan(rows, now, ttlMs = 12 * hour, rowCap = 2500)
        assertEquals(listOf("p1"), plan.expiredIds)
        assertTrue(plan.oldestIdsToCut.isEmpty())
    }

    @Test
    fun `exact ttl boundary is kept`() {
        val rows = listOf("p1" to now - 12 * hour)
        val plan = FeedPurge.plan(rows, now, ttlMs = 12 * hour, rowCap = 2500)
        assertTrue(plan.expiredIds.isEmpty())
    }

    @Test
    fun `row cap cuts only the oldest beyond the cap`() {
        // 5 rows, cap 2 -> the 3 oldest are cut.
        val rows = listOf(
            "p1" to now - 5 * hour,
            "p2" to now - 4 * hour,
            "p3" to now - 3 * hour,
            "p4" to now - 2 * hour,
            "p5" to now - 1 * hour,
        )
        val plan = FeedPurge.plan(rows, now, ttlMs = 12 * hour, rowCap = 2)
        assertTrue(plan.expiredIds.isEmpty())
        assertEquals(listOf("p1", "p2", "p3"), plan.oldestIdsToCut)
    }

    @Test
    fun `ttl runs first, cap applies to survivors only`() {
        // 4 rows: 1 expired, 3 fresh. Cap 2 -> 1 of the 3 fresh is cut (the oldest).
        val rows = listOf(
            "p1" to now - 20 * hour,
            "p2" to now - 3 * hour,
            "p3" to now - 2 * hour,
            "p4" to now - 1 * hour,
        )
        val plan = FeedPurge.plan(rows, now, ttlMs = 12 * hour, rowCap = 2)
        assertEquals(listOf("p1"), plan.expiredIds)
        assertEquals(listOf("p2"), plan.oldestIdsToCut)
    }

    @Test
    fun `empty input produces an empty plan`() {
        val plan = FeedPurge.plan(emptyList(), now, ttlMs = 12 * hour, rowCap = 2500)
        assertTrue(plan.expiredIds.isEmpty())
        assertTrue(plan.oldestIdsToCut.isEmpty())
    }

    @Test
    fun `size tripwire forces an aggressive cut`() {
        // Simulates the 25 MB DB tripwire: force the table down to 1000 rows no matter what.
        val rows = (0 until 1200).map { "p$it" to now - it * 1000L } // all fresh
        val plan = FeedPurge.plan(rows, now, ttlMs = 12 * hour, rowCap = 2500, forcedCap = 1000)
        assertTrue(plan.expiredIds.isEmpty())
        assertEquals(200, plan.oldestIdsToCut.size)
        // The 200 oldest are cut (p1199 is oldest: fetched first), the newest survive.
        assertEquals("p1199", plan.oldestIdsToCut.first())
        assertEquals("p1000", plan.oldestIdsToCut.last())
    }
}
