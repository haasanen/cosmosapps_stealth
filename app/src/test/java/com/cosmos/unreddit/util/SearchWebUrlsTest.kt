package com.cosmos.unreddit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function pins for the WebView search UI (see SearchWebUrls).
 * Ground truth captured live 2026-11-25.
 */
class SearchWebUrlsTest {

    @Test
    fun `search url encodes the query`() {
        assertEquals(
            "https://www.reddit.com/search/?q=dji%20neo",
            SearchWebUrls.search("dji neo")
        )
        assertEquals(
            "https://www.reddit.com/search/?q=neo%202%20%26%20mini",
            SearchWebUrls.search("neo 2 & mini")
        )
    }

    @Test
    fun `in-subreddit url scopes and restricts`() {
        assertEquals(
            "https://www.reddit.com/r/DJI/search/?q=neo&restrict_sr=1",
            SearchWebUrls.inSubreddit("DJI", "neo")
        )
        // Tolerate the forms the UI can hand over.
        assertEquals(
            "https://www.reddit.com/r/DJI/search/?q=neo&restrict_sr=1",
            SearchWebUrls.inSubreddit("r/DJI", "neo")
        )
        assertEquals(
            "https://www.reddit.com/r/DJI/search/?q=neo&restrict_sr=1",
            SearchWebUrls.inSubreddit("/DJI/ ", "neo")
        )
    }

    @Test
    fun `search pages stay in the webview`() {
        for (u in listOf(
            "https://www.reddit.com/search/?q=dji+neo",
            "https://www.reddit.com/search/?q=dji%20neo&type=link&sort=relevance&t=WEEK",
            "https://www.reddit.com/search/?q=dji+neo&type=community&after=t5_2wu4v",
            "https://www.reddit.com/r/DJI/search/?q=neo&restrict_sr=1&sort=relevance",
            "https://old.reddit.com/search/?q=dji+neo",
            "https://np.reddit.com/r/GarageGym/search/?q=cam",
        )) assertTrue("must stay in webview: $u", SearchWebUrls.isSearchPage(u))
    }

    @Test
    fun `non-search links leave the webview`() {
        for (u in listOf(
            "https://www.reddit.com/r/DjiNeo/comments/1xzndhv/gettin_dressed/",
            "https://www.reddit.com/r/DJI/hot/",
            "https://www.reddit.com/user/testauthor/",
            "https://i.redd.it/49sr7t7d3f2i1.gif",
            "https://v.redd.it/i2no06sa8y1i1",
            "https://www.example.com/search/?q=x",      // not a reddit host
            "https://www.reddit.com/d/creative_search", // path segment only
            "not a url at all",
        )) assertFalse("must NOT stay in webview: $u", SearchWebUrls.isSearchPage(u))
    }

    @Test
    fun `user agent keeps chrome identity but drops the webview marker`() {
        val phoneUa = "Mozilla/5.0 (Linux; Android 16; Pixel 10a Build/BP4A.251740.100; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.7339.207 Mobile Safari/537.36"
        val cleaned = SearchWebUrls.cleanUserAgent(phoneUa)
        assertFalse(cleaned.contains("; wv"))
        assertTrue(cleaned.contains("Pixel 10a Build/BP4A.251740.100) AppleWebKit"))
        assertTrue(cleaned.contains("Chrome/140.0.7339.207"))
        // UAs without the marker are untouched.
        assertEquals("Chrome/140", SearchWebUrls.cleanUserAgent("Chrome/140"))
    }

    @Test
    fun `reddit hosts recognized`() {
        assertTrue(SearchWebUrls.isRedditHost("www.reddit.com"))
        assertTrue(SearchWebUrls.isRedditHost("old.reddit.com"))
        assertTrue(SearchWebUrls.isRedditHost("reddit.com"))
        assertTrue(SearchWebUrls.isRedditHost("redd.it"))
        assertFalse(SearchWebUrls.isRedditHost("reddit.com.evil.example"))
        assertFalse(SearchWebUrls.isRedditHost("www.google.com"))
    }
}
