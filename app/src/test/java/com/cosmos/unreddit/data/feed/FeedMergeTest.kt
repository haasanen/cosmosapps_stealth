package com.cosmos.unreddit.data.feed

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.util.extension.interlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedMergeTest {

    private val now = 1_700_000_000_000L

    private fun post(name: String, score: Int = 100, ageMinutes: Long = 60, subreddit: String = "test"): PostData =
        PostData(
            subreddit = subreddit,
            linkFlairRichText = emptyList(),
            authorFlairRichText = null,
            title = "title $name",
            prefixedSubreddit = "r/test",
            name = name,
            ratio = 0.99,
            totalAwards = 0,
            isOC = false,
            flair = null,
            authorFlair = null,
            galleryData = null,
            score = score,
            hint = null,
            isSelf = false,
            crossposts = null,
            domain = "reddit.com",
            selfTextHtml = null,
            suggestedSort = null,
            isArchived = false,
            isOver18 = false,
            mediaPreview = null,
            awardings = emptyList(),
            isSpoiler = false,
            isLocked = false,
            distinguished = null,
            author = "u/tester",
            commentsNumber = 5,
            permalink = "/r/test/comments/abc/$name/",
            isStickied = false,
            url = "https://www.reddit.com/r/test/comments/abc/$name/",
            created = now / 1000 - ageMinutes * 60,
            media = null,
            mediaMetadata = null,
            isRedditGallery = null,
            isVideo = false
        )

    private fun child(data: PostData) = PostChild(data)

    @Test
    fun emptyFreshCycleKeepsCachedFeedNeverBlank() {
        // The whole "never blank" contract: when the network cycle yields nothing
        // (CF hard-challenge, offline, or every sub failing), the merge returns the
        // cached posts so the home feed is not empty. This is the path the
        // FeedCoordinator takes when the fan-out throws or emits no rows.
        val cache = (1..12).map { i -> post("cached$i", score = 100 + i, ageMinutes = 60L * (i + 1)) }
        val out = FeedMerge.merge(emptyList(), cache, Sort.HOT, now)
        assertEquals("cache must survive an empty-fresh cycle", 12, out.size)
        assertTrue(out.map { it.data.name }.toSet() == cache.map { it.name }.toSet())
    }

    @Test
    fun emptyFreshAndEmptyCacheIsEmpty() {
        // First-ever launch with no network: nothing to show is legitimately empty
        // (the UI shows a loading/empty state, not a crash).
        val out = FeedMerge.merge(emptyList(), emptyList(), Sort.HOT, now)
        assertTrue(out.isEmpty())
    }

    @Test
    fun partialFreshCycleStillKeepsCachePostsBelowFresh() {
        // A partial cycle (some subs answered, others failed) still must not drop the
        // cached posts — fresh wins dedup, cache fills in below.
        val fresh = listOf(listOf(child(post("f1", score = 50)), child(post("f2", score = 40))))
        val cache = listOf(post("cached1", score = 90), post("cached2", score = 80))
        val out = FeedMerge.merge(fresh, cache, Sort.HOT, now)
        val names = out.map { it.data.name }
        assertTrue("fresh present", names.containsAll(listOf("f1", "f2")))
        assertTrue("cache survived a partial cycle", names.containsAll(listOf("cached1", "cached2")))
        assertEquals(4, out.size)
    }

    @Test
    fun freshOnlyInterlacesSubLists() {
        val a = listOf(child(post("a1", 500)), child(post("a2", 400)))
        val b = listOf(child(post("b1", 300)), child(post("b2", 200)))
        val out = FeedMerge.merge(listOf(a, b), emptyList(), Sort.HOT, now)
        assertEquals(listOf(a, b).interlace().map { it.data.name }, out.map { it.data.name })
    }

    @Test
    fun freshWinsOverDuplicateCachePost() {
        val fresh = listOf(listOf(child(post("dup", score = 50))))
        val cached = listOf(post("dup", score = 9999), post("cacheOnly"))
        val out = FeedMerge.merge(fresh, cached, Sort.HOT, now)
        assertEquals(50, out.first { it.data.name == "dup" }.data.score)
        assertEquals(2, out.size)
    }

    @Test
    fun highScoreFreshFirstPostStaysOnTop() {
        val fresh = listOf((1..3).map { i -> child(post("f$i", score = 10, ageMinutes = 10)) })
        val cached = (1..5).map { i -> post("c$i", score = 100_000, ageMinutes = 10) }
        val out = FeedMerge.merge(fresh, cached, Sort.HOT, now, maxCachedRank = 30)
        assertEquals("f1", out.first().data.name)
        assertTrue(out.map { it.data.name }.containsAll(listOf("f1", "f2", "f3")))
    }

    @Test
    fun cachedOnlyPostsCappedByMaxCachedOnly() {
        val fresh = listOf(listOf(child(post("f1"))))
        val cached = (1..200).map { i -> post("c$i") }
        val out = FeedMerge.merge(fresh, cached, Sort.HOT, now, maxCachedOnly = 10)
        assertEquals(10, out.count { it.data.name.startsWith("c") })
    }

    @Test
    fun outputCappedByMaxRows() {
        val fresh = listOf((1..600).map { i -> child(post("f$i")) })
        val out = FeedMerge.merge(fresh, emptyList(), Sort.HOT, now, maxRows = 500)
        assertEquals(500, out.size)
    }

    @Test
    fun newSortPutsCachedOnlyAfterFresh() {
        val fresh = listOf(listOf(child(post("f1", ageMinutes = 5))))
        val cached = (1..50).map { i -> post("c$i", ageMinutes = 100) }
        val out = FeedMerge.merge(fresh, cached, Sort.NEW, now)
        assertTrue(
            "cached-only must come after fresh",
            out.indexOfFirst { it.data.name.startsWith("c") } > out.indexOfLast { it.data.name == "f1" }
        )
    }

    @Test
    fun topSortOrdersFreshByScore() {
        val a = listOf(child(post("a1", score = 100)))
        val b = listOf(child(post("b1", score = 500)))
        val out = FeedMerge.merge(listOf(a, b), emptyList(), Sort.TOP, now)
        assertEquals(listOf("b1", "a1"), out.map { it.data.name })
    }

    // ── ISSUE A: failed subs keep their whole cached set ────────────────────────────
    // A FAILED sub (fetch threw / timed out) is NOT the same as a confirmed-empty sub
    // (the fetch succeeded and the sub genuinely has no posts). Only the former keeps
    // its cached posts unconditionally; the latter's cache is dropped like any other
    // stale cache row. These tests pin that distinction.

    @Test
    fun failedSubCacheIsKeptInFull_evenWhenSubYieldsNoFreshPosts() {
        // "gaming" FAILED to fetch (transient error): its fresh list is empty (the
        // worker substitutes emptyList for a null result) but its CACHE must survive
        // in full — it is the only copy of the sub's last good data, and it is EXEMPT
        // from the stale-cache cap (maxCachedOnly=0 here, all 3 rows still kept).
        // "android" succeeded and delivered fresh posts; its stale cache row a_old
        // gets the normal stale-cache treatment and is dropped by the cap. (In
        // production a refreshed sub's stale rows are removed earlier, at the DB level
        // by the per-sub replaceSubreddit; this asserts the merge-level contract.)
        val fresh = listOf(
            listOf(), // gaming: failed -> empty
            listOf(child(post("a1", score = 50, subreddit = "android")))
        )
        val cache = listOf(
            post("g1", score = 90, subreddit = "gaming"),
            post("g2", score = 80, subreddit = "gaming"),
            post("g3", score = 70, subreddit = "gaming"),
            post("a_old", score = 999, subreddit = "android") // stale row from the confirmed sub
        )
        val out = FeedMerge.merge(
            fresh, cache, Sort.HOT, now, maxCachedOnly = 0, failedSubs = setOf("gaming")
        )
        val names = out.map { it.data.name }
        assertTrue("failed sub's whole cache survives, exempt from the cap",
            names.containsAll(listOf("g1", "g2", "g3")))
        assertTrue("fresh post from the successful sub is present", names.contains("a1"))
        assertFalse("confirmed sub's stale cache row is subject to the cap", names.contains("a_old"))
    }

    @Test
    fun failedSubMatchIsCaseInsensitive() {
        // The profile stores "DataHoarder" (user's casing) while the fan-out reports
        // the failure under a different casing. Matching must be case-insensitive, or
        // the failed sub's cache would be silently dropped.
        val fresh = listOf(listOf<PostChild>()) // sub failed
        val cache = listOf(post("dh1", subreddit = "DataHoarder"))
        val out = FeedMerge.merge(
            fresh, cache, Sort.HOT, now, failedSubs = setOf("datahoarder")
        )
        assertEquals(listOf("dh1"), out.map { it.data.name })
    }

    @Test
    fun confirmedEmptySubCacheIsDropped_notTreatedAsFailed() {
        // The sub was fetched successfully and is genuinely empty: NOT in failedSubs,
        // so its stale cache rows get the normal treatment (capped, rank-limited) —
        // here maxCachedOnly=0 drops them entirely. This is the inverse guard of the
        // test above: failing to distinguish the two would keep dead subs' cache
        // forever.
        val fresh = listOf(listOf<PostChild>()) // sub confirmed empty
        val cache = listOf(post("e1", subreddit = "quietsub"))
        val out = FeedMerge.merge(
            fresh, cache, Sort.HOT, now, maxCachedOnly = 0, failedSubs = emptySet()
        )
        assertTrue("confirmed-empty sub's cache is dropped", out.isEmpty())
    }

    @Test
    fun failedSubCacheIsExemptFromMaxCachedOnlyCap() {
        // A failed sub with a big cache (more than maxCachedOnly rows) must survive in
        // full — the cap only bounds stale rows from confirmed subs.
        val fresh = listOf(listOf(child(post("a1", score = 50, subreddit = "android"))))
        val cache = (1..30).map { i -> post("g$i", subreddit = "gaming", score = i) }
        val out = FeedMerge.merge(
            fresh, cache, Sort.HOT, now, maxCachedOnly = 10, failedSubs = setOf("gaming")
        )
        val gamingKept = out.count { it.data.subreddit == "gaming" }
        assertEquals("all 30 failed-sub cache rows survive the cap", 30, gamingKept)
    }

    @Test
    fun failedSubCachePostsAreNotDedupedAwayByFreshWins() {
        // If the same post id were somehow both in the (failed sub's) cache and in
        // fresh (a crosspost), fresh still wins — the keep rule must not resurrect a
        // stale duplicate.
        val fresh = listOf(listOf(child(post("dup", score = 50, subreddit = "gaming"))))
        val cache = listOf(post("dup", score = 999, subreddit = "gaming"), post("g_old", subreddit = "gaming"))
        val out = FeedMerge.merge(
            fresh, cache, Sort.HOT, now, failedSubs = setOf("gaming")
        )
        assertEquals("fresh copy of the duplicate wins", 50, out.first { it.data.name == "dup" }.data.score)
        assertEquals(2, out.size)
    }
}
