package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.api.reddit.RedditCookieJar
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * LIVE diagnostic — not part of the CI suite (uses the network).
 *
 * Runs the app's exact client stack (same OkHttpClient build as NetworkModule,
 * the same RedditCookieJar, the real RedditOfficialSource) against the live
 * reddit.com home feed and prints what happens at every step.
 */
class RedditOfficialDiagnostic {

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(60L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .cookieJar(RedditCookieJar())
        .build()

    @Test
    fun liveHomeFeed() {
        runBlocking {
            val source = RedditOfficialSource(client(), NetworkModule.provideRedditMoshi(), Dispatchers.IO)
            val t0 = System.currentTimeMillis()
            try {
                val listing = source.getSubreddit("popular", Sort.HOT, null, null)
                println("LIVE OK: ${listing.data.children.size} posts in ${System.currentTimeMillis() - t0}ms")
                println("LIVE first 5: " + listing.data.children.take(5)
                    .map { (it as? PostChild)?.data?.title ?: "?" }.joinToString())
            } catch (t: Throwable) {
                println("LIVE FAIL after ${System.currentTimeMillis() - t0}ms: ${t::class.java.simpleName}: ${t.message}")
                t.cause?.let { println("LIVE cause: ${it::class.java.simpleName}: ${it.message}") }
            }
        }
    }
}
