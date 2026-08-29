package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Anonymous Atom (RSS) feeds of www.reddit.com.
 *
 * As of 2026-08-29 (verified live) these are the only reddit.com endpoints that still
 * answer without a login: the `.json` API returns 403 and the HTML pages are
 * login-walled, while `…/.rss` feeds return HTTP 200 with full post data.
 *
 * A feed carries at most ~25–30 entries and has no pagination cursor, so
 * [com.cosmos.unreddit.data.remote.api.reddit.source.RedditOfficialSource] uses a
 * feed for the first page of a list and continues with the Arctic Shift archive
 * (epoch cursor taken from the oldest feed entry).
 *
 * Responses are [ResponseBody]s (no scalars converter in the dependency set); the
 * source reads them exactly once with [ResponseBody.string].
 */
interface RedditRssApi {

    /** Default ("hot") subreddit feed, or `r/popular` for the home feed. */
    @GET("r/{subreddit}/.rss")
    suspend fun subredditFeed(@Path("subreddit") subreddit: String): ResponseBody

    @GET("r/{subreddit}/new/.rss")
    suspend fun subredditNewFeed(@Path("subreddit") subreddit: String): ResponseBody

    @GET("r/{subreddit}/top/.rss")
    suspend fun subredditTopFeed(
        @Path("subreddit") subreddit: String,
        @Query("t") time: String
    ): ResponseBody

    @GET("r/{subreddit}/rising/.rss")
    suspend fun subredditRisingFeed(@Path("subreddit") subreddit: String): ResponseBody

    /** In-subreddit search (relevance order, the only order the feed supports). */
    @GET("r/{subreddit}/search/.rss")
    suspend fun subredditSearchFeed(
        @Path("subreddit") subreddit: String,
        @Query("q") query: String
    ): ResponseBody

    /** Global full-text search (works, verified 2026-08-29). */
    @GET("search/.rss")
    suspend fun globalSearchFeed(@Query("q") query: String): ResponseBody

    /**
     * User activity feed: a mix of comments (t1 entries) and own submissions
     * (t3 entries), newest first.
     */
    @GET("user/{user}/.rss")
    suspend fun userCommentsFeed(@Path("user") user: String): ResponseBody

    @GET("user/{user}/new/.rss")
    suspend fun userPostsFeed(@Path("user") user: String): ResponseBody

    companion object {
        const val BASE_URL = "https://www.reddit.com/"
    }
}
