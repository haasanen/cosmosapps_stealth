package com.cosmos.unreddit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function pins for the browser-handoff search (2.5.110): the URL shapes
 * reddit itself serves, the handoff target picker that must never pin the
 * intent back into Stealth itself, and host classification for the return
 * path (result taps come back through the manifest reddit.com deep links).
 * Ground truth captured live 2026-11-25/27.
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
    fun `handoff pins the browser never stealth itself`() {
        // Default resolving to our own package (reddit.com link defaults) =>
        // null => plain implicit intent so the chooser appears, never a loop.
        assertNull(SearchWebUrls.pickHandoffPackage("com.cosmos.unreddit", "com.cosmos.unreddit"))
        // A real browser is kept as the explicit target.
        assertEquals(
            "org.mozilla.firefox",
            SearchWebUrls.pickHandoffPackage("org.mozilla.firefox", "com.cosmos.unreddit")
        )
        // Nothing resolves => null (caller starts the implicit intent and
        // handles ActivityNotFoundException with a visible toast).
        assertNull(SearchWebUrls.pickHandoffPackage(null, "com.cosmos.unreddit"))
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
