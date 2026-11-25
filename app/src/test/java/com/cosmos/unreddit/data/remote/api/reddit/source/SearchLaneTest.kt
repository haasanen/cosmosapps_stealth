package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.ArcticApi
import com.cosmos.unreddit.data.remote.api.reddit.RedditApi
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search-lane behaviour pins (2026-11-25 "Something went wrong" reddit search report).
 *
 * Global search itself moved to the embedded reddit web UI (2.5.104) because
 * reddit made search JS-only server-side; these pins keep the user-selectable
 * source lanes (old.reddit scraping, Arctic Shift prefixes) honest: results
 * parse, and walls/rate-limits fail LOUD instead of faking "no results".
 *
 * Live ground truth captured 2026-11-25 from this datacenter:
 *  - www SSR /search/: JS challenge page (fixture search_challenge_djineo.html);
 *    solving + resubmitting hits reddit's "blocked by network security" 403
 *    (fixture search_block_403_networksecurity.html) — every non-JS client.
 *  - old.reddit /search?type=link logged-out: 342 KB "Welcome to Reddit" sign-in
 *    interstitial (fixture old_search_interstitial_welcome.html), a 200 that
 *    PostScraper would otherwise silently turn into "no results".
 *  - arctic-shift: users/search?author_prefix= + subreddits/search?subreddit_prefix=
 *    + subreddit-scoped posts/search work live; global full-text posts/search has
 *    no equivalent (400s on every unscoped parameter combo, verified).
 *
 * The results fixture below is constructed from PostScraper's own Selector
 * constants (div[id~=thing_t3_\w*] + the data-* attribute set) — same hand-rolled
 * DOM pattern as RedditOfficialSourceTest — because no logged-out old.reddit
 * search-results page can be captured from a datacenter exit today (that is the
 * bug). The interstitial fixture is a real live capture.
 */
class SearchLaneTest {

    // --- old.reddit scraping search ---------------------------------------------------

    private class ScrapingApi(private val body: String) : RedditApi {
        override suspend fun getSubreddit(
            subreddit: String, sort: Sort, timeSorting: TimeSorting?, after: String?, geoFilter: String?
        ) = unsupported()
        override suspend fun getSubredditInfo(subreddit: String) = unsupported()
        override suspend fun searchInSubreddit(
            subreddit: String, query: String, sort: Sort?, timeSorting: TimeSorting?, after: String?
        ) = unsupported()
        override fun getSubredditRules(subreddit: String): retrofit2.Call<com.cosmos.unreddit.data.remote.api.reddit.model.Child> =
            throw NotImplementedError()
        override suspend fun getPost(permalink: String, limit: Int?, sort: Sort) = unsupported()
        override suspend fun getMoreChildren(children: String, linkId: String) = unsupported()
        override suspend fun getUserInfo(user: String) = unsupported()
        override suspend fun getUserPosts(
            user: String, sort: Sort, timeSorting: TimeSorting?, after: String?
        ) = unsupported()
        override suspend fun getUserComments(
            user: String, sort: Sort, timeSorting: TimeSorting?, after: String?
        ) = unsupported()
        override suspend fun searchPost(
            query: String, sort: Sort?, timeSorting: TimeSorting?, after: String?
        ) = body.toResponseBody("text/html".toMediaType())
        override suspend fun searchUser(
            query: String, sort: Sort?, timeSorting: TimeSorting?, after: String?
        ) = unsupported()
        override suspend fun searchSubreddit(
            query: String, sort: Sort?, timeSorting: TimeSorting?, after: String?
        ) = unsupported()
        override suspend fun consentOver18(body: okhttp3.RequestBody, dest: String) = unsupported()
        private suspend fun unsupported(): ResponseBody = throw NotImplementedError()
    }

