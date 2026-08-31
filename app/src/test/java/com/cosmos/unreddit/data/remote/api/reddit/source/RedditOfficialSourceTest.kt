package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Fixture-based regression tests for the "Reddit (official)" SSR source.
 *
 * These drive the REAL [RedditOfficialSource] — the same OkHttpClient + Moshi pipeline the app
 * uses — against real captured reddit.com server-rendered HTML (fixtures in
 * `src/test/resources/reddit_ssr/`, full pages, not trimmed). The network is stubbed at the
 * OkHttp interceptor level, so the tests run fully offline on the JVM.
 *
 * Beyond pure parsing, they also exercise the live-fetch mechanics that the committed parser-only
 * suite could not see: the JS-challenge resubmit (absolute URL, doubled 16-hex solution, token)
 * and the "load more" continuation partial fetch + merge. If reddit.com changes the contract —
 * a challenge parameter, a card attribute, the continuation selector — a test goes red here
 * (and in CI) before a broken build reaches a device.
 */
class RedditOfficialSourceTest {

    private lateinit var source: RedditOfficialSource

    /** Records the request URLs the source asked for, in order. */
    private val requestedUrls = mutableListOf<String>()

    /** Counts the JS-challenge resubmit requests (GET with a js_challenge=1 param). */
    private val resubmits = mutableListOf<okhttp3.HttpUrl>()

    private fun loadFixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("reddit_ssr/$name")
            ?: throw IllegalStateException("missing test resource: reddit_ssr/$name")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun loadFixtureBytes(name: String): String = loadFixture(name)

    /**
     * Maps a request URL to the fixture that should answer it, in priority order.
     * The rules follow the URL contract the source builds (see RedditOfficialSource):
     *  - challenge resubmits: `js_challenge=1` in the query
     *  - lazy partials: `/svc/...` (continuations, comment bodies)
     *  - post detail: `/r/{sub}/comments/{id}/`
     *  - user posts: `/user/{u}/submitted/`
     *  - user comments: `/user/{u}/comments/`
     *  - user home (karma): `/user/{u}/`
     *  - search: `/search/` (global) or `/r/{sub}/search/` (in-sub)
     *  - subreddit about: `/r/{sub}/`
     *  - subreddit feed: `/r/{sub}/{sort}/`
     */
    private fun fixtureFor(url: okhttp3.HttpUrl): String {
        requestedUrls += url.toString()
        val path = url.encodedPath
        if (url.queryParameter("js_challenge") == "1") {
            resubmits += url
            return "ra_android_p1.html"
        }
        if (path.startsWith("/svc/")) {
            return when {
                path.contains("community-more-posts") -> "cont_android_p1.html"
                path.contains("profile_posts-more-posts") -> "sub_user_cont.html"
                path.contains("comment/") -> "" // comment bodies degrade to ""
                else -> ""
            }
        }
        return when {
            // user profile comments: /user/{u}/comments/  (must precede the /comments/ detail rule)
            path.contains("/user/") && path.contains("/comments/") -> "pin_user_c.html"
            // post detail: /r/{sub}/comments/{id}/
            path.contains("/comments/") -> "vp_detail.html"
            path.contains("/submitted/") -> "sub_user_p1.html"
            path.contains("/user/") -> "pin_user_p.html"
            path.contains("/search/") -> when {
                url.queryParameter("type") == "user" -> "search_user.html"
                url.queryParameter("type") == "community" -> "search_community.html"
                path.startsWith("/r/") -> "insub_search1.html"
                else -> "fv3_search0.html"
            }
            Regex("^/r/[a-zA-Z0-9_]+/$").containsMatchIn(path) -> "vp_about.html"
            path.contains("/r/Android/hot/") -> "ra_android_p1.html"
            else -> "ra_android_p1.html"
        }
    }

    private fun okResponse(req: okhttp3.Request, body: String): Response = Response.Builder()
        .request(req)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .header("Content-Type", "text/html; charset=utf-8")
        .body(body.toResponseBody("text/html".toMediaTypeOrNull()))
        .build()

