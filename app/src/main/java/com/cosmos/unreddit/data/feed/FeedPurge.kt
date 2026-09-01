package com.cosmos.unreddit.data.feed

/**
 * Pure purge policy for the feed cache — the leak guard.
 *
 * The cache is disposable by design: nothing else in the app references `feed_cache`
 * rows, so deleting a row can never strand a reference. These rules bound the table so
 * it can never grow without limit:
 *
 * 1. TTL — rows older than [FeedPurgePlan] ttl are expired first.
 * 2. Row cap — if survivors exceed the cap, the OLDEST survivors are cut (a hot feed
 *    only needs recent posts).
 * 3. Size tripwire — if the caller passes [forcedCap] (set by the 25 MB database size
 *    check in the coordinator), the table is forced down to that many rows regardless
 *    of the normal cap.
 *
 * No I/O here: the coordinator turns a [FeedPurgePlan] into DAO calls.
 */
object FeedPurge {

    /** The default TTL for home-feed rows: 12 h. */
    const val DEFAULT_TTL_MS = 12L * 3_600_000L

    /** Normal row cap per profile: 2,500 posts (~10 MB). */
    const val DEFAULT_ROW_CAP = 2500

    /** Aggressive cap used by the size tripwire. */
    const val TRIPWIRE_CAP = 1000

    /**
     * @param rows (postId, fetchedAtMs) pairs currently in the cache for the profile.
     * @param now current epoch millis.
     * @param ttlMs rows older than this are expired.
     * @param rowCap maximum rows to keep after TTL.
     * @param forcedCap when non-null, overrides [rowCap] (size tripwire).
     */
    fun plan(
        rows: List<Pair<String, Long>>,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        rowCap: Int = DEFAULT_ROW_CAP,
        forcedCap: Int? = null
    ): FeedPurgePlan {
        val cutoff = now - ttlMs
        val expired = rows.filter { it.second < cutoff }.map { it.first }
        val survivors = rows.filter { it.second >= cutoff }
        val cap = forcedCap ?: rowCap
        val overflow = survivors.size - cap
        val oldestToCut = if (overflow > 0) {
            survivors.sortedBy { it.second }
                .take(overflow)
                .map { it.first }
        } else {
            emptyList()
        }
        return FeedPurgePlan(expired, oldestToCut)
    }
}

/** The outcome of a purge pass: two disjoint id sets to delete. */
data class FeedPurgePlan(
    val expiredIds: List<String>,
    val oldestIdsToCut: List<String>
) {
    val isEmpty: Boolean get() = expiredIds.isEmpty() && oldestIdsToCut.isEmpty()
}
