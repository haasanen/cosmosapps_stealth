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
import com.cosmos.unreddit.ui.postlist.FeedDebug
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
        val freshIds: Set<String> = emptySet(),
        /**
         * Subreddits whose fetch FAILED this cycle (transient error, or skipped on a
         * confirmed CF block). Unlike a confirmed-empty subreddit, a failed sub's cached
         * posts are kept. When non-empty the UI shows a toast naming these subs so the
         * user knows which of their subs did not refresh (ISSUE A).
         */
        val failedSubs: List<String> = emptyList()
    ) {
        val isFinished: Boolean get() = !refreshing
    }

    private val _state = MutableStateFlow(FeedState())
    val state: StateFlow<FeedState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + io)
    private var activeCycle: Job? = null

    /**
     * Failed-sub auto-retry (2026-09-05): when a cycle ends normally but some
     * subreddits failed — the classic cause is backgrounding the app mid-refresh,
     * which suspends the device's network and kills DNS for the whole process —
     * the app must finish that refresh itself. [scheduleRetryChain] re-fetches
     * ONLY the still-failed subs (never the whole 73-sub fan-out again), with a
     * bounded attempt budget and doubling backoff, so a dead network is never
     * hammered. The chain keeps running while the app is backgrounded — the retry
     * is precisely for the window where the network comes back; a new [refresh]
     * (trigger, pull-to-refresh) supersedes the chain at any point. A CONFIRMED CF
     * block (the hard-error path of [refresh]) deliberately never lands here —
     * retrying a block is the hammering pattern the fan-out stagger exists to
     * avoid.
     */
    private var retryChainJob: Job? = null

    /**
     * Epoch of the in-flight refresh episode. Bumped each time a new fan-out
     * starts; the failed-sub retry chain checks it before writing state, so a
     * chain that outlived its episode (cancellation is cooperative and lags one
     * suspension point) can never write stale feed state.
     */
    @Volatile
    private var episodeGeneration = 0

    /** Everything needed to re-merge the feed after re-fetching failed subs. */
    private data class CycleSnapshot(
        /** Full subscription list of the cycle (order preserved). */
        val subs: List<String>,
        /**
         * Every subreddit -> its posts fetched in the base cycle. Subs that are
         * still failed map to an empty list — their cached posts are kept and
         * shown with a stale timestamp until a retry confirms them.
         */
        val results: Map<String, List<PostData>>,
        /** Subreddits still failed (the re-fetch targets of the next attempt). */
        val failed: List<String>,
        val sort: Sort,
        val profileId: Int,
        val seen: Set<String>,
        val saved: Set<String>
    )

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
    ): Job {
        this.ttlMs = ttlMs
        this.showNsfw = showNsfw
        val multiredd = subs.joinToString("+")
        if (multiredd.isBlank()) {
            com.cosmos.unreddit.ui.postlist.FeedDebug.log("refresh: SKIPPED blank multiredd")
            // No cycle starts; a completed job so callers (the background worker) can
            // still await() it without special-casing the blank case.
            return Job()
        }
        activeCycle?.cancel()
        // A new full cycle supersedes any pending failed-sub retry chain: it will
        // re-fetch everything anyway, and a stale chain's state writes must not
        // interleave with the new cycle (generation guard, below).
        retryChainJob?.cancel()
        episodeGeneration++
        val episode = episodeGeneration
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
                    freshIds = emptySet(),
                    // Clear the PREVIOUS cycle's failed-sub list: this cycle hasn't
                    // fetched anything yet. Without this, the cache-first / offline
                    // early-return paths below would keep re-surfacing last cycle's
                    // failures (and re-toast) on a cycle that never fetched.
                    failedSubs = emptyList()
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
            val cachedPosts = mapToEntities(FeedMerge.orderCache(cached, sort), seenSet, savedSet)
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
            var finalFailedSubs: Set<String> = emptySet()
            // The last snapshot's confirmed (non-failed) per-sub results, aligned to
            // [subs] — used for the per-sub atomic cache replace (ISSUE A).
            var lastConfirmed: List<List<PostData>> = emptyList()
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
                            "inFlight=${page.progress.inFlight.size} posts=${page.perSub.flatten().size} " +
                            "failed=${page.failedSubs.size}"
                    )
                    val mergedData = FeedMerge.merge(
                        page.perSub, cached, sort,
                        failedSubs = page.failedSubs
                    ).map { it.data }
                    lastMerged = mergedData
                    finalFailedSubs = page.failedSubs
                    // Only the FINAL emission is aligned 1:1 to [subs] (intermediate
                    // snapshots hold only the subs finished so far). The per-sub atomic
                    // replace needs that alignment, so capture only the final one.
                    if (page.isFinal) {
                        lastConfirmed = page.perSub.map { list -> list.map { it.data } }
                    }
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
                //
                // Message-less exceptions (NullPointerException et al.) would render
                // as the bare "refresh failed" fallback with nothing to diagnose —
                // so log the full stack AND put the exception class in the banner
                // when the message is blank (2026-09-03 device screenshot).
                //
                // CONVENTION (2026-09-03): every user-visible feed error is a
                // complete sentence — capitalised first letter, period at the end.
                // Real exceptions carry that text in their message; the fallback
                // below follows the same rule so the banner never mixes styles.
                com.cosmos.unreddit.ui.postlist.FeedDebug.logException("fan-out FAILED", e)
                // Per-sub atomic replace for whatever was CONFIRMED before the failure
                // (failed subs keep their cache untouched — ISSUE A).
                persistConfirmed(profileId, subs, lastConfirmed, finalFailedSubs)
                val msg = e.message?.takeIf { it.isNotBlank() }
                _state.update { s ->
                    s.copy(
                        refreshing = false,
                        progress = null,
                        // Surface the failed subs even on the error path: the banner
                        // shows the failure and the toast names which subs are stale.
                        failedSubs = finalFailedSubs.toList(),
                        error = msg ?: "Refresh failed (${e.javaClass.simpleName}). Please try again."
                    )
                }
                return@launch
            }

            // 3. Persist fresh results per-subreddit (atomic replace), then purge.
            com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                "fan-out COMPLETE: merged=${lastMerged.size} posts, persisting per-sub " +
                    "(confirmed=${subs.size - finalFailedSubs.size} failed=${finalFailedSubs.size})"
            )
            persistConfirmed(profileId, subs, lastConfirmed, finalFailedSubs)
            runPurge(profileId)

            _state.update { s ->
                s.copy(
                    refreshing = false,
                    progress = null,
                    offline = false,
                    fromCacheOnly = false,
                    lastRefresh = System.currentTimeMillis(),
                    failedSubs = finalFailedSubs.toList(),
                    error = if (s.posts.isEmpty()) "No posts loaded." else null
                )
            }

            // 4. Failed-sub auto-resume: if this cycle ended with subreddits that
            //    never confirmed (network died mid-cycle — classically app
            //    backgrounding), the app finishes the refresh itself instead of
            //    waiting for the user to pull again at exactly the right moment.
            if (finalFailedSubs.isNotEmpty()) {
                val resultsMap = if (lastConfirmed.size == subs.size) {
                    subs.zip(lastConfirmed).associate { (sub, list) -> sub to list }
                } else {
                    // No final emission was consumed (should not happen on this
                    // path): nothing is confirmed, everything is a retry target.
                    subs.associateWith { emptyList<PostData>() }
                }
                scheduleRetryChain(
                    CycleSnapshot(
                        subs = subs,
                        results = resultsMap,
                        failed = finalFailedSubs.toList(),
                        sort = sort,
                        profileId = profileId,
                        seen = seenSet,
                        saved = savedSet
                    ),
                    episode = episode
                )
            }
        }
        // Expose the cycle job so callers can await completion. [activeCycle]
        // was just assigned.
        return activeCycle ?: Job()
    }

    /**
     * Failed-sub auto-retry chain (2026-09-05): the follow-up to a cycle that
     * finished with failed subreddits — classically because the app was
     * backgrounded mid-refresh and Android suspended the device's network.
     *
     * Re-fetches ONLY the still-failed subs (never the whole fan-out again), at
     * most [MAX_RETRY_ATTEMPTS] rounds with doubling backoff, so a dead network
     * is never hammered and the device's wake budget stays intact. The chain
     * runs in [scope] (app lifetime, not the UI lifecycle) — that is the point:
     * it keeps ticking while the app is backgrounded, which is exactly the
     * window where the network comes back. Every round ends in the SAME
     * user-visible state as a manual pull-to-refresh: the newly confirmed subs
     * are persisted, merged into the feed, and only the still-failed ones are
     * listed. A new [refresh] (trigger, pull) supersedes the chain at any
     * point; [episodeGeneration] keeps a cancelling chain's in-flight state
     * writes from interleaving with the successor.
     *
     * A CONFIRMED Cloudflare block (FeedBlockedException from the base cycle)
     * never starts a chain: retrying a block is the hammering pattern the
     * fan-out stagger exists to avoid.
     */
    private fun scheduleRetryChain(snapshot: CycleSnapshot, episode: Int) {
        if (snapshot.failed.isEmpty()) return
        retryChainJob = scope.launch {
            var current = snapshot
            var attempt = 0
            while (current.failed.isNotEmpty() && attempt < MAX_RETRY_ATTEMPTS) {
                if (episodeGeneration != episode) return@launch
                attempt++
                val backoff = RETRY_BASE_DELAY_MS * (1L shl (attempt - 1))
                FeedDebug.log(
                    "failed-sub retry #${attempt}: ${current.failed.size} subs " +
                        "(backoff ${backoff}ms) online=${isOnline()}"
                )
                delay(backoff)
                if (episodeGeneration != episode) return@launch
                // Wait for a usable network before spending requests: the chain
                // exists for the "network comes back" moment, not to probe a
                // dead link every backoff tick. Bounded so a permanently dead
                // link falls through to the round (which then fails cleanly
                // and the outer budget bounds the total).
                var waitTicks = 0
                while (!isOnline() && waitTicks < MAX_WAIT_TICKS && episodeGeneration == episode) {
                    delay(RETRY_BASE_DELAY_MS)
                    waitTicks++
                }
                if (episodeGeneration != episode) return@launch
                val retryMultiredd = current.failed.joinToString("+")
                val roundResult = runCatching {
                    officialSource.getSubredditFanOutProgressive(
                        multiredd = retryMultiredd,
                        sort = current.sort,
                        timeSorting = null,
                        after = null,
                        stream = false
                    ).last()
                }
                if (episodeGeneration != episode) return@launch
                roundResult.fold(
                    onSuccess = { page ->
                        // Only subs the retry CONFIRMED move from failed to
                        // confirmed; the rest stay in the failed list for the
                        // next attempt (or, when the budget is spent, they keep
                        // their cached posts — the toast names them).
                        val results = LinkedHashMap(current.results)
                        val stillFailed = ArrayList(current.failed)
                        val confirmedThisRound = ArrayList<String>()
                        // perSub is aligned 1:1 to the requested multiredd order
                        // (a dead sub still gets an empty entry — the worker
                        // records emptyList() and adds it to failedSubs), so the
                        // index in current.failed IS the index into page.perSub.
                        for ((idx, sub) in current.failed.withIndex()) {
                            val confirmed = idx < page.perSub.size &&
                                page.failedSubs.none { it.equals(sub, ignoreCase = true) }
                            if (confirmed) {
                                results[sub] = page.perSub[idx].map { it.data }
                                stillFailed.remove(sub)
                                confirmedThisRound.add(sub)
                                // A confirmed empty feed (noData) is still a
                                // confirmed result: replace the stale cache.
                            }
                        }
                        if (confirmedThisRound.isNotEmpty()) {
                            // Persist exactly the subs confirmed THIS round
                            // (atomic per-sub replace, same semantics as the
                            // base cycle) so the cache matches the screen.
                            persistConfirmed(
                                profileId = current.profileId,
                                subs = confirmedThisRound,
                                confirmed = confirmedThisRound.map { results[it] ?: emptyList() },
                                failedSubs = emptySet()
                            )
                            runPurge(current.profileId)
                        }
                        val nextSnapshot = current.copy(results = results, failed = stillFailed)
                        // Re-emit the feed exactly as a pull-to-refresh would:
                        // fresh (confirmed) posts on top, failed subs' cached
                        // posts kept, only the still-failed list narrowed.
                        val merged = FeedMerge.merge(
                            freshPerSub = nextSnapshot.results.values.map { list ->
                                list.map { PostChild(it) }
                            },
                            cache = loadCache(current.profileId),
                            sort = current.sort,
                            failedSubs = stillFailed.toSet()
                        ).map { it.data }
                        val freshIds = nextSnapshot.results.values.flatten()
                            .map { it.name }
                            .toHashSet()
                        val posts = mapToEntities(merged, current.seen, current.saved)
                        _state.update { s ->
                            if (s.profileId != current.profileId) {
                                s // A different profile took over; don't stomp it.
                            } else {
                                s.copy(
                                    posts = posts,
                                    refreshing = false,
                                    progress = null,
                                    offline = false,
                                    fromCacheOnly = false,
                                    lastRefresh = System.currentTimeMillis(),
                                    freshIds = freshIds,
                                    failedSubs = stillFailed,
                                    error = if (s.posts.isEmpty()) "No posts loaded." else null
                                )
                            }
                        }
                        FeedDebug.log(
                            "failed-sub retry #$attempt done: " +
                                "confirmed=${confirmedThisRound.size} " +
                                "stillFailed=${stillFailed.size}"
                        )
                        current = nextSnapshot
                    },
                    onFailure = { e ->
                        // CancellationException: the episode moved on (superseded
                        // by a new refresh or shutdown) — stop silently.
                        if (e is kotlinx.coroutines.CancellationException ||
                            e is RedditOfficialSource.FeedBlockedException
                        ) {
                            return@launch
                        }
                        FeedDebug.log(
                            "failed-sub retry #$attempt failed: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                )
            }
            if (episodeGeneration == episode && current.failed.isNotEmpty()) {
                // Budget exhausted: the chain stops; the still-failed subs keep
                // their cached posts and the state's failedSubs list already
                // names them (the UI toast did its job at the base cycle).
                FeedDebug.log(
                    "failed-sub retry: budget exhausted, " +
                        "${current.failed.size} subs left on cache"
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
        // TTL enforced at READ time (2026-09-05): a row older than the configured
        // cache duration is expired — it is deleted now and never shown. The
        // end-of-cycle purge is the leak guard; this is the actual enforcement
        // point. Without it, rows from a dead source (e.g. the legacy Atom
        // fallback) stayed visible for months: the cache-first path served them
        // on every launch and only a completed fan-out would eventually purge.
        val cutoff = System.currentTimeMillis() - ttlMs
        val fresh = rows.filter { it.fetchedAt >= cutoff }
        if (fresh.size != rows.size) {
            val expired = rows.filter { it.fetchedAt < cutoff }.map { it.postId }
            runCatching { db.feedCacheDao().deleteByIds(profileId, expired) }
            com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                "cache load: purged ${expired.size} rows older than TTL " +
                    "(${ttlMs / 3_600_000}h)"
            )
        }
        val parsed = fresh.mapNotNull { toPostData(it.postJson) }
        // TEMP cache diagnostics: "raw" = rows in the table, "parsed" = rows whose
        // postJson deserialized back into a PostData. raw > parsed means stored JSON
        // is corrupt or blank (a persist-time toJson failure writes postJson = "").
        val empty = fresh.count { it.postJson.isBlank() }
        com.cosmos.unreddit.ui.postlist.FeedDebug.log(
            "cache load: raw=${rows.size} fresh=${fresh.size} parsed=${parsed.size} emptyJson=$empty"
        )
        // Self-heal: blank postJson rows can never deserialize back (they are the
        // legacy corruption from the empty MediaMetadataAdapter.toJson). Delete them
        // instead of keeping them around to be re-counted on every launch.
        if (empty > 0) {
            val blankIds = fresh.filter { it.postJson.isBlank() }.map { it.postId }
            runCatching { db.feedCacheDao().deleteByIds(profileId, blankIds) }
            com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                "cache load: purged $empty corrupt (blank JSON) rows"
            )
        }
        return parsed.take(FeedPurge.DEFAULT_ROW_CAP)
    }

    /**
     * Per-subreddit atomic cache replace (ISSUE A).
     *
     * For every subreddit in [subs] that was CONFIRMED this cycle (i.e. not in
     * [failedSubs]) its entire cached set is atomically replaced by [confirmed]
     * [i] — even if that new set is empty (a genuinely empty sub drops its stale
     * posts). Failed subs are skipped entirely: their cache rows stay untouched, so
     * the user keeps their last good data for those subs until the next successful
     * fetch. [confirmed] is aligned 1:1 to [subs] (the source's snapshot builds it
     * with `subs.mapNotNull { results[it] }`, which keeps the request order).
     */
    private suspend fun persistConfirmed(
        profileId: Int,
        subs: List<String>,
        confirmed: List<List<PostData>>,
        failedSubs: Set<String>
    ) {
        if (subs.size != confirmed.size) {
            // Defensive: never index into a misaligned list.
            com.cosmos.unreddit.ui.postlist.FeedDebug.log(
                "persistConfirmed: subs=${subs.size} != confirmed=${confirmed.size}, skipping"
            )
            return
        }
        val now = System.currentTimeMillis()
        var replaced = 0
        var kept = 0
        for ((i, sub) in subs.withIndex()) {
            if (failedSubs.any { it.equals(sub, ignoreCase = true) }) {
                kept++
                continue
            }
            val rows = confirmed[i].map {
                FeedCache(
                    postId = it.name,
                    subreddit = it.subreddit.ifBlank { sub },
                    permalink = it.permalink,
                    postJson = toJson(it),
                    fetchedAt = now,
                    profileId = profileId
                )
            }
            if (runCatching { db.feedCacheDao().replaceSubreddit(profileId, sub, rows) }.isFailure) {
                com.cosmos.unreddit.ui.postlist.FeedDebug.log("persistConfirmed: FAILED sub=$sub")
            } else {
                replaced++
            }
        }
        com.cosmos.unreddit.ui.postlist.FeedDebug.log(
            "persistConfirmed: replaced=$replaced keptFailed=$kept (total subs=${subs.size})"
        )
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

        /**
         * Failed-sub auto-retry budget: max rounds per interrupted cycle.
         * 3 x (15s, 30s, 60s) backoff ≈ 105s of coverage — long enough for the
         * network to recover after a backgrounding suspension, short enough to
         * stop probing a network that is genuinely gone.
         */
        private const val MAX_RETRY_ATTEMPTS = 3
        /** Base delay of the retry backoff (doubles per attempt). */
        private const val RETRY_BASE_DELAY_MS = 15_000L
        /**
         * Max ticks the chain waits for [isOnline] to come back before spending
         * a round anyway (bounded probe). 10 x 15s = 150s per attempt.
         */
        private const val MAX_WAIT_TICKS = 10
    }
}
