package com.cosmos.unreddit.util

/**
 * URL building/classification for the embedded reddit.com web search UI.
 *
 * Background (live-captured 2026-11-25): reddit.com search became JS-only.
 * The server response for `/search/` and `/r/<sub>/search/` contains ZERO
 * result cards (`<shreddit-post` count 0 — cards render client-side from a
 * captcha-gated data call; a reCAPTCHA Enterprise iframe sits on the page),
 * the JS-challenge resubmit is answered with the 403 "blocked by network
 * security" page for non-JS clients, and XHR-shaped requests 403 outright.
 * A real browser engine passes (the owner's Chrome proves it daily), so the
 * app renders those pages in a WebView and routes result taps natively.
 *
 * Pure functions — JVM-tested in SearchWebUrlsTest.
 */
object SearchWebUrls {

    private const val SITE = "https://www.reddit.com"

    /** Global search page URL (reddit's own web UI). */
    fun search(query: String): String = "$SITE/search/?q=${encode(query)}"

    /** Subreddit-scoped search page URL (reddit's own web UI). */
    fun inSubreddit(subreddit: String, query: String): String {
        val name = subreddit.trim().trim('/').removePrefix("r/").trim('/')
        return "$SITE/r/$name/search/?q=${encode(query)}&restrict_sr=1"
    }

    /**
     * True when [url] is a reddit SEARCH page (global or subreddit-scoped, on
     * any reddit web host) and must keep loading inside the WebView instead of
     * being handed to the native screens — this covers the in-page tab switches
     * (All/Posts/Communities), sort and time filters, and pagination, which all
     * keep `/search/` in the path.
     */
    fun isSearchPage(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (!isRedditHost(host)) return false
        val path = uri.path ?: return false
        return SEARCH_PATH.matches(path)
    }

    fun isRedditHost(host: String): Boolean =
        host == "reddit.com" || host.endsWith(".reddit.com") ||
            host == "redd.it" || host.endsWith(".redd.it")

    /**
     * Reddit's network gate treats the WebView's "; wv" marker as a generic
     * embedded client; removing only that marker keeps the engine's real
     * Chrome identity. (Measured: the same page 403s a "client-ish" UA and
     * loads for an honest Chrome UA.)
     */
    fun cleanUserAgent(userAgent: String): String = userAgent.replace("; wv)", ")")

    private val SEARCH_PATH = Regex("""/search(/.*)?|/r/[^/]+/search(/.*)?""")

    private fun encode(query: String): String =
        java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}
