package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression for the 2026-09-14 report: "the blur setting in the menu should
 * toggle blur off or on for either nsfw or spoiler content."
 *
 * The official SSR source reads the NSFW/spoiler flags from the card's
 * `shreddit-blurred-container` element (reddit's own per-card blur marker,
 * `reason`="nsfw"/"spoiler"), with the lightbox telemetry JSON as a fallback.
 * These tests drive the REAL parser against live captures of an NSFW feed and a
 * spoiler feed so the flags the blur toggle depends on are pinned.
 */
class NsfwSpoilerHandlingTest {

    private lateinit var source: RedditOfficialSource

    private fun loadFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("reddit_ssr/$name")!!
            .bufferedReader().use { it.readText() }

    @Before
    fun buildSource() {
        val stub = Interceptor { chain ->
            val req = chain.request()
            okhttp3.Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(loadFixture("nsfw_feed_hot.html").toResponseBody("text/html".toMediaTypeOrNull()))
                .build()
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        val moshi = NetworkModule.provideRedditMoshi()
        source = RedditOfficialSource(client, moshi, Dispatchers.Default)
    }

    @Test
    fun `every card reddit marks nsfw parses with isOver18 true`() = runBlocking {
        val doc = org.jsoup.Jsoup.parse(loadFixture("nsfw_feed_hot.html"))
        val cards = doc.select("shreddit-post").toList()
        val flagged = cards.filter {
            it.select("shreddit-blurred-container").firstOrNull()?.attr("reason") == "nsfw"
        }
        assertTrue("expected NSFW-flagged cards in the capture", flagged.isNotEmpty())
        for (card in flagged) {
            val post = source.parsePostCardForTest(card)
            assertNotNull("card ${card.attr("id")} did not parse", post)
            assertTrue(
                "card ${card.attr("id")} is marked nsfw by reddit but isOver18=false " +
                    "(the NSFW blur toggle could never engage for it)",
                post!!.data.isOver18
            )
        }
    }

    @Test
    fun `the card reddit marks spoiler parses with isSpoiler true`() = runBlocking {
        val doc = org.jsoup.Jsoup.parse(loadFixture("spoiler_feed_hot.html"))
        val cards = doc.select("shreddit-post").toList()
        val flagged = cards.filter {
            it.select("shreddit-blurred-container").firstOrNull()?.attr("reason") == "spoiler"
        }
        assertTrue("expected a spoiler-flagged card in the capture", flagged.isNotEmpty())
        for (card in flagged) {
            val post = source.parsePostCardForTest(card)
            assertNotNull("card ${card.attr("id")} did not parse", post)
            assertTrue(
                "card ${card.attr("id")} is marked spoiler by reddit but isSpoiler=false " +
                    "(the spoiler blur toggle could never engage for it)",
                post!!.data.isSpoiler
            )
        }
    }

    @Test
    fun `non-flagged cards stay unflagged`() = runBlocking {
        // Cards with NO blurred-container and NO nsfw telemetry must not be
        // spuriously flagged (regression: don't over-apply the flag).
        val doc = org.jsoup.Jsoup.parse(loadFixture("pf_popular_p1.html"))
        val clean = doc.select("shreddit-post").toList().filter {
            it.select("shreddit-blurred-container").isEmpty() &&
                it.selectFirst("shreddit-media-lightbox-listener") == null
        }
        for (card in clean) {
            val post = source.parsePostCardForTest(card) ?: continue
            assertFalse("card ${card.attr("id")} not flagged but isOver18=true", post.data.isOver18)
            assertFalse("card ${card.attr("id")} not flagged but isSpoiler=true", post.data.isSpoiler)
        }
    }
}
