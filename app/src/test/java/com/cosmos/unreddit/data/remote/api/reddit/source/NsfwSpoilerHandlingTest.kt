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
    fun `flagged cards get the sharp original preview not the CDN blurred rendition`() = runBlocking {
        // 2026-09-14: the app's "Show NSFW preview" toggle only removes the
        // app-side Gaussian blur. For flagged feed cards the loaded preview was
        // ALSO reddit's server-side blurred rendition
        // (preview.redd.it/<id>?blur=40), so the toggle visibly did nothing —
        // "especially on some videos". The parser must rewrite those to the
        // sharp i.redd.it original (same file id, anonymously served).
        val doc = org.jsoup.Jsoup.parse(loadFixture("nsfw_feed_hot.html"))
        val cards = doc.select("shreddit-post").toList()
        val flagged = cards.filter {
            it.select("shreddit-blurred-container").firstOrNull()?.attr("reason") == "nsfw" ||
                it.hasAttr("nsfw")
        }
        var rewritten = 0
        for (card in flagged) {
            val post = source.parsePostCardForTest(card) ?: continue
            val thumb = post.data.thumbnail
            // External embeds (redgifs/YouTube) have opaque external-preview
            // ids with no i.redd.it twin — left untouched, asserted separately.
            if (thumb == null || "external-preview.redd.it" in thumb) continue
            // Any in-card preview.redd.it rendition MUST NOT carry the CDN blur.
            assertFalse(
                "card ${card.attr("id")} preview is still the CDN-blurred rendition: $thumb",
                thumb.contains("blur=")
            )
            if (thumb.startsWith("https://i.redd.it/")) rewritten++
        }
        assertTrue(
            "expected at least one card rewritten to the sharp i.redd.it original, " +
                "but none were (the feed would still load ?blur=40 renditions)",
            rewritten > 0
        )
    }

    @Test
    fun `external video posters stay on the CDN rendition`() = runBlocking {
        // 2026-09-14 "especially on some videos": NSFW video (redgifs/YouTube)
        // cards carry their poster on external-preview.redd.it with the CDN
        // blur BAKED INTO THE SIGNED URL (?blur=40). Verified: dropping the
        // param 403s (signature mismatch), the token is opaque (no i.redd.it
        // twin), and no other sharp rendition exists on reddit's CDNs. The
        // parser must NOT invent an i.redd.it path for these — the poster stays
        // on the CDN rendition (the app-side Gaussian still toggles over it,
        // and tapping plays the full-quality video).
        val doc = org.jsoup.Jsoup.parse(loadFixture("nsfw_feed_hot.html"))
        val cards = doc.select("shreddit-post").toList()
        var externalPosters = 0
        for (card in cards) {
            val post = source.parsePostCardForTest(card) ?: continue
            val thumb = post.data.thumbnail ?: continue
            if ("external-preview.redd.it" !in thumb) continue
            externalPosters++
            assertTrue(
                "external-site poster was rewritten to a bogus path: $thumb",
                thumb.startsWith("https://external-preview.redd.it/")
            )
        }
        assertTrue("expected external-site posters in the capture", externalPosters > 0)
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

    @Test
    fun `spoiler video poster swaps to the sharp player rendition`() = runBlocking {
        // 2026-09-14 report, the r/outerwilds post t3_1wfq2yl: a SPOILER video
        // whose card background is external-preview.redd.it/<sig>.jpeg?blur=40
        // (frosted, 30KB; the param is bound to the signature, re-fetch 403s) —
        // so the app's blur toggle visibly did nothing. But the same image has
        // a SHARP signed rendition: the <shreddit-player>'s poster attr
        // (external-preview.redd.it/<slug>-v0-<sig>.jpeg, 200 sharp). The parser
        // must swap to it (token suffix match), and leave external-embed cards
        // (different poster image) on their CDN rendition.
        val doc = org.jsoup.Jsoup.parse(loadFixture("spoiler_video_detail.html"))
        val card = doc.select("shreddit-post").firstOrNull { it.attr("id") == "t3_1wfq2yl" }
            ?: error("t3_1wfq2yl card missing from the capture")
        val post = source.parsePostCardForTest(card, doc)
        assertNotNull(post)
        assertTrue("spoiler flag lost on t3_1wfq2yl", post!!.data.isSpoiler)
        val thumb = post.data.thumbnail
        assertNotNull("t3_1wfq2yl has no thumbnail", thumb)
        assertFalse(
            "t3_1wfq2yl poster still the CDN-frosted rendition (the reported bug): $thumb",
            thumb!!.contains("blur=")
        )
        assertTrue(
            "t3_1wfq2yl poster should be the player's sharp rendition, got: $thumb",
            thumb.contains("potentially-dumb-question-v0-")
        )
    }
}
