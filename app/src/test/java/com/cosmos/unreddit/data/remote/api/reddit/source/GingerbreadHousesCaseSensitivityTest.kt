package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression: r/GingerbreadHouses (and any subreddit whose name is not all-lowercase)
 * served by reddit.com as a 200 "shell" page — full chrome, no subreddit header, zero
 * shreddit-post cards — when fetched with the wrong case. Before the fix this produced
 * a generic "Something went wrong" banner (the messageless-exception path) instead of
 * posts.
 *
 * Fix under test: on a shell page the source fetches the sub's Atom feed (open, no
 * challenge, case-insensitive) to recover the canonical name from
 * `<category term="GingerbreadHouses" .../>` and retries with the correct case.
 *
 * Fixtures (captured 2026-09-21 from reddit.com):
 *  - gbh_shell.html      360 KB shell page for /r/gingerbreadhouses/hot/ (0 posts, 0 header)
 *  - gbh_canonical.rss   the .rss feed carrying the canonical name
 *  - gbh_canonical.html  the real page for /r/GingerbreadHouses/hot/ (3 posts, 2 headers)
 */
class GingerbreadHousesCaseSensitivityTest {

    private lateinit var source: RedditOfficialSource
    private val requestedUrls = mutableListOf<String>()

    private fun loadFixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("reddit_ssr/$name")
            ?: throw IllegalStateException("missing test resource: reddit_ssr/$name")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun fixtureFor(url: okhttp3.HttpUrl): String {
        requestedUrls += url.toString()
        val path = url.encodedPath
        return when {
            // Canonical-name lookup: the open Atom feed (any case in the path).
            path.contains(".rss") -> "gbh_canonical.rss"
            // Correctly-cased community (feed or about) → the real page.
            path.contains("/r/GingerbreadHouses/") -> "gbh_canonical.html"
            // Wrong-case community (feed or about) → the shell page reddit.com served.
            path.contains("/r/gingerbreadhouses/") -> "gbh_shell.html"
            else -> "gbh_shell.html"
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

    private fun buildSource(): RedditOfficialSource {
        val stub = Interceptor { chain ->
            val req = chain.request()
            okResponse(req, loadFixture(fixtureFor(req.url)))
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        return RedditOfficialSource(client, NetworkModule.provideRedditMoshi(), Dispatchers.Default)
    }

    @Before
    fun setUp() {
        requestedUrls.clear()
        source = buildSource()
    }

    @Test
    fun `wrong-case sub resolves canonical name from rss and loads real feed`() = runBlocking {
        val listing = source.getSubreddit("gingerbreadhouses", Sort.HOT, null, null)
        val posts = listing.data.children.filterIsInstance<PostChild>()
        // The correctly-cased page had 3 posts.
        assertEquals("shell page must be retried with the canonical case", 3, posts.size)
        // Cards carry the subreddit exactly as the page names it.
        assertTrue(posts.first().data.subreddit.contains("GingerbreadHouses"))
        // The recovery actually happened: RSS consulted, then the correct-case feed fetched.
        assertTrue("expected an .rss canonical-name lookup", requestedUrls.any { it.contains(".rss") })
        assertTrue(
            "expected a retry against /r/GingerbreadHouses/",
            requestedUrls.any { it.contains("/r/GingerbreadHouses/hot/") }
        )
        // No duplicates.
        assertEquals(posts.size, posts.map { it.data.name }.toSet().size)
    }

    @Test
    fun `wrong-case sub about page recovers instead of throwing`() = runBlocking {
        val child = source.getSubredditInfo("gingerbreadhouses")
        val about = child as? AboutChild
            ?: throw AssertionError("expected AboutChild, got $child")
        assertTrue(
            "about must come from the recovered page (title + description + active users parsed), got: ${about.data}",
            about.data.title == "GingerbreadHouses" &&
                about.data.activeUserCount == 93 &&
                about.data.publicDescriptionHtml?.contains("gingerbread") == true
        )
        assertTrue("expected an .rss canonical-name lookup", requestedUrls.any { it.contains(".rss") })
        assertTrue(
            "expected a retry against /r/GingerbreadHouses/",
            requestedUrls.any { it.startsWith("https://www.reddit.com/r/GingerbreadHouses/") }
        )
    }
}
