package com.cosmos.unreddit.data.remote.api.reddit

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Arctic Shift (like most public APIs) may block the default `okhttp/x.y.z` user agent.
 * Send a plain browser-like one instead.
 */
class ArcticUserAgentInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            .build()
        return chain.proceed(request)
    }
}
