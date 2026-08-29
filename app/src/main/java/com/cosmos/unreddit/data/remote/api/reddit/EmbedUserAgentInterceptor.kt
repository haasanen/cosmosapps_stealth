package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.Interceptor
import okhttp3.Response

/**
 * embed.reddit.com answers a real browser user-agent (verified live 2026-08-29), but
 * bot-looking clients get challenged. Mirrors [RedditRssUserAgentInterceptor], with an
 * HTML Accept header (the embed pages are HTML, not Atom).
 */
class EmbedUserAgentInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html, application/xhtml+xml, */*;q=0.8")
            .build()
        return chain.proceed(request)
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8a) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
