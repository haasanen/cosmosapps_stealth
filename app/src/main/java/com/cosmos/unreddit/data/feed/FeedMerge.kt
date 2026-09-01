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
        maxCachedRank: Int = 30
    ): List<PostChild> {
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
            val cacheOnly = cache
                .filter { p -> !seenFresh.contains(p.name) }
                .map { p -> p to p.hotRank(nowEpochMs) }
                .sortedWith(compareByDescending<Pair<PostData, Double>> { it.second }
                    .thenByDescending { it.first.created })
                .take(maxCachedOnly)

            // Build PostChildren for the cache-only rows.
            val cachedChildren = cacheOnly.map { (post, _) ->
                PostChild(post)
            }

            when (sort) {
                Sort.NEW -> result += cachedChildren
                else -> {
                    // Insert at rank min(i + 1, maxCachedRank) so stale posts stay below
                    // the fresh top block but still interleave into the tail.
                    cachedChildren.forEachIndexed { i, child ->
                        val pos = minOf(i + 1, maxCachedRank)
                        result.add(minOf(pos, result.size), child)
                    }
                }
            }
        }

        return if (result.size > maxRows) result.subList(0, maxRows) else result
    }

    /** Hot-like ranking for a single post: score^1.5 / (ageHours + 2)^1.5. */
    private fun PostData.hotRank(nowEpochMs: Long): Double {
        // `created` is epoch SECONDS (created_utc); convert before the age math.
        val ageHours = ((nowEpochMs - created * 1000L) / 3_600_000.0).coerceAtLeast(0.0)
        val score = score.coerceAtLeast(1)
        return Math.pow(score.toDouble(), 1.5) / Math.pow(ageHours + 2.0, 1.5)
    }
}
