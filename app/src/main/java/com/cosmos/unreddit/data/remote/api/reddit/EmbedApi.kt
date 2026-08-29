package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * The official embed service of reddit.com (`embed.reddit.com`).
 *
 * As of 2026-08-29 (verified live, 200s with a real browser user-agent) this is the only
 * unblocked reddit.com endpoint that exposes the **live** upvote count and comment count:
 * each post's embed page server-renders
 *
 *   `<faceplate-number number="2274" pretty></faceplate-number> upvotes`
 *   `View 729 comments`
 *
 * The anonymous `.json` API returns 403 (bot wall) and the public Arctic archive only
 * carries the placeholder values reddit hands anonymous clients (`score: 1`,
 * `num_comments: 0`) for fresh posts - which is exactly why the old feed-only
 * enrichment showed wrong numbers. The `.rss` Atom feeds do not expose any counts at
 * all. So the embed page is the official, live source for score/comment count.
 *
 * Rate limits (measured 2026-08-29): 15/15 sequential fetches at ~3s intervals and
 * 12/12 fully parallel fetches, no 429s. A page is ~320KB of HTML, so at most ~25 of
 * them are fetched per feed page.
 *
 * The response is a [ResponseBody] (no scalars converter in the dependency set); the
 * source reads it exactly once with [ResponseBody.string] and pulls the two numbers
 * out with regular expressions - no HTML parser needed.
 */
interface EmbedApi {

    /** The official embed page of one post. Returns 200 for live posts. */
    @GET("r/{subreddit}/comments/{postId}")
    suspend fun postEmbed(
        @Path("subreddit") subreddit: String,
        @Path("postId") postId: String
    ): ResponseBody

    companion object {
        const val BASE_URL = "https://embed.reddit.com/"
    }
}