    @Before
    fun buildSource() {
        requestedUrls.clear()
        resubmits.clear()
        val stub = Interceptor { chain ->
            val req = chain.request()
            okResponse(req, loadFixture(fixtureFor(req.url)))
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        val moshi = NetworkModule.provideRedditMoshi()
        source = RedditOfficialSource(client, moshi, Dispatchers.Default)
    }

    //region Subreddit feed

    @Test
    fun `subreddit feed merges SSR cards with the load-more continuation`() = runBlocking {
        val listing = source.getSubreddit("Android", Sort.HOT, null, null)
        val children = listing.data.children.filterIsInstance<PostChild>()
        // 3 cards server-rendered in ra_android_p1 + 24 in the continuation partial.
        assertEquals(27, children.size)
        val first = children.first().data
        assertEquals("t3_1w2mtlr", first.name)
        assertEquals("Android", first.subreddit)
        assertEquals("FragmentedChicken", first.author)
        assertEquals(215, first.score)
        // Pagination cursor = last (merged) post fullname, i.e. the continuation's last card.
        assertEquals("t3_1w14j1r", listing.data.after)
        // No duplicates after the merge.
        assertEquals(children.size, children.map { it.data.name }.toSet().size)
        // The continuation partial was actually fetched from the page's signed src.
        assertTrue(
            "expected a /svc/shreddit/community-more-posts request",
            requestedUrls.any { it.startsWith("https://www.reddit.com/svc/shreddit/community-more-posts/") }
        )
    }

    @Test
    fun `subreddit feed never duplicates a post across the SSR cards and the continuation`() = runBlocking {
        val listing = source.getSubreddit("Android", Sort.HOT, null, null)
        val names = listing.data.children.filterIsInstance<PostChild>().map { it.data.name }
        assertEquals("merged feed must contain no duplicate fullnames", names.size, names.toSet().size)
    }

    //endregion

    //region JS challenge

    @Test
    fun `first request answered with a JS challenge is solved and resubmitted with an absolute url`() = runBlocking {
        // Point the whole flow at the challenge fixture first: the source must solve it over
        // plain HTTP and only then parse the real page.
        requestedUrls.clear()
        resubmits.clear()
        val challengeSource = buildSourceWith(firstBody = "ch_js_challenge.html")
        val listing = challengeSource.getSubreddit("Android", Sort.HOT, null, null)
        val children = listing.data.children.filterIsInstance<PostChild>()
        assertTrue("expected posts after solving the challenge, got ${children.size}", children.isNotEmpty())

        // Exactly one resubmit happened.
        assertEquals(1, resubmits.size)
        val resubmit = resubmits[0]
        // The form action is relative (/r/Android/hot/) — the source must send an ABSOLUTE URL.
        assertEquals("https", resubmit.scheme)
        assertEquals("www.reddit.com", resubmit.host)
        assertEquals("/r/Android/hot/", resubmit.encodedPath)
        // The original page's own query params are preserved (the browser copies them).
        assertEquals("25", resubmit.queryParameter("count"))
        // The solution is the 16-hex literal from the challenge fixture, doubled.
        val solution = resubmit.queryParameter("solution") ?: ""
        assertEquals("76c86b1b8d9d9ddc76c86b1b8d9d9ddc", solution)
        // The form token round-trips.
        assertNotNull(resubmit.queryParameter("token"))
        assertEquals("", resubmit.queryParameter("jsc_orig_r"))
    }

    /** Builds a source whose first response for the feed URL is a named fixture. */
    private fun buildSourceWith(firstBody: String): RedditOfficialSource {
        val stub = Interceptor { chain ->
            val req = chain.request()
            val url = req.url
            val body = when {
                // The challenge resubmit: the solved real page.
                url.queryParameter("js_challenge") == "1" -> {
                    resubmits += url
                    loadFixtureBytes("ra_android_p1.html")
                }
                // The very first request of the flow: the challenge page itself.
                requestedUrls.isEmpty() -> loadFixtureBytes(firstBody)
                else -> loadFixtureBytes(fixtureFor(url))
            }
            okResponse(req, body)
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        return RedditOfficialSource(client, NetworkModule.provideRedditMoshi(), Dispatchers.Default)
    }

    //endregion

    //region Post detail

    @Test
    fun `post detail parses OP and comment cards in depth order`() = runBlocking {
        val result = source.getPost("/r/linux/comments/1w2gg8x/how_linux_distro_provide_higher_cpu_benchmark/", null, Sort.HOT)
        assertEquals(2, result.size)
        val (postListing, commentsListing) = result
        val op = postListing.data.children.first() as PostChild
        assertEquals("t3_1w2gg8x", op.data.name)
        assertEquals("linux", op.data.subreddit)
        assertEquals("How linux distro provide higher CPU benchmark scores than windows?", op.data.title)

        val comments = commentsListing.data.children.filterIsInstance<CommentChild>()
        // Detail page SSR carries 25 shreddit-comment cards.
        assertEquals(25, comments.size)
        val first = comments.first().data
        assertEquals("t1_p6s8oxo", first.name)
        assertEquals("d0pe-asaurus", first.author)
        assertEquals(0, first.depth)
        assertEquals("t3_1w2gg8x", first.linkId)
        assertTrue(first.permalink.contains("/r/linux/comments/1w2gg8x/"))
    }

    //endregion

    //region Subreddit about

    @Test
    fun `subreddit about parses community header`() = runBlocking {
        val child = source.getSubredditInfo("linux") as AboutChild
        val about = child.data
        assertEquals("linux", about.title)
        assertTrue(about.displayName.startsWith("Linux, GNU/Linux"))
        assertEquals(315639, about.activeUserCount)
        assertTrue(about.publicDescriptionHtml?.contains("Welcome to /r/Linux!") == true)
    }

    //endregion

    //region User

    @Test
    fun `user info parses karma`() = runBlocking {
        val child = source.getUserInfo("Coldplayfan1999") as AboutUserChild
        assertEquals("Coldplayfan1999", child.data.name)
        assertEquals(9674, child.data.totalKarma)
    }

    @Test
    fun `user posts use the submitted url and merge the continuation`() = runBlocking {
        val listing = source.getUserPosts("Coldplayfan1999", Sort.HOT, null, null)
        // The source must target the live URL: /user/{u}/submitted/?count=25&sort=hot
        assertTrue(
            "expected /user/Coldplayfan1999/submitted/ request, got: $requestedUrls",
            requestedUrls.any {
                it.startsWith("https://www.reddit.com/user/Coldplayfan1999/submitted/") &&
                    it.contains("count=25") && it.contains("sort=hot")
            }
        )
        // And it must NOT use the dead /user/{u}/{sort}/ URL.
        assertFalse(requestedUrls.any { it.contains("/user/Coldplayfan1999/hot/") })

        val children = listing.data.children.filterIsInstance<PostChild>()
        // 3 SSR (sub_user_p1) + 25 continuation (sub_user_cont).
        assertEquals(28, children.size)
        val first = children.first().data
        assertEquals("t3_1w2qlbj", first.name)
        assertEquals("Coldplayfan1999", first.author)
        // Cursor = last merged card.
        assertEquals("t3_1vp6pwl", listing.data.after)
    }

    @Test
    fun `user comments parses profile comment cards`() = runBlocking {
        val listing = source.getUserComments("Coldplayfan1999", Sort.HOT, null, null)
        val children = listing.data.children.filterIsInstance<CommentChild>()
        assertTrue("expected profile comments, got ${children.size}", children.isNotEmpty())
        // First profile-comment card in the fixture.
        assertEquals("t1_p6tt7o6", children.first().data.name)
        // Bodies come from lazy /svc/ partials (stubbed empty) -> degrade to "", never throw.
        assertFalse(children.isEmpty())
    }

    //endregion

    //region Search

    @Test
    fun `global search parses both legacy and unit result blocks`() = runBlocking {
        val listing = source.searchPost("digital film", null, null, null)
        val children = listing.data.children.filterIsInstance<PostChild>()
        // Fixture fv3_search0 carries 6 legacy blocks + 1 unit block, all distinct posts.
        assertEquals(7, children.size)
        assertEquals("t3_1vz9f7d", children.first().data.name)
        // The unit block's post is present too (it only has the new markup).
        assertTrue(children.any { it.data.name == "t3_1w1wr5t" })
    }

    @Test
    fun `in-subreddit search parses the unit markup`() = runBlocking {
        val listing = source.searchInSubreddit("Android", "bigme", null, null, null)
        val children = listing.data.children.filterIsInstance<PostChild>()
        assertTrue("expected in-sub search results, got ${children.size}", children.isNotEmpty())
        assertEquals("t3_1w2gg8x", children.first().data.name)
        assertEquals("linux", children.first().data.subreddit)
    }

    @Test
    fun `user search parses profile blocks`() = runBlocking {
        val listing = source.searchUser("coldplayfan", null, null, null)
        val children = listing.data.children
        assertEquals(4, children.size)
        val first = children.first() as AboutUserChild
        assertEquals("coldplayfan", first.data.name)
    }

    @Test
    fun `community search parses subreddit blocks`() = runBlocking {
        val listing = source.searchSubreddit("linux", null, null, null)
        val children = listing.data.children
        assertEquals(5, children.size)
        val first = children.first() as AboutChild
        assertEquals("linux", first.data.displayName)
        assertEquals("/r/linux/", first.data.url)
    }

    //endregion

    //region Invariants

    @Test
    fun `post ids are always real t3 fullnames`() = runBlocking {
        val children = source.getSubreddit("Android", Sort.HOT, null, null).data.children
            .filterIsInstance<PostChild>()
        assertTrue(children.isNotEmpty())
        assertTrue(children.all { it.data.name.startsWith("t3_") })
    }

    //endregion
}
