package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Real cookie store for the "Reddit (official)" SSR client.
 *
 * The previous implementation was a no-op stub: it always returned a single hard-coded
 * `over18=1` cookie and discarded every cookie the server sent (`saveFromResponse` was
 * `// ignore`). That silently broke the entire official-source flow, because reddit.com
 * answers a brand-new session's first request with a ~8 KB JS-challenge page, and only
 * *after* that challenge is solved does it issue the session cookies that make every
 * follow-up request return real HTML instead of another challenge page:
 *
 *   - `token_v2`      (the solved-challenge session token)
 *   - `loid`          (login/identification id)
 *   - `csrf_token`    (anti-CSRF token)
 *   - `session_tracker`
 *
 * If those cookies are thrown away between requests, the app re-solves the challenge on
 * every single call (and the signed "load more" continuation partials are bound to the
 * solved session, so they 401/403 once the session cookie is lost) — which is why the
 * on-device build showed an empty black home feed.
 *
 * This jar now persists server cookies in memory, keyed by host, for the lifetime of the
 * process, and re-sends the `over18=1` consent cookie on every request (reddit.com would
 * otherwise answer an age-verification interstitial). Memory-only is deliberate: it needs
 * no disk I/O, no security-exempted storage, and a re-solve on cold start is cheap.
 */
class RedditCookieJar : CookieJar {

    // Host (including port) -> mutable list of cookies that host set for us.
    private val byHost = HashMap<String, MutableList<Cookie>>()
    private val lock = Any()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val hostCookies = synchronized(lock) {
            byHost[url.host]?.toList() ?: emptyList()
        }
        // Always also present the age-consent cookie so the content gate never blocks.
        return if (hostCookies.any { it.name == OVER_18_NAME }) {
            hostCookies
        } else {
            hostCookies + OVER_18
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            val list = byHost.getOrPut(url.host) { mutableListOf() }
            val names = cookies.map { it.name }.toSet()
            // Drop the host's stored cookies that this response replaces (same name),
            // then append the fresh values. Keeps the jar current without unbounded growth.
            list.removeAll { it.name in names }
            list.addAll(cookies)
        }
    }

    companion object {
        private const val OVER_18_NAME = "over18"
        private val OVER_18 = Cookie.Builder()
            .name(OVER_18_NAME)
            .value("1")
            .domain("reddit.com")
            .path("/")
            .secure()
            .build()
    }
}
