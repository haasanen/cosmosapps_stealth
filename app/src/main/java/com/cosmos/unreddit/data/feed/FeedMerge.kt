package com.cosmos.unreddit.data.feed

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.util.extension.interlace

/**
 * Pure merge logic for the home feed: combines fresh per-subreddit fetches with cached
 * posts into one balanced list.
 *
 * No I/O, no Android dependencies: everything here is unit-testable on the plain JVM.
 */
object FeedMerge {

    /**
     * Merge freshly fetched posts with cached ones.
     *
     * Rules:
     * - Dedupe by post fullname; a FRESH post always wins (it carries the current
     *   score / comment count).
     * - Fresh posts are ordered like reddit's hot feed: balanced interleave of the
     *   per-subreddit lists (so big subs cannot bury small ones), date for NEW,
     *   score for TOP.
     * - Cache-only posts are appended, ranked by score/age (hot-like), but at most
     *   [maxCachedOnly] of them and never above position [maxCachedRank] from the top,
     *   so stale content cannot float above fresh results. For NEW sorting cached posts
     *   are always after fresh (they are by definition older).
     * - Output capped at [maxRows] to bound RAM.
     */
    fun merge(
        freshPerSub: List<List<PostChild>>,
        cache: List<PostData>,
        sort: Sort,
        nowEpochMs: Long = System.currentTimeMillis(),
        maxRows: Int = 500,
        maxCachedOnly: Int = 100,
        maxCachedRank: Int = 30,
        failedSubs: Set<String> = emptySet()
    ): List<PostChild> {
        // Case-insensitive: a profile stores subreddit names in the user's casing while
        // the cache/SSR store the display name ("DataHoarder"), and reddit matches subs
        // case-insensitively. A failed sub is one whose fetch THREW (transient error);
        // a confirmed-empty sub is NOT here and its cache is dropped as intended.
        val failedSubsLower = failedSubs.mapTo(HashSet<String>()) { it.lowercase() }

        // Dedupe fresh first (cross-sub duplicates are rare but possible via crossposts).
        val seenFresh = HashSet<String>()
        val freshPerSubDeduped = freshPerSub
            .map { list -> list.filter { seenFresh.add(it.data.name) } }
            .filter { it.isNotEmpty() }

        val freshOrdered: List<PostChild> = when (sort) {
            Sort.NEW -> freshPerSubDeduped.flatten().sortedByDescending { it.data.created }
            Sort.TOP -> freshPerSubDeduped.flatten().sortedByDescending { it.data.score }
            else -> freshPerSubDeduped.interlace()
        }

        val result = ArrayList<PostChild>(freshOrdered.size + maxCachedOnly)
        result += freshOrdered

        if (cache.isNotEmpty()) {
            // Cache posts from FAILED subs are always kept (they are the only copy; the
            // sub's new posts were never confirmed, so dropping the old ones would lose
            // the user's last good data for that sub). They are exempt from the
            // maxCachedOnly cap — a failed sub's whole cache survives.
            val cacheFresh = cache.filter { !seenFresh.contains(it.name) }
            val keptFailedCache = if (failedSubsLower.isNotEmpty()) {
                cacheFresh.filter { failedSubsLower.contains(it.subreddit.lowercase()) }
            } else emptyList()
            // Only cache posts from confirmed (non-failed) subs are subject to the cap;
            // the failed subs' posts above are already unconditionally kept.
            val keptFailedNames = keptFailedCache.mapTo(HashSet<String>()) { it.name }
            val cappedCache = cacheFresh
                .filter { it.name !in keptFailedNames }
                .map { p -> p to p.hotRank(nowEpochMs) }
                .sortedWith(compareByDescending<Pair<PostData, Double>> { it.second }
                    .thenByDescending { it.first.created })
                .take(maxCachedOnly)

            // Build PostChildren for the cache-only rows.
            val failedChildren = keptFailedCache.map { PostChild(it) }
            val cachedChildren = cappedCache.map { (post, _) -> PostChild(post) }

            when (sort) {
                Sort.NEW -> {
                    result += failedChildren
                    result += cachedChildren
                }
                else -> {
                    // Insert at rank min(i + 1, maxCachedRank) so stale posts stay below
                    // the fresh top block but still interleave into the tail.
                    (failedChildren + cachedChildren).forEachIndexed { i, child ->
                        val pos = minOf(i + 1, maxCachedRank)
                        result.add(minOf(pos, result.size), child)
                    }
                }
            }
        }

        return if (result.size > maxRows) result.subList(0, maxRows) else result
    }

    /**
     * Order a pure-cache list the same way the fresh feed is ordered, so a
     * cache-only screen (first paint, cache-first serve, offline) does not show
     * insertion-ordered blocks of one subreddit after another.
     *
     * The raw DAO query has no ORDER BY: SQLite returns rows in the order
     * replaceSubreddit wrote them, i.e. whole-subreddit blocks. Applying the
     * same ordering here as merge() (balanced interleave for HOT/BEST, newest
     * for NEW, top score for TOP) keeps cached and fresh views consistent.
     */
    fun orderCache(cache: List<PostData>, sort: Sort): List<PostData> = when (sort) {
        Sort.NEW -> cache.sortedByDescending { it.created }
        Sort.TOP -> cache.sortedByDescending { it.score }
        else -> cache.groupBy { it.subreddit.lowercase() }.values.interlace()
    }

    /** Hot-like ranking for a single post: score^1.5 / (ageHours + 2)^1.5. */
    private fun PostData.hotRank(nowEpochMs: Long): Double {
        // `created` is epoch SECONDS (created_utc); convert before the age math.
        val ageHours = ((nowEpochMs - created * 1000L) / 3_600_000.0).coerceAtLeast(0.0)
        val score = score.coerceAtLeast(1)
        return Math.pow(score.toDouble(), 1.5) / Math.pow(ageHours + 2.0, 1.5)
    }
}
