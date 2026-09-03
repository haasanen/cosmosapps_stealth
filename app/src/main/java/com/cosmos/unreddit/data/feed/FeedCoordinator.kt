package com.cosmos.unreddit.data.feed

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.local.dao.FeedCacheDao
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.db.FeedCache
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.data.remote.api.reddit.source.RedditOfficialSource
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.di.NetworkModule.RedditMoshi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Drives the official home feed: cache-first rendering, progressive fan-out refresh,
 * persistence and purge of the local cache, offline support and post-detail snapshots.
 *
 * State machine (see the progressive-feed plan):
 *
 * 1. [refresh] loads the cache for the profile and emits it IMMEDIATELY (instant
 *    first paint; on an offline device that is the whole content).
 * 2. While online it collects the source's progressive fan-out. Every emission is a
 *    CUMULATIVE snapshot of all subreddits finished so far; it is merged with the
 *    cache via [FeedMerge] (fresh wins, hot-like interleave, cache-only capped) and
 *    re-emitted, so the user watches the feed fill in subreddit by subreddit behind
 *    a live progress header.
 * 3. On completion the fresh posts are persisted (JSON snapshots) and the cache is
 *    purged per [FeedPurge] (TTL -> row cap -> DB-size tripwire).
 *
 * CF model: reddit.com routes everything through Cloudflare, so a challenged or
 * throttled request is a NORMAL state, not an error: the source retries each
 * subreddit leniently, failed subs are skipped, and if a whole cycle yields nothing
 * the cache is shown as-is (never blank, never a crash).
 */
