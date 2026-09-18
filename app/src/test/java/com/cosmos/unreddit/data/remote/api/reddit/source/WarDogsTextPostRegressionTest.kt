package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression (2026-09-18 "blank spot and tiny square" report, r/linux_gaming
 * "War Dogs" post): the post is a PURE TEXT post (no image, video, or
 * gallery). The app's detail header must render it as text — the 250dp media
 * container stays hidden and the type-indicator chip is GONE. This test pins
 * the DATA the decision rests on: the parser must classify the captured card
 * as TEXT / NO_MEDIA. The header layout that acts on it:
 * item_post_header.xml (image_post_container, hidden for PostType.TEXT in
 * PostAdapter.bind) and applyTypeIndicator (GONE for everything but
 * gallery/video/link).
 *
 * Fixture: live detail page capture (2026-09-18) with the app's own
 * challenge-solve flow.
 */
class WarDogsTextPostRegressionTest {

    private lateinit var source: RedditOfficialSource

    private fun loadFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("reddit_ssr/$name")!!
            .bufferedReader().use { it.readText() }

    private fun buildSource(): RedditOfficialSource {
        val stub = Interceptor { chain ->
            val req = chain.request()
            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(loadFixture("text_post_detail_war_dogs.html").toResponseBody("text/html".toMediaTypeOrNull()))
                .build()
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        val moshi = NetworkModule.provideRedditMoshi()
        return RedditOfficialSource(client, moshi, Dispatchers.Default)
    }

    @Test
    fun `war dogs detail post parses as a pure text post with no media`() {
        val src = buildSource()
        val doc = org.jsoup.Jsoup.parse(loadFixture("text_post_detail_war_dogs.html"))
        val card = doc.select("shreddit-post").first { it.attr("id") == "t3_1wir8u4" }
        assertEquals("text", card.attr("post-type"))

        val d = src.parsePostCardForTest(card, doc)!!.data
        assertEquals(PostType.TEXT, d.postType)
        assertEquals(MediaType.NO_MEDIA, d.mediaType)
        assertTrue(d.isSelf)
        // A text post must never carry the frost-baked flag (its preview is
        // null; the flag only exists on external video cards).
        assertFalse(d.frostBakedPreview)
        // The selftext survived the parse (the body renders under the title).
        assertTrue(d.selfTextHtml.orEmpty().length > 0)
    }
}
