package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.Interceptor
import okhttp3.Response

/**
 * reddit.com serves the RSS feeds to real browser user agents; a default client
 * agent is served a challenge/block page. Verified 2026-08-29 with a mobile
 * Chrome UA (and that the same UA is required from datacenter IPs to get
 * through).
 */
class RedditRssUserAgentInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .build()
        )
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8a) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