@Singleton
class FeedCoordinator @Inject constructor(
    private val officialSource: RedditOfficialSource,
    private val db: RedditDatabase,
    @ApplicationContext private val context: Context,
    @RedditMoshi private val moshi: Moshi,
    private val postMapper: PostMapper2,
    @DefaultDispatcher private val io: CoroutineDispatcher
) {

    data class FeedState(
        val posts: List<PostEntity> = emptyList(),
        val progress: RedditOfficialSource.FanOutProgress? = null,
        val refreshing: Boolean = false,
        val offline: Boolean = false,
        /** True when the feed is being served from cache with no live network (offline). */
        val fromCacheOnly: Boolean = false,
        val error: String? = null,
        val lastRefresh: Long = 0L,
        val profileId: Int = 0,
        /**
         * Ids of the posts actually fetched from the network during the current cycle.
         * Posts present in [posts] but NOT in this set were pulled from the local cache
         * (not re-fetched this cycle) and are shown with a "(cached)" timestamp badge.
         */
        val freshIds: Set<String> = emptySet()
    ) {
        val isFinished: Boolean get() = !refreshing
    }

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + io)
    private var activeCycle: Job? = null

    /** TTL for cache rows, set per [refresh] call from the user's preference. */
    @Volatile
    private var ttlMs: Long = FeedPurge.DEFAULT_TTL_MS

    /** Whether NSFW posts are shown, set per [refresh] call from the user's preference. */
    @Volatile
    private var showNsfw: Boolean = false

    /** Per-subreddit `after` cursors from the last page-1 refresh, used by [loadMore]. */
    private val cursors = LinkedHashMap<String, String?>()
    /** Subreddit order of the last refresh (cursor alignment for [loadMore]). */
    private var subredditOrder = listOf<String>()

    /** Fresh rows captured during the fan-out collect, persisted at the end. */
    private val pendingRows = LinkedHashMap<String, FeedCache>()

    private val postAdapter: JsonAdapter<PostData> = moshi.adapter(PostData::class.java)

    //region Home feed

    /**
     * One full refresh cycle: emit cache, fan out progressively, persist, purge.
     * Calling it again (sort change, pull-to-refresh) cancels the running cycle.
     */
    fun refresh(
        profileId: Int,
        subs: List<String>,
        sort: Sort,
        historyIds: List<String> = emptyList(),
        savedIds: List<String> = emptyList(),
        showNsfw: Boolean = false,
        ttlMs: Long = FeedPurge.DEFAULT_TTL_MS,
        manual: Boolean = false
    ) {
        this.ttlMs = ttlMs
        this.showNsfw = showNsfw
        val multiredd = subs.joinToString("+")
        if (multiredd.isBlank()) {
            com.cosmos.unreddit.ui.postlist.FeedDebug.log("refresh: SKIPPED blank multiredd")
            return
        }
        activeCycle?.cancel()

        com.cosmos.unreddit.ui.postlist.FeedDebug.lastRefreshArgs.set(
            "profile=$profileId subs=${subs.size}"
        )
        com.cosmos.unreddit.ui.postlist.FeedDebug.log(
            "refresh START profile=$profileId subs=${subs.size} sort=$sort online=${isOnline()}"
        )

        activeCycle = scope.launch {
            pendingRows.clear()
            _state.update {
                it.copy(
                    profileId = profileId,
                    posts = emptyList(),
                    progress = null,
                    refreshing = true,
                    offline = false,
                    fromCacheOnly = false,
                    error = null,
                    lastRefresh = 0L,
                    freshIds = emptySet()
                )
            }

            // 1. Instant first paint from the cache.
            val cached = runCatching { loadCache(profileId) }.getOrElse { e ->
                com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                    "cache load FAILED: ${e.javaClass.simpleName}: ${e.message}"
                )
                emptyList()
            }
            val seenSet = historyIds.toHashSet()
            val savedSet = savedIds.toHashSet()
            val cachedPosts = mapToEntities(cached, seenSet, savedSet)
            if (cachedPosts.isNotEmpty()) {
                _state.update { s -> s.copy(posts = cachedPosts) }
            }

            // 1b. Show the progress header from the very first frame: "0 / N" while the
            //     fan-out is still warming up. Without this, a cold launch (empty cache)
            //     sits on a blank list until the first subreddit survives CF's challenge
            //     dance — minutes in the worst case — with nothing on screen but the logo.
            //     The first few subs are named so a stuck load is screenshot-able.
            _state.update { s ->
                s.copy(progress = RedditOfficialSource.FanOutProgress(subs.size, 0, 0, subs.take(4), null))
            }

            if (!isOnline()) {
                _state.update { s ->
                    s.copy(
                        refreshing = false,
                        offline = true,
                        fromCacheOnly = true,
                        progress = null
                    )
                }
                return@launch
            }

            // 2. Progressive fan-out. Each emission is cumulative; merge with cache.
            //
            // Cache-first policy: a NON-manual refresh (returning to the tab, reopening
            // the app, or the trigger re-firing after the datastore settles) never hits
            // the network while the cache already has posts — the user must land on the
            // same list they last saw. Only a manual pull-to-refresh (manual = true) or
            // a genuinely empty cache triggers a fresh fan-out.
            if (!manual && cachedPosts.isNotEmpty()) {
                com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                    "cache-first: serving ${cachedPosts.size} cached posts, skipping fan-out"
                )
                _state.update { s ->
                    s.copy(
                        posts = cachedPosts,
                        refreshing = false,
                        progress = null,
                        offline = false,
                        fromCacheOnly = true,
                        lastRefresh = System.currentTimeMillis(),
                        freshIds = emptySet()
                    )
                }
                return@launch
            }
            var lastMerged: List<PostData> = emptyList()
            try {
                com.cosmos.unreddit.ui.postlist.FeedDebug.log("fan-out: collecting (stream=true)")
                officialSource.getSubredditFanOutProgressive(
                    multiredd = multiredd,
                    sort = sort,
                    timeSorting = null,
                    after = null,
                    stream = true
                ).collect { page ->
                    val n = com.cosmos.unreddit.ui.postlist.FeedDebug.fanOutEmissions.incrementAndGet()
                    com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                        "fan-out page #$n: done=${page.progress.done}/${page.progress.total} " +
                            "inFlight=${page.progress.inFlight.size} posts=${page.perSub.flatten().size}"
                    )
                    val mergedData = FeedMerge.merge(page.perSub, cached, sort).map { it.data }
                    lastMerged = mergedData
                    val posts = mapToEntities(mergedData, seenSet, savedSet)
                    // Everything the network actually returned this cycle (deduped) is
                    // "fresh"; anything in the merged list that isn't here came from cache.
                    val freshIds = page.perSub.flatten().map { it.data.name }.toHashSet()
                    cursors.clear()
                    cursors.putAll(page.cursors)
                    // Any stable order works: loadMore re-joins the multiredd and the
                    // cursor string in the SAME order, and the source zips them 1:1.
                    subredditOrder = page.cursors.keys.toList()
                    _state.update { s ->
                        s.copy(posts = posts, progress = page.progress, refreshing = true, freshIds = freshIds)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // refresh() cancels the previous in-flight cycle (new trigger, pull-to-
                // refresh, datastore re-emission); the old cycle's collect throws this.
                // A cancellation is NOT a refresh error — must propagate, otherwise it
                // becomes a user-visible "…was cancelled" banner (2026-09-02 screenshot).
                throw e
            } catch (e: Exception) {
                // CF hard-block (RedditOfficialSource.FeedBlockedException) or network
                // death mid-cycle: persist whatever the fan-out streamed, keep the
                // cache-rendered posts, and surface the (actionable) error. There is
                // deliberately no silent switch to another endpoint or source — Atom
                // and Arctic Shift are independent, selectable sources in Settings.
                com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                    "fan-out FAILED: ${e.javaClass.simpleName}: ${e.message}"
                )
                if (lastMerged.isNotEmpty()) persistFresh(profileId, lastMerged)
                _state.update { s ->
                    s.copy(
                        refreshing = false,
                        progress = null,
                        error = e.message ?: "refresh failed"
                    )
                }
                return@launch
            }

            // 3. Persist fresh results, then purge.
            com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                "fan-out COMPLETE: merged=${lastMerged.size} posts, persisting"
            )
            persistFresh(profileId, lastMerged)
            runPurge(profileId)

            _state.update { s ->
                s.copy(
                    refreshing = false,
                    progress = null,
                    offline = false,
                    fromCacheOnly = false,
                    lastRefresh = System.currentTimeMillis(),
                    error = if (s.posts.isEmpty()) "no posts loaded" else null
                )
            }
        }
    }

    /**
     * Load the next page of the home feed using the per-subreddit cursors captured
     * by the last page-1 refresh. Appends new posts (deduped by id) to the current
     * list and stores them in the cache.
     */
    fun loadMore(
        profileId: Int,
        sort: Sort,
        historyIds: List<String>,
        savedIds: List<String>
    ) {
        if (cursors.isEmpty() || subredditOrder.isEmpty()) return
        val subList = subredditOrder.filter { cursors.containsKey(it) }
        if (subList.isEmpty()) return
        val after = subList.joinToString(RedditOfficialSource.FANOUT_CURSOR_SEPARATOR) {
            cursors[it].orEmpty()
        }
        scope.launch {
            _state.update { s -> s.copy(refreshing = true) }
            try {
                val page = officialSource.getSubredditFanOutProgressive(
                    multiredd = subList.joinToString("+"),
                    sort = sort,
                    timeSorting = null,
                    after = after,
                    stream = false
                ).last()
                val current = _state.value.posts
                val seenIds = current.map { it.id }.toHashSet()
                val fresh = page.perSub
                    .flatten()
                    .map { it.data }
                    .filter { it.name !in seenIds }
                if (fresh.isEmpty()) {
                    _state.update { s -> s.copy(refreshing = false) }
                    return@launch
                }
                val entities = mapToEntities(
                    fresh,
                    historyIds.toHashSet(),
                    savedIds.toHashSet()
                )
                persistFresh(profileId, fresh)
                cursors.clear()
                cursors.putAll(page.cursors)
                _state.update { s ->
                    s.copy(
                        posts = (current + entities).take(MAX_FEED_ROWS),
                        refreshing = false
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Propagate: scope cancellation is not a loadMore failure.
                throw e
            } catch (e: Exception) {
                _state.update { s -> s.copy(refreshing = false) }
            }
        }
    }

    //endregion

    //region Post detail snapshots

    /**
     * A cached snapshot of a post (instant offline detail rendering), or null.
     * The detail screen renders this immediately and refreshes from the network in
     * the background; the network result wins.
     */
    suspend fun getPostFromCache(profileId: Int, postId: String): PostEntity? =
        runCatching {
            val row = db.feedCacheDao().byPostId(profileId, postId) ?: return@runCatching null
            toPostData(row.postJson)?.let { postMapper.dataToEntity(it) }
        }.getOrNull()

    /** True when a cache row for the post is newer than [maxAgeMs] (skip re-fetch). */
    suspend fun isPostFreshInCache(
        profileId: Int,
        postId: String,
        maxAgeMs: Long = POST_TTL_MS
    ): Boolean =
        (db.feedCacheDao().byPostId(profileId, postId)?.fetchedAt ?: 0L) >
            System.currentTimeMillis() - maxAgeMs

    /**
     * Refresh one post from the network (with the slugless-permalink redirect fix)
     * and store the snapshot in the cache. Silently no-ops on failure — the detail
     * screen already shows the cached version.
     */
    fun refreshPost(profileId: Int, permalink: String, name: String) {
        scope.launch {
            val child = runCatching {
                officialSource.getPost(permalink, sort = Sort.BEST)
            }.getOrNull() ?: return@launch
            val data = (child.firstOrNull()?.data?.children?.firstOrNull() as? PostChild)?.data
                ?: return@launch
            runCatching {
                db.feedCacheDao().upsertAll(
                    listOf(
                        FeedCache(
                            postId = data.name,
                            subreddit = data.subreddit,
                            permalink = data.permalink,
                            postJson = toJson(data),
                            fetchedAt = System.currentTimeMillis(),
                            profileId = profileId
                        )
                    )
                )
            }
        }
    }

    //endregion

    /**
     * Post-detail network reload succeeded: update ONLY this post.
     *
     * 1. The feed_cache row (postJson + fetchedAt) is re-written from the fresh
     *    [PostData] — the same row the feed reads back on the next cache load, so
     *    score/comment count stay fresh there.
     * 2. The in-memory feed state list has the one matching entity replaced (score,
     *    comment count, upvote ratio, flair) — the whole feed is NOT re-fetched and
     *    posts that aren't in the current list are untouched.
     *
     * Called from the post-details screen after a pull-down reload.
     */
    fun applyPostUpdate(profileId: Int, data: PostData) {
        scope.launch {
            val entity = runCatching { postMapper.dataToEntity(data) }.getOrNull()
            // 1. Cache row (only if this post was already cached for the profile —
            //    we must not create cache rows for posts outside the home feed).
            if (db.feedCacheDao().byPostId(profileId, data.name) != null) {
                runCatching {
                    db.feedCacheDao().upsertAll(
                        listOf(
                            FeedCache(
                                postId = data.name,
                                subreddit = data.subreddit,
                                permalink = data.permalink,
                                postJson = toJson(data),
                                fetchedAt = System.currentTimeMillis(),
                                profileId = profileId
                            )
                        )
                    )
                }
            }
            // 2. In-memory feed list: replace only the one matching entity.
            val current = _state.value
            if (current.profileId == profileId && entity != null) {
                val idx = current.posts.indexOfFirst { it.id == entity.id }
                if (idx >= 0) {
                    val kept = current.posts[idx]
                    // Keep list-owned UI state (seen/saved); take the fresh metrics.
                    val updated = entity.copy(seen = kept.seen, saved = kept.saved)
                    _state.update { s ->
                        s.copy(posts = s.posts.toMutableList().also { it[idx] = updated })
                    }
                }
            }
        }
    }

    //region Cache internals

    private suspend fun loadCache(profileId: Int): List<PostData> {
        val rows = db.feedCacheDao().allFromProfile(profileId)
        val parsed = rows.mapNotNull { toPostData(it.postJson) }
        // TEMP cache diagnostics: "raw" = rows in the table, "parsed" = rows whose
        // postJson deserialized back into a PostData. raw > parsed means stored JSON
        // is corrupt or blank (a persist-time toJson failure writes postJson = "").
        val empty = rows.count { it.postJson.isBlank() }
        com.cosmos.unreddit.ui.postlist.FeedDebug.log(
            "cache load: raw=${rows.size} parsed=${parsed.size} emptyJson=$empty"
        )
        // Self-heal: blank postJson rows can never deserialize back (they are the
        // legacy corruption from the empty MediaMetadataAdapter.toJson). Delete them
        // instead of keeping them around to be re-counted on every launch.
        if (empty > 0) {
            val blankIds = rows.filter { it.postJson.isBlank() }.map { it.postId }
            runCatching { db.feedCacheDao().deleteByIds(profileId, blankIds) }
            com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                "cache load: purged $empty corrupt (blank JSON) rows"
            )
        }
        return parsed.take(FeedPurge.DEFAULT_ROW_CAP)
    }

    private suspend fun persistFresh(profileId: Int, fresh: List<PostData>) {
        if (fresh.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = fresh.map {
            FeedCache(
                postId = it.name,
                subreddit = it.subreddit,
                permalink = it.permalink,
                postJson = toJson(it),
                fetchedAt = now,
                profileId = profileId
            )
        }
        // TEMP cache diagnostics: a blank postJson means toJson threw for that post
        // (runCatching -> ""). Such rows are written but deserialized back as null,
        // so they are invisible to the cache — exactly the "persisted but not loaded"
        // symptom. If emptyJson > 0 here, the next "cache load" line will show the drop.
        val emptyJson = rows.count { it.postJson.isBlank() }
        var failedBatches = 0
        for (i in rows.indices step UPSERT_BATCH) {
            val batch = rows.subList(i, minOf(i + UPSERT_BATCH, rows.size))
            if (!runCatching { db.feedCacheDao().upsertAll(batch) }.isSuccess) {
                failedBatches++
                com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                    "persist FAILED batch=${i / UPSERT_BATCH + 1} size=${batch.size}"
                )
            }
        }
        com.cosmos.unreddit.ui.postlist.FeedDebug.log(
            "persist: rows=${rows.size} emptyJson=$emptyJson failedBatches=$failedBatches"
        )
    }

    private suspend fun runPurge(profileId: Int) {
        try {
            val all = db.feedCacheDao().allFromProfile(profileId)
            var forcedCap: Int? = null
            if (db.feedCacheDao().databaseSizeBytes() > DB_TRIPWIRE_BYTES) {
                forcedCap = FeedPurge.TRIPWIRE_CAP
            }
            val plan = FeedPurge.plan(
                rows = all.map { it.postId to it.fetchedAt },
                now = System.currentTimeMillis(),
                ttlMs = ttlMs,
                forcedCap = forcedCap
            )
            if (!plan.isEmpty) {
                db.feedCacheDao().deleteByIds(
                    profileId,
                    plan.expiredIds + plan.oldestIdsToCut
                )
            }
        } catch (e: Exception) {
            // Purge failure is non-fatal; the next cycle retries.
        }
    }

    private suspend fun mapToEntities(
        data: List<PostData>,
        seen: Set<String>,
        saved: Set<String>
    ): List<PostEntity> =
        data.mapNotNull { d ->
            // Respect the NSFW content preference (mirrors PostUtil.filterPosts).
            if (d.isOver18 && !showNsfw) return@mapNotNull null
            runCatching { postMapper.dataToEntity(d) }.getOrNull()
        }.map { p ->
            p.apply {
                this.seen = seen.contains(p.id)
                this.saved = saved.contains(p.id)
            }
        }

    private fun toPostData(json: String): PostData? =
        runCatching { postAdapter.fromJson(json) }.getOrNull()

    private fun toJson(data: PostData): String =
        runCatching { postAdapter.toJson(data) }.getOrDefault("")

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    //endregion

    fun shutdown() {
        activeCycle?.cancel()
        scope.cancel()
    }

    companion object {
        /** Post-detail snapshot TTL: 1 h. */
        const val POST_TTL_MS = 3_600_000L

        /** Whole-DB size tripwire: 25 MB. */
        const val DB_TRIPWIRE_BYTES = 25L * 1024 * 1024

        /** Maximum rows the in-memory feed list grows to (memory guard). */
        const val MAX_FEED_ROWS = 1000

        /** Feed-cache upsert batch size. */
        private const val UPSERT_BATCH = 200
    }
}
