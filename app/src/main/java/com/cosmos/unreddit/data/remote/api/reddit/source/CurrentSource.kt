package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.model.preferences.DataPreferences
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentSource @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val redditSource: RedditSource,
    private val redditScrapingSource: RedditScrapingSource,
    private val tedditSource: TedditSource,
    private val arcticShiftSource: ArcticShiftSource,
    private val redditOfficialSource: RedditOfficialSource,
    private val redditAtomSource: RedditAtomSource
) : BaseRedditSource {

    private val mutex = Mutex()

    private var source: BaseRedditSource

    /** The currently selected reddit source; exposed so the UI can adapt (e.g. only the
     *  official source drives the progressive cached home feed). */
    var sourceType: DataPreferences.RedditSource
        private set

    init {
        val sourceValue = runBlocking { preferencesRepository.getRedditSource().first() }
        sourceType = DataPreferences.RedditSource.fromValue(sourceValue)
        source = getRedditSource(sourceValue)
    }

    override suspend fun getSubreddit(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.getSubreddit(subreddit, sort, timeSorting, after)
    }

    override suspend fun getSubredditInfo(subreddit: String): Child {
        return source.getSubredditInfo(subreddit)
    }

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        // 2026-11-25: the www sources' in-subreddit SSR search hits the same
        // network-security wall as global search; arctic answers it for real
        // (subreddit-scoped title/selftext search — live-verified).
        return when (sourceType) {
            DataPreferences.RedditSource.ARCTIC -> source.searchInSubreddit(subreddit, query, sort, timeSorting, after)
            else -> searchWithFallback(
                "insub/${sourceType.name.lowercase()}",
                { source.searchInSubreddit(subreddit, query, sort, timeSorting, after) },
                { arcticShiftSource.searchInSubreddit(subreddit, query, sort, timeSorting, after) }
            )
        }
    }

    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> {
        return source.getPost(permalink, limit, sort)
    }

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren {
        // TODO: Replace by source when an endpoint is available for Teddit
        return redditSource.getMoreChildren(children, linkId)
    }

    override suspend fun getUserInfo(user: String): Child {
        return source.getUserInfo(user)
    }

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.getUserPosts(user, sort, timeSorting, after)
    }

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.getUserComments(user, sort, timeSorting, after)
    }

    override suspend fun searchPost(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        // Arctic has its own (prefix-based) search; the "Reddit (official)" source has a
        // working live full-text search; the other sources fall back to the official API
        // (TODO: Replace by source when an endpoint is available for Teddit).
        // 2026-11-25: reddit's network security BLOCKS the JS-challenge resubmit for
        // /search/ on every non-JS client (challenge page -> solved GET -> 403
        // "You've been blocked by network security", fixture-captured; reddit.com/search.json
        // and api.reddit.com/search 403 outright). The www lanes therefore chain to the
        // old.reddit scraping source (which may itself interstitial -> the error the UI
        // shows names both layers via FeedDebug).
        return when (sourceType) {
            DataPreferences.RedditSource.ARCTIC -> source.searchPost(query, sort, timeSorting, after)
            DataPreferences.RedditSource.REDDIT_OFFICIAL -> searchWithFallback(
                "post/official",
                { redditOfficialSource.searchPost(query, sort, timeSorting, after) },
                { redditScrapingSource.searchPost(query, sort, timeSorting, after) }
            )
            else -> searchWithFallback(
                "post/json",
                { redditSource.searchPost(query, sort, timeSorting, after) },
                { redditScrapingSource.searchPost(query, sort, timeSorting, after) }
            )
        }
    }

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        // The official reddit.com user search (challenge-solving) is the only
        // RELIABLE full-list path, so it serves every selected source:
        //  - ARCTIC's `/api/users/search` is prefix-only (no match unless the
        //    query is the start of a username) and rate-limits aggressively
        //    (422 "slow down a bit" on common short prefixes) — verified live.
        //  - the old JSON `/search?type=user` endpoint (the previous "fallback")
        //    returns the 8KB JS-challenge HTML page, not JSON, so parsing always
        //    threw → the "Something went wrong" banner (2026-09-18 report).
        // 2026-11-25: the official lane now ALSO hits the network-security block
        // (see searchPost), so it gets a fallback: arctic's prefix search. Degraded
        // (only matches usernames STARTING with the query) but real data, not an error.
        return searchWithFallback(
            "user/official",
            { redditOfficialSource.searchUser(query, sort, timeSorting, after) },
            { arcticShiftSource.searchUser(query, sort, timeSorting, after) }
        )
    }

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        // 2026-11-25: chain to arctic's subreddit-prefix search (live-verified
        // `/api/subreddits/search?subreddit_prefix=`) when the selected source's lane
        // is blocked — for the www sources that is the same network-security wall.
        return when (sourceType) {
            DataPreferences.RedditSource.ARCTIC -> source.searchSubreddit(query, sort, timeSorting, after)
            else -> searchWithFallback(
                "subreddit/${sourceType.name.lowercase()}",
                { source.searchSubreddit(query, sort, timeSorting, after) },
                { arcticShiftSource.searchSubreddit(query, sort, timeSorting, after) }
            )
        }
    }

    suspend fun setRedditSource(value: Int) {
        mutex.withLock {
            sourceType = DataPreferences.RedditSource.fromValue(value)
            source = getRedditSource(value)
        }
    }

    private fun getRedditSource(value: Int): BaseRedditSource {
        return when(DataPreferences.RedditSource.fromValue(value)) {
            DataPreferences.RedditSource.REDDIT -> redditSource
            DataPreferences.RedditSource.TEDDIT -> tedditSource
            DataPreferences.RedditSource.REDDIT_SCRAP -> redditScrapingSource
            DataPreferences.RedditSource.ARCTIC -> arcticShiftSource
            DataPreferences.RedditSource.REDDIT_OFFICIAL -> redditOfficialSource
            DataPreferences.RedditSource.REDDIT_ATOM -> redditAtomSource
        }
    }
}

/**
 * Runs [primary]; on any failure logs the reason to FeedDebug and runs [fallback].
 * A failure in the fallback propagates (it is the last layer).
 */
internal suspend fun searchWithFallback(
    lane: String,
    primary: suspend () -> Listing,
    fallback: suspend () -> Listing
): Listing {
    try {
        return primary()
    } catch (e: Exception) {
        com.cosmos.unreddit.ui.postlist.FeedDebug.log(
            "search $lane failed (${e.javaClass.simpleName}: ${e.message}); falling back"
        )
    }
    return fallback()
}
