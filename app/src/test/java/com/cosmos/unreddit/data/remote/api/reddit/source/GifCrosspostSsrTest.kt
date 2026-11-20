package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * GIF posts that are CROSSPOSTED lose both signals the animated-card injection
 * relies on (live captures 2026-11-20, r/animation post 1y10mv1, a crosspost of
 * r/user_GroundbreakingDay317 gif post 1xzndhv):
 *  - the card's content-href is the TARGET post's permalink, so `url` no longer
 *    ends with .gif, and the card embeds NO i.redd.it <img>;
 *  - card AND player post-type is "crosspost", not "gif" — the web marks the
 *    animated nature with the boolean `gif=""` attribute on <shreddit-player>,
 *    whose src is a signed cf.preview.redd.it/<token>.gif?format=mp4 rendition
 *    (unsigned: 403; stable public origin twin: i.redd.it/<token>.gif, 200
 *    image/gif 36-frame animated, verified same session).
 * Before the fix the card fell through to the target permalink as `url`, media
 * injection never fired and the media viewer received an HTML page
 * ("doesn't open"), with a still poster preview — the exact user report.
 *
 * Contract pinned here: crosspost-of-gif cards derive the i.redd.it origin url
 * from the player path token, which re-arms the standard injection (signed mp4
 * fallback_url, is_gif via the player's gif attr, raw-gif thumbnail), matching
 * the shape the native (non-crosspost) card of the same post already produces.
 * v.redd.it video crossposts must keep the existing bare-base behaviour.
 */
class GifCrosspostSsrTest {

    private fun fixture(name: String): String {
        val s = javaClass.classLoader.getResourceAsStream("reddit_ssr/$name")
            ?: throw IllegalStateException("missing fixture $name")
        return s.bufferedReader().use { it.readText() }
    }

    private fun sourceFor(vararg fixtures: Pair<String, String>): RedditOfficialSource {
        val stub = Interceptor { chain ->
            val req = chain.request()
            val body = when {
                req.url.encodedPath.startsWith("/svc/") -> ""
                else -> fixtures.firstOrNull { (path, _) ->
                    req.url.encodedPath.startsWith(path)
                }?.let { (_, f) -> fixture(f) } ?: ""
            }
            Response.Builder()
                .request(req)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/html; charset=utf-8")
                .body(body.toResponseBody("text/html".toMediaTypeOrNull()))
                .build()
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        return RedditOfficialSource(client, NetworkModule.provideRedditMoshi(), Dispatchers.Default)
    }

    private val signedMp4 =
        "https://cf.preview.redd.it/49sr7t7d3f2i1.gif?width=1067&format=mp4&s=2a95c4984d97ceda83cf7763c1180ba42654bc03"

    @Test
    fun `crosspost gif feed card derives the i redd it origin url and plays like a native gif`() {
        val doc = org.jsoup.Jsoup.parse(fixture("gettin_dressed_xpost_feed.html"))
        val card = doc.selectFirst("shreddit-post[id=\"t3_1y10mv1\"]")
        assertNotNull("xpost card missing from feed fixture", card)
        val post = org.jsoup.Jsoup.parse("<html></html>").let { doc2 ->
            com.cosmos.unreddit.data.remote.api.reddit.source.RedditOfficialSource(
                OkHttpClient(), NetworkModule.provideRedditMoshi(), Dispatchers.Default
            ).parsePostCardForTest(card!!, doc)
        }
        assertNotNull("card did not parse", post)
        val p = post!!.data
        assertEquals(
            "crosspost-of-gif url must be the stable i.redd.it origin (got ${p.url})",
            "https://i.redd.it/49sr7t7d3f2i1.gif", p.url
        )
        assertEquals("crosspost-of-gif must classify as REDDIT_GIF (got ${p.mediaType})",
            MediaType.REDDIT_GIF, p.mediaType)
        assertEquals("mediaUrl must be the signed player mp4", signedMp4, p.mediaUrl)
        assertEquals("list preview must be the raw gif (Coil decodes 36 frames)",
            "https://i.redd.it/49sr7t7d3f2i1.gif", p.thumbnail)
        assertEquals("player gif attr must set is_gif", true, p.media?.redditVideoPreview?.isGif)
        assertEquals("is_video must be injected", true, p.isVideo)
    }

    @Test
    fun `v redd it video crosspost keeps the bare v redd it base`() {
        val doc = org.jsoup.Jsoup.parse(fixture("gettin_dressed_xpost_feed.html"))
        val card = doc.selectFirst("shreddit-post[id=\"t3_1y1c0fy\"]")
        assertNotNull("v.redd.it xpost card missing", card)
        val post = RedditOfficialSource(
            OkHttpClient(), NetworkModule.provideRedditMoshi(), Dispatchers.Default
        ).parsePostCardForTest(card!!, doc)
        val p = post!!.data
        assertTrue(
            "v.redd.it xpost must keep bare v.redd.it url (got ${p.url})",
            p.url.startsWith("https://v.redd.it/")
        )
        assertEquals(
            "v.redd.it xpost must not be reclassified as gif",
            MediaType.REDDIT_VIDEO, p.mediaType
        )
    }

    @Test
    fun `crosspost gif detail page resolves the same playable media`() = runBlocking {
        val source = sourceFor(
            "/r/animation/comments/1y10mv1/" to "gettin_dressed_xpost_p.html"
        )
        val listings = source.getPost("/r/animation/comments/1y10mv1/gettin_dressed/", null, Sort.HOT)
        val posts = listings[0].data.children.filterIsInstance<PostChild>().map { it.data }
        val op = posts.first { it.name == "t3_1y10mv1" }
        assertEquals("detail OP url must be the i.redd.it origin (got ${op.url})",
            "https://i.redd.it/49sr7t7d3f2i1.gif", op.url)
        assertEquals(MediaType.REDDIT_GIF, op.mediaType)
        assertEquals(signedMp4, op.mediaUrl)
    }

    @Test
    fun `native gif card of the same post keeps its working shape`() = runBlocking {
        val source = sourceFor(
            "/r/GroundbreakingDay317/comments/1xzndhv/" to "gettin_dressed_native_p.html"
        )
        val listings = source.getPost(
            "/r/GroundbreakingDay317/comments/1xzndhv/gettin_dressed/", null, Sort.HOT
        )
        val posts = listings[0].data.children.filterIsInstance<PostChild>().map { it.data }
        val op = posts.first { it.name == "t3_1xzndhv" }
        assertEquals("https://i.redd.it/49sr7t7d3f2i1.gif", op.url)
        assertEquals(MediaType.REDDIT_GIF, op.mediaType)
        assertEquals(signedMp4, op.mediaUrl)
        assertEquals(true, op.media?.redditVideoPreview?.isGif)
    }
}
