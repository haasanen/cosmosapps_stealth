package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single reddit backend since the 2026-11 source cleanup: the app speaks to
 * OFFICIAL reddit.com only. The former multi-source selection (old.reddit
 * scraping, the legacy JSON API, Teddit, the Arctic Shift archive, Atom RSS)
 * was removed entirely by owner decision — "We use official reddit only"
 * (2026-11-25) — so this facade now delegates every call to
 * [RedditOfficialSource] and exists only so the repositories and paging
 * datasources keep one injection point.
 *
 * Note: global and in-subreddit SEARCH render reddit's own web UI in an
 * embedded WebView (ui/search/SearchFragment, ui/web/RedditWebView) because
 * reddit made search JS-only server-side; the search* methods here serve the
 * remaining native lanes (e.g. user search) against the same official source.
 */
@Singleton
class CurrentSource @Inject constructor(
    private val redditOfficialSource: RedditOfficialSource
) : BaseRedditSource {

    override suspend fun getSubreddit(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = redditOfficialSource.getSubreddit(subreddit, sort, timeSorting, after)

    override suspend fun getSubredditInfo(subreddit: String): Child =
        redditOfficialSource.getSubredditInfo(subreddit)

    override suspend fun probeSearch(query: String): String =
        redditOfficialSource.probeSearch(query)

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = redditOfficialSource.searchInSubreddit(subreddit, query, sort, timeSorting, after)

    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> =
        redditOfficialSource.getPost(permalink, limit, sort)

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren =
        redditOfficialSource.getMoreChildren(children, linkId)

    override suspend fun getUserInfo(user: String): Child =
        redditOfficialSource.getUserInfo(user)

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = redditOfficialSource.getUserPosts(user, sort, timeSorting, after)

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = redditOfficialSource.getUserComments(user, sort, timeSorting, after)

    override suspend fun searchPost(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = redditOfficialSource.searchPost(query, sort, timeSorting, after)

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = redditOfficialSource.searchUser(query, sort, timeSorting, after)

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = redditOfficialSource.searchSubreddit(query, sort, timeSorting, after)
}
