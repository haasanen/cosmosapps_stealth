package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * API client for the Arctic Shift archive (https://github.com/photon-reddit/arctic_shift).
 *
 * Arctic Shift stores native Reddit JSON objects, so responses are parsed into the app's
 * existing models (PostData, CommentData, AboutData, ...) by [ArcticShiftSource].
 *
 * Note: `over_18` is never sent - on this API it is an equality filter (only exact matches),
 * not an "include NSFW" flag. Requests therefore return all content and NSFW filtering is
 * done client-side by the app.
 */
interface ArcticApi {

    @GET("/api/posts/search?md2html=true")
    suspend fun searchPosts(
        @Query("subreddit") subreddit: String? = null,
        @Query("author") author: String? = null,
        @Query("query") query: String? = null,
        @Query("sort") sort: String?,
        @Query("before") before: Long? = null,
        @Query("after") after: Long? = null,
        @Query("limit") limit: Int? = null
    ): ResponseBody

    @GET("/api/posts/ids?md2html=true")
    suspend fun getPostByIds(@Query("ids") ids: String): ResponseBody

    @GET("/api/comments/search?md2html=true")
    suspend fun searchComments(
        @Query("author") author: String? = null,
        @Query("sort") sort: String?,
        @Query("before") before: Long? = null,
        @Query("after") after: Long? = null,
        @Query("limit") limit: Int? = null
    ): ResponseBody

    @GET("/api/comments/tree?md2html=true")
    suspend fun getCommentsTree(
        @Query("link_id") linkId: String,
        @Query("limit") limit: Int = TREE_LIMIT,
        @Query("start_breadth") startBreadth: Int = TREE_BREADTH,
        @Query("start_depth") startDepth: Int = TREE_DEPTH
    ): ResponseBody

    @GET("/api/subreddits/ids")
    suspend fun getSubredditByIds(@Query("ids") ids: String): ResponseBody

    @GET("/api/subreddits/search")
    suspend fun searchSubreddits(
        @Query("subreddit_prefix") prefix: String,
        @Query("limit") limit: Int = 50
    ): ResponseBody

    @GET("/api/users/search")
    suspend fun searchUsers(
        @Query("author") author: String? = null,
        @Query("author_prefix") authorPrefix: String? = null,
        @Query("limit") limit: Int = 50
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://arctic-shift.photon-reddit.com/"
        const val TREE_LIMIT = 9999
        const val TREE_BREADTH = 1000
        const val TREE_DEPTH = 1000
    }
}
