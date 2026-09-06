package com.cosmos.unreddit.data.feed

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.flow.first
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
    private val preferences: DataStore<Preferences>,
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
     * Failed-sub auto-retry (2026-09-06, v2.5.53): when a cycle ends normally but some
     * subreddits failed — classically because the app was backgrounded mid-refresh,
     * which suspends the device's network and kills DNS for the whole process — the
     * app must finish that refresh itself.
     *
     * Design correction (v2.5.52 proved the inverse fails): the v2.5.52 chain kept
     * ticking while backgrounded — the log line `failed-sub retry #1: 53 subs
     * (backoff 15000ms) online=true` shows [isOnline] reporting "online" while all
     * 153 requests were dying with UnknownHostException: the connectivity FLAG stays
     * set for a backgrounded process whose sockets are dead. All 3 rounds therefore
     * ran against the dead link while the user was in the other app, the budget was
     * spent, and the reopen still showed 53 stale subs. v2.5.53 inverts the premise:
     *
     *  1. Rounds run ONLY while the app is in the FOREGROUND (its network actually
     *     works). Backgrounding waits at zero cost — the budget is ROUNDS, not time,
     *     so a round may wait 30 s or 30 min for the foreground; it is spent only
     *     on a real attempt.
     *  2. The pending retry is PERSISTED (DataStore) the moment it is created, so a
     *     process death while backgrounded cannot lose it: [init] restores it from
     *     disk on every app launch. A 24 h TTL drops it only when it is long past.
     *  3. A round that CONFIRMS nothing is still an attempt (the budget bounds
     *     hammering a genuinely dead network); a round that confirms >=1 sub is
     *     free — it proves the network works and the rest of the subs stay queued.
     *
     * A new [refresh] that reaches the network (a REAL fan-out) supersedes the
     * pending retry: it re-fetches everything anyway. The cache-first and offline
     * early-return paths do NOT — those never re-fetch, so the pending subs would
     * be stranded. A CONFIRMED CF block never starts a retry: retrying a block is
     * the hammering pattern the fan-out stagger exists to avoid.
     */
    private var retryChainJob: Job? = null

    /**
     * A failed-sub retry persisted across process death. Deliberately TINY: it stores
     * only which subs still need fetching — never post payloads. The confirmed subs'
     * posts are already in the feed_cache table (persistConfirmed ran before the retry
     * started), so [restorePendingRetry] rebuilds the confirmed results from the DB.
     *
     * [allSubs] is the full subscription list in its ORIGINAL order (defines the feed
     * interleave order); [failed] is the still-failed list (shrinks as rounds confirm
     * subs); [streak] is the consecutive no-confirmation round count (the budget —
     * a confirming round resets it, so "the network is back, finish the job" is not
     * artificially capped, while a genuinely dead network stops after
     * [MAX_RETRY_ATTEMPTS] consecutive empty rounds).
     */
    private data class PendingRetry(
        val profileId: Int,
        val allSubs: List<String>,
        val failed: List<String>,
        val sort: Sort,
        val streak: Int,
        val createdAt: Long
    ) {
        /** The subs this retry is NOT re-fetching (their fresh posts are in the DB). */
        val confirmedSubs: List<String>
            get() = allSubs.filter { c -> failed.none { it.equals(c, ignoreCase = true) } }
    }

    private val pendingRetryKey = stringPreferencesKey("feed_pending_retry")

    private fun PendingRetry.encodeLite(): String =
        runCatching { moshi.adapter(PendingRetry::class.java).toJson(this) }.getOrDefault("")

    private fun decodePendingRetry(json: String): PendingRetry? =
        runCatching { moshi.adapter(PendingRetry::class.java).fromJson(json) }.getOrNull()

    /**
     * Rebuild a [PendingRetry]'s confirmed per-sub results from the feed_cache table:
     * persistConfirmed() already atomically wrote each confirmed sub's fresh posts
     * before the retry started, so the DB is the source of truth. A sub whose rows
     * vanished (purge race, DB cleared) is put back on the failed list — re-fetching
     * is always safe; showing nothing would be a regression.
     */
    private suspend fun rebuildPendingResults(
        profileId: Int,
        allSubs: List<String>,
        failed: List<String>
    ): Pair<List<String>, Map<String, List<PostData>>> {
        val results = LinkedHashMap<String, List<PostData>>()
        val stillFailed = ArrayList(failed)
        for (sub in allSubs) {
            if (failed.any { it.equals(sub, ignoreCase = true) }) continue
            val rows = runCatching { db.feedCacheDao().bySubreddit(profileId, sub) }
                .getOrDefault(emptyList())
            val posts = rows.mapNotNull { toPostData(it.postJson) }
            if (posts.isNotEmpty()) {
                results[sub] = posts
            } else {
                stillFailed.add(sub)
            }
        }
        return stillFailed to results
    }

    /**
     * Restore a pending failed-sub retry that survived a process death (called from
     * [init] on every launch). Android kills backgrounded processes — the in-memory
     * chain dies with them, which is exactly why the pending state lives on disk.
     */
    private fun restorePendingRetry() {
        scope.launch {
            runCatching {
                val json = preferences.data.first()[pendingRetryKey] ?: return@launch
                val pending = decodePendingRetry(json) ?: return@launch
                if (pending.failed.isEmpty() || pending.allSubs.isEmpty()) {
                    preferences.edit { it.remove(pendingRetryKey) }
                    return@launch
                }
                if (System.currentTimeMillis() - pending.createdAt > PENDING_RETRY_TTL_MS) {
                    // Long past: the cached data is older than the pending window and a
                    // normal refresh will handle it — do not resurrect stale subs.
                    preferences.edit { it.remove(pendingRetryKey) }
                    FeedDebug.log(
                        "pending retry dropped (older than TTL): ${pending.failed.size} subs"
                    )
                    return@launch
                }
                // Rebuild the confirmed results from the DB; subs whose cache rows are
                // gone rejoin the failed list.
                val (failed, results) = rebuildPendingResults(
                    pending.profileId, pending.allSubs, pending.failed
                )
                if (failed.isEmpty()) {
                    preferences.edit { it.remove(pendingRetryKey) }
                    return@launch
                }
                FeedDebug.log(
                    "pending retry RESTORED after relaunch: ${failed.size} subs " +
                        "(streak=${pending.streak}, age=${(System.currentTimeMillis() - pending.createdAt) / 1000}s)"
                )
                startRetryChain(pending.copy(failed = failed), episode = episodeGeneration, results)
            }
        }
    }

    /**
     * Whether the app is in the foreground (at least partially visible), driven by
     * the single activity's onStart/onStop. Failed-sub retry rounds run ONLY while
     * this is true: on this device the backgrounded process's network is suspended
     * (DNS dies) — a round spent there is guaranteed to fail.
     */
    private val appVisible = MutableStateFlow(false)

    fun onForeground() {
        if (!appVisible.value) {
            appVisible.value = true
            FeedDebug.log("app foreground")
        }
    }

    fun onBackground() {
        if (appVisible.value) {
            appVisible.value = false
            FeedDebug.log("app background")
        }
    }

    init {
        // Restore a pending failed-sub retry that survived process death. This MUST
        // be a real init block (not a bare call in the field section): @Inject field
        // properties (preferences, db) are assigned by Hilt before init runs. Android
        // kills backgrounded processes — the in-memory chain dies with them, which is
        // exactly why the pending state lives on disk (a few hundred bytes, never
        // post payloads). The record was persisted the moment it was created, so on
        // every launch we pick it back up and let it run its foreground rounds from
        // where it stopped.
        restorePendingRetry()
    }

    /**
     * Epoch of the in-flight refresh episode. Bumped each time a NEW fan-out cycle
     * starts; the failed-sub retry checks it before writing state, so a chain that
     * outlived its episode (cancellation is cooperative and lags one suspension
     * point) can never write stale feed state.
     */
    @Volatile
    private var episodeGeneration = 0

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
        // Do NOT cancel the pending failed-sub retry here: the cache-first and
        // offline paths below never re-fetch, so cancelling the chain here would
        // strand the still-failed subs (the v2.5.52 trap in the other direction —
        // a plain tab-return refresh silently kills the pending retry). Only a
        // refresh that REACHES THE NETWORK supersedes it (see the fan-out block).
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
            // A refresh that REACHES THE NETWORK supersedes any pending failed-sub
            // retry: this cycle re-fetches every sub anyway, so the pending subs
            // either join this cycle's finalFailedSubs (re-persisted below) or are
            // confirmed here. Bump the episode so a still-running old chain bails,
            // and drop the persisted record.
            episodeGeneration++
            val episode = episodeGeneration
            retryChainJob?.cancel()
            retryChainJob = null
            runCatching {
                preferences.edit { it.remove(pendingRetryKey) }
            }
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
            //    The pending retry is PERSISTED before the first round (survives
            //    process death) and runs only while the app is foreground.
            if (finalFailedSubs.isNotEmpty()) {
                val resultsMap = if (lastConfirmed.size == subs.size) {
                    subs.zip(lastConfirmed).associate { (sub, list) -> sub to list }
                } else {
                    // No final emission was consumed (should not happen on this
                    // path): nothing is confirmed, everything is a retry target.
                    subs.associateWith { emptyList<PostData>() }
                }
                startRetryChain(
                    PendingRetry(
                        profileId = profileId,
                        allSubs = subs,
                        failed = finalFailedSubs.toList(),
                        sort = sort,
                        streak = 0,
                        createdAt = System.currentTimeMillis()
                    ),
                    episode = episode,
                    results = resultsMap
                )
            }
        }
        // Expose the cycle job so callers can await completion. [activeCycle]
        // was just assigned.
        return activeCycle ?: Job()
    }

    /**
     * Failed-sub auto-retry chain (v2.5.53): the follow-up to a cycle that
     * finished with failed subreddits — classically because the app was
     * backgrounded mid-refresh and Android suspended the process's network
     * (DNS dies: 153 x UnknownHostException in the 2026-09-06 device log).
     *
     * v2.5.52's design was inverted — it kept ticking WHILE backgrounded, so all
     * 3 rounds ran against the suspended link while the user was in another app
     * and the budget was spent before they came back. This version:
     *  - rounds run ONLY while the app is in the FOREGROUND ([appVisible]);
     *    backgrounding waits at zero cost — the budget is CONSECUTIVE EMPTY
     *    ROUNDS, not time, so a round may wait minutes for the foreground;
     *  - the pending state is PERSISTED to DataStore the moment it exists (a
     *    few hundred bytes: which subs, sort, streak — never post payloads; the
     *    confirmed subs' posts are already in the feed_cache table), so a
     *    process death cannot lose it; [restorePendingRetry] picks it back up
     *    on the next launch;
     *  - a round that CONFIRMS at least one sub resets the streak (the network
     *    works — finish the job); a round that confirms nothing counts against
     *    the budget (a genuinely dead network stops after
     *    [MAX_RETRY_ATTEMPTS] consecutive empty rounds);
     *  - a new [refresh] that reaches the network supersedes the chain;
     *    [episodeGeneration] keeps a superseded chain's state writes from
     *    interleaving with the new cycle.
     *
     * Every round ends in the SAME user-visible state as a manual pull-to-
     * refresh: the newly confirmed subs are persisted, merged into the feed,
     * and only the still-failed ones are listed (their cached posts kept).
     *
     * [results] is the in-memory confirmed per-sub map (base cycle's confirmed
     * results + any the chain confirms); it is NOT persisted — after a process
     * death [rebuildPendingResults] reconstructs it from the feed_cache table.
     */
    private fun startRetryChain(pending: PendingRetry, episode: Int, results: Map<String, List<PostData>>) {
        if (pending.failed.isEmpty()) return
        retryChainJob = scope.launch {
            // Persist BEFORE the first round: the window in which a process death
            // can lose the retry opens the moment the retry exists.
            runCatching { preferences.edit { it[pendingRetryKey] = pending.encodeLite() } }
            FeedDebug.log(
                "failed-sub retry started: ${pending.failed.size} subs " +
                    "(streak=${pending.streak} pendingAge=${(System.currentTimeMillis() - pending.createdAt) / 1000}s)"
            )
            var current = pending
            var confirmed = LinkedHashMap(results)
            while (current.failed.isNotEmpty() && current.streak < MAX_RETRY_ATTEMPTS) {
                if (episodeGeneration != episode) return@launch
                // 1. Foreground gate: wait (free) until the app is visible. This
                //    is the v2.5.52 correction — isOnline() alone is NOT a
                //    sufficient trigger, because the capability flag stays set
                //    for a backgrounded process whose sockets are dead.
                appVisible.first { it }
                if (episodeGeneration != episode) return@launch
                val waited = System.currentTimeMillis() - current.createdAt
                // 2. Backoff after a failed round: don't re-hit a link that just
                //    refused us. Round 1 (fresh start, no prior failure) goes
                //    straight in.
                if (current.streak > 0) {
                    val backoff = RETRY_BASE_DELAY_MS * (1L shl (current.streak - 1).coerceAtMost(2))
                    FeedDebug.log("failed-sub retry: backoff ${backoff}ms")
                    delay(backoff)
                    if (episodeGeneration != episode) return@launch
                }
                FeedDebug.log(
                    "failed-sub retry round: ${current.failed.size} subs " +
                        "foreground online=${isOnline()} waited=${waited / 1000}s streak=${current.streak}"
                )
                // 3. The round: re-fetch ONLY the still-failed subs (never the
                //    whole fan-out again). stream=false: a single final emission
                //    whose perSub is aligned 1:1 to the requested order — a dead
                //    sub still gets an empty entry and a failedSubs membership
                //    (verified: sendSnapshot builds perSub = subs.mapNotNull{...}
                //    in original order, failed subs recorded emptyList()), so the
                //    index into current.failed IS the index into page.perSub.
                val roundResult = runCatching {
                    officialSource.getSubredditFanOutProgressive(
                        multiredd = current.failed.joinToString("+"),
                        sort = current.sort,
                        timeSorting = null,
                        after = null,
                        stream = false
                    ).last()
                }
                if (episodeGeneration != episode) return@launch
                roundResult.fold(
                    onSuccess = { page ->
                        // Only subs the retry CONFIRMED move failed -> confirmed;
                        // the rest stay in the failed list (they keep their
                        // cached posts — the state's failedSubs names them).
                        val stillFailed = ArrayList(current.failed)
                        val confirmedThisRound = ArrayList<String>()
                        for ((idx, sub) in current.failed.withIndex()) {
                            val confirmed = idx < page.perSub.size &&
                                page.failedSubs.none { it.equals(sub, ignoreCase = true) }
                            if (confirmed) {
                                confirmed[sub] = page.perSub[idx].map { it.data }
                                stillFailed.remove(sub)
                                confirmedThisRound.add(sub)
                            }
                        }
                        if (confirmedThisRound.isNotEmpty()) {
                            // Persist exactly the subs confirmed THIS round
                            // (atomic per-sub replace, same semantics as the
                            // base cycle) so the cache matches the screen.
                            persistConfirmed(
                                profileId = current.profileId,
                                subs = confirmedThisRound,
                                confirmed = confirmedThisRound.map { confirmed[it] ?: emptyList() },
                                failedSubs = emptySet()
                            )
                            runPurge(current.profileId)
                        }
                        // Streak: a confirming round resets the budget (network
                        // works, finish the job); an empty round consumes one.
                        current = current.copy(
                            failed = stillFailed,
                            streak = if (confirmedThisRound.isEmpty()) current.streak + 1 else 0
                        )
                        emitRetryState(current, stillFailed, confirmed)
                        FeedDebug.log(
                            "failed-sub retry round done: confirmed=${confirmedThisRound.size} " +
                                "stillFailed=${stillFailed.size} streak=${current.streak}"
                        )
                        if (current.failed.isEmpty()) {
                            // All subs confirmed: the retry is complete, drop the
                            // persisted record.
                            runCatching { preferences.edit { it.remove(pendingRetryKey) } }
                            return@launch
                        }
                        // Persist the SHRUNKEN pending state before the next wait:
                        // if the process dies while backgrounded again, the next
                        // launch restores exactly what is still outstanding.
                        runCatching {
                            preferences.edit { it[pendingRetryKey] = current.encodeLite() }
                        }
                    },
                    onFailure = { e ->
                        // CancellationException: superseded by a new refresh or
                        // shutdown — stop silently. FeedBlockedException: the
                        // retry path hit a confirmed CF block — stop (retrying a
                        // block is the hammering pattern the stagger avoids).
                        if (e is kotlinx.coroutines.CancellationException ||
                            e is RedditOfficialSource.FeedBlockedException
                        ) {
                            return@launch
                        }
                        // A round that threw (network death mid-round) counts as
                        // an empty round against the streak.
                        current = current.copy(streak = current.streak + 1)
                        runCatching {
                            preferences.edit { it[pendingRetryKey] = current.encodeLite() }
                        }
                        FeedDebug.log(
                            "failed-sub retry round failed: ${e.javaClass.simpleName}: ${e.message} " +
                                "(streak=${current.streak})"
                        )
                    }
                )
            }
            if (episodeGeneration == episode && current.failed.isNotEmpty()) {
                // Budget spent (MAX_RETRY_ATTEMPTS consecutive empty rounds):
                // stop, and DROP the persisted record — a normal pull re-fetches
                // everything anyway, and leaving a stale record on disk would
                // resurrect a dead chain on every launch.
                runCatching { preferences.edit { it.remove(pendingRetryKey) } }
                FeedDebug.log(
                    "failed-sub retry: budget exhausted, " +
                        "${current.failed.size} subs left on cache"
                )
            }
        }
    }

    /** Re-emit the feed after a retry round, exactly like a pull-to-refresh would. */
    private suspend fun emitRetryState(
        current: PendingRetry,
        stillFailed: List<String>,
        confirmed: Map<String, List<PostData>>
    ) {
        // Fresh per-sub data for the merge, in the ORIGINAL sub order: confirmed
        // subs = base cycle results + this round's results; failed subs = empty
        // list — their cache rows stay in the DB and are merged in below (kept
        // whole by FeedMerge's failedSubs rule).
        val freshPerSub = current.allSubs.map { sub ->
            (confirmed[sub] ?: emptyList()).map { PostChild(it) }
        }
        val cache = loadCache(current.profileId)
        val merged = FeedMerge.merge(
            freshPerSub = freshPerSub,
            cache = cache,
            sort = current.sort,
            failedSubs = stillFailed.toSet()
        ).map { it.data }
        val freshIds = merged.map { it.name }.toHashSet()
        val (seen, saved) = badgeSets(current.profileId)
        val posts = mapToEntities(merged, seen, saved)
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
    }

    /** Seen/saved badge sets straight from the DB (the chain outlives refresh args). */
    private suspend fun badgeSets(profileId: Int): Pair<Set<String>, Set<String>> {
        val history = runCatching { db.historyDao().getHistoryIdsFromProfile(profileId).first() }
            .getOrDefault(emptyList())
        val saved = runCatching { db.postDao().getSavedPostIdsFromProfile(profileId).first() }
            .getOrDefault(emptyList())
        return history.toHashSet() to saved.toHashSet()
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
         * Failed-sub auto-retry budget: max CONSECUTIVE ROUNDS THAT CONFIRM
         * NOTHING. A round that confirms at least one sub resets the streak —
         * the network demonstrably works, so the chain keeps going until every
         * sub is confirmed or the network dies again. 3 empty rounds at
         * 15s/30s/60s backoff (≈105s of probing) is long enough for the
         * post-backgrounding network to recover, short enough to stop probing
         * a link that is genuinely gone.
         */
        private const val MAX_RETRY_ATTEMPTS = 3
        /** Base delay of the retry backoff (doubles per consecutive empty round). */
        private const val RETRY_BASE_DELAY_MS = 15_000L
        /**
         * A persisted pending retry older than this is dropped on restore: the
         * sub list it carries may be stale (the user can unsubscribe between
         * crashes) and its cache rows are already older than the pending window.
         * 24 h is generous; the record is tiny, so the cost of keeping it is
         * nothing and the cost of a stale one is one extra refresh.
         */
        private const val PENDING_RETRY_TTL_MS = 24L * 3_600_000L
    }
}