    private fun scrapingSource(body: String) = RedditScrapingSource(
        redditApi = ScrapingApi(body),
        ioDispatcher = Dispatchers.Unconfined,
        mainImmediateDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun `scraping search throws on the logged-out interstitial instead of faking empty`() = runBlocking {
        val source = scrapingSource(loadResource("old_search_interstitial_welcome.html"))

        val e = assertThrows(java.io.IOException::class.java) {
            runBlocking { source.searchPost("dji neo", Sort.HOT, null, null) }
        }
        assertTrue("message must name the interstitial: ${e.message}",
            e.message!!.contains("interstitial"))
    }

    @Test
    fun `scraping search parses old reddit search results`() = runBlocking {
        val source = scrapingSource(loadResource("old_search_results_synthetic.html"))

        val listing = source.searchPost("neo", Sort.HOT, null, null)
        val children = listing.data.children ?: emptyList()
        assertEquals("2 result cards expected", 2, children.size)
        val first = children[0] as com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
        assertEquals("t3_aaaaaaaaaa", first.data.name)
        assertEquals("Neo test title", first.data.title)
        assertEquals("t3_bbbbbbbbbb", (children[1] as com.cosmos.unreddit.data.remote.api.reddit.model.PostChild).data.name)
    }

    // --- arctic prefix fallbacks ------------------------------------------------------

    private class FakeArcticApi : ArcticApi {
        var usersPrefix: String? = null
        var subsPrefix: String? = null
        var usersStatus = 200

        override suspend fun searchPosts(
            subreddit: String?, author: String?, query: String?, sort: String?,
            before: Long?, after: Long?, limit: Int?
        ) = """{"data":[]}""".toResponseBody("application/json".toMediaType())
        override suspend fun getPostByIds(ids: String) = unsupported()
        override suspend fun searchComments(
            author: String?, sort: String?, before: Long?, after: Long?, limit: Int?
        ) = unsupported()
        override suspend fun getCommentsTree(linkId: String, limit: Int, startBreadth: Int, startDepth: Int) = unsupported()
        override suspend fun getSubredditByIds(ids: String) = unsupported()
        override suspend fun searchSubreddits(prefix: String, limit: Int): ResponseBody {
            subsPrefix = prefix
            return if (usersStatus == 422) http422() else """{"data":[]}""".toResponseBody("application/json".toMediaType())
        }
        override suspend fun searchUsers(author: String?, authorPrefix: String?, limit: Int): ResponseBody {
            usersPrefix = authorPrefix
            return if (usersStatus == 422) http422() else """{"data":[]}""".toResponseBody("application/json".toMediaType())
        }
        private fun http422(): ResponseBody = throw retrofit2.HttpException(
            retrofit2.Response.error<Unit>(422, "".toResponseBody(null))
        )
        private suspend fun unsupported(): ResponseBody = throw NotImplementedError()
    }

    private fun arcticSource(api: ArcticApi) = ArcticShiftSource(
        arcticApi = api,
        moshi = com.cosmos.unreddit.di.NetworkModule.provideRedditMoshi(),
        ioDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun `arctic user fallback forwards the query as author_prefix`() = runBlocking {
        val api = FakeArcticApi()
        val listing = arcticSource(api).searchUser("dji", Sort.HOT, null, null)
        assertEquals("author_prefix must carry the query", "dji", api.usersPrefix)
        assertEquals(0, (listing.data.children ?: emptyList()).size)
    }

    @Test
    fun `arctic subreddit fallback forwards the query as subreddit_prefix`() = runBlocking {
        val api = FakeArcticApi()
        val listing = arcticSource(api).searchSubreddit("dji", Sort.HOT, null, null)
        assertEquals("subreddit_prefix must carry the query", "dji", api.subsPrefix)
        assertEquals(0, (listing.data.children ?: emptyList()).size)
    }

    @Test
    fun `arctic rate-limit (422) surfaces instead of faking empty`() = runBlocking {
        val api = FakeArcticApi().apply { usersStatus = 422 }
        assertThrows(retrofit2.HttpException::class.java) {
            runBlocking { arcticSource(api).searchUser("dj", Sort.HOT, null, null) }
        }
        Unit
    }

    private fun loadResource(name: String) =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("reddit_ssr/$name"))
            .use { it.readBytes().decodeToString() }
}
