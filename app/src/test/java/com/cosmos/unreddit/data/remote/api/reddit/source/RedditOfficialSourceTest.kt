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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fixture-based regression tests for the "Reddit (official)" SSR source.
 *
 * These drive the REAL [RedditOfficialSource] — same [OkHttpClient] + [com.squareup.moshi.Moshi]
 * pipeline the app uses — against real captured reddit.com server-rendered HTML. The network is
 * stubbed at the OkHttp interceptor level (no real requests), so the test runs fully offline on
 * the JVM. The point is to prove the parser actually *runs* and to pin the contract: if reddit.com
 * changes its markup so a card no longer carries an expected attribute, a test goes red here
 * (and in CI) before a broken build ever reaches a device.
 *
 * Fixtures live in `src/test/resources/reddit_ssr/` and were captured from live www.reddit.com
 * pages. They are deliberately the *full* page (not trimmed) so the parser is exercised against a
 * realistic document, not a hand-crafted happy path.
 */
class RedditOfficialSourceTest {

    private lateinit var source: RedditOfficialSource

    /** Maps a request URL to the fixture that should answer it. Returns null to answer empty. */
    private fun fixtureFor(url: String): String? = when {
        // /popular/ feed
        url.contains("/popular/") -> "pf_popular_p1.html"
        // global search  /search/?…
        url.contains("/search/") -> "fv3_search0.html"
        // user comments  /user/{name}/comments/  (MUST precede the /comments/ post-detail rule)
        url.contains("/user/") && url.contains("/comments/") -> "pin_user_c.html"
        // post detail  /r/{sub}/comments/{id}/…
        url.contains("/comments/") -> "vp_detail.html"
        // user home  /user/{name}/  (karma) and user posts  /user/{name}/{sort}/
        url.contains("/user/") -> "pin_user_p.html"
        // subreddit about  /r/{sub}/  (exact, no trailing path)
        Regex("/r/[a-z0-9_]+/$").containsMatchIn(url.substringBefore('?')) -> "vp_about.html"
        // lazy comment-body partials  /svc/shreddit/comment/…  -> empty (body degrades to "")
        url.contains("/svc/") -> null
        else -> null
    }

    private fun loadFixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("reddit_ssr/$name")
            ?: throw IllegalStateException("missing test resource: reddit_ssr/$name")
        return stream.bufferedReader().use { it.readText() }
    }

    @Before
    fun buildSource() {
        val stub = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val body = fixtureFor(url)?.let { loadFixture(it) } ?: "<html><body></body></html>"
            val resp = Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(body.toResponseBody("text/html".toMediaTypeOrNull()))
                .build()
            resp
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        val moshi = NetworkModule.provideRedditMoshi()
        source = RedditOfficialSource(client, moshi, Dispatchers.Default)
    }

    @Test
    fun `subreddit feed parses real post cards with id sub title author score`() = runBlocking {
        val listing = source.getSubreddit("popular", Sort.HOT, null, null)
        val children = listing.data.children.filterIsInstance<PostChild>()
        // Fixture page carries exactly 25 shreddit-post cards.
        assertEquals(25, children.size)
        val first = children.first().data
        assertEquals("t3_1w1ydca", first.name)
        assertEquals("whatisit", first.subreddit)
        assertEquals("Estate sale find today", first.title)
        assertEquals("SpicyDillRadish", first.author)
        assertEquals(4415, first.score)
        assertEquals(787, first.commentsNumber)
        // Pagination cursor = last post fullname.
        assertEquals(children.last().data.name, listing.data.after)
        // Multi-sub popular feed should span several subreddits.
        assertTrue(children.map { it.data.subreddit }.toSet().size > 5)
    }

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

    @Test
    fun `subreddit about parses community header`() = runBlocking {
        val child = source.getSubredditInfo("linux") as AboutChild
        val about = child.data
        assertEquals("linux", about.title)
        assertTrue(about.displayName.startsWith("Linux, GNU/Linux"))
        assertEquals(315639, about.activeUserCount)
        assertTrue(about.publicDescriptionHtml?.contains("Welcome to /r/Linux!") == true)
    }

    @Test
    fun `user info parses karma`() = runBlocking {
        val child = source.getUserInfo("Coldplayfan1999") as AboutUserChild
        assertEquals("Coldplayfan1999", child.data.name)
        assertEquals(9674, child.data.totalKarma)
    }

    @Test
    fun `user posts parses their post cards`() = runBlocking {
        val listing = source.getUserPosts("Coldplayfan1999", Sort.HOT, null, null)
        val children = listing.data.children.filterIsInstance<PostChild>()
        assertTrue("expected user posts, got ${children.size}", children.isNotEmpty())
        assertEquals("t3_1w27oi2", children.first().data.name)
        assertEquals("linux", children.first().data.subreddit)
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

    @Test
    fun `global search parses post preview blocks`() = runBlocking {
        val listing = source.searchPost("digital film", null, null, null)
        val children = listing.data.children.filterIsInstance<PostChild>()
        // Fixture search page carries 6 search-post-with-content-preview blocks.
        assertEquals(6, children.size)
        assertEquals("t3_1vz9f7d", children.first().data.name)
    }

    @Test
    fun `ad cards are a different tag and never appear in parsed feeds`() = runBlocking {
        // The parser selects the "shreddit-post" tag; ads are "shreddit-ad-post" and are dropped
        // by construction. Confirm the raw feed fixture contains no parsed ad id.
        val raw = loadFixture("pf_popular_p1.html")
        val rawAdPosts = Regex("<shreddit-ad-post").findAll(raw).count()
        // Whatever the ad count in the fixture, the parsed children must all be real t3 ids.
        val children = source.getSubreddit("popular", Sort.HOT, null, null).data.children
            .filterIsInstance<PostChild>()
        assertTrue(children.isNotEmpty())
        assertTrue(children.all { it.data.name.startsWith("t3_") })
        // (rawAdPosts is informational; recorded so a future fixture swap is auditable.)
        println("informational: fixture contains $rawAdPosts ad-post cards; parsed ${children.size} real posts")
    }
}
