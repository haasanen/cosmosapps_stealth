package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.ui.postlist.PostViewHolder
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
 * 2026-09-17 report: "the video preview setting doesn't work."
 *
 * The gate that decides which feed cells autoplay
 * ([PostViewHolder.VideoPostViewHolder.canAutoplay]) originally accepted only
 * a `v.redd.it` host. A plain native video card resolves to a `v.redd.it`
 * HLS playlist (passes), but an ANIMATED (GIF) card resolves its playable
 * rendition to the signed `preview.redd.it` / `cf.preview.redd.it`
 * `?format=mp4` URL the card's <shreddit-player> carries — a different host,
 * so GIFs silently never autoplay and the toggle "does nothing."
 *
 * These tests drive the REAL official source against a live capture that
 * contains both a GIF card and a native video card, and pin that both pass
 * the autoplay gate.
 */
class OfficialGifAutoplayTest {

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
                .body(loadFixture("media_gif_p1.html").toResponseBody("text/html".toMediaTypeOrNull()))
                .build()
        }
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(stub)
            .build()
        val moshi = NetworkModule.provideRedditMoshi()
        source = RedditOfficialSource(client, moshi, Dispatchers.Default)
    }

    /** Build a minimal PostEntity from the parsed media fields only. */
    private fun entityOf(id: String, mediaType: MediaType, mediaUrl: String): PostEntity =
        PostEntity(
            id = id,
            subreddit = "test",
            title = "t",
            ratio = 100,
            totalAwards = 0,
            isOC = false,
            score = "1",
            type = if (mediaType == MediaType.IMAGE) PostType.IMAGE else PostType.VIDEO,
            domain = mediaUrl.substringAfter("://").substringBefore('/'),
            isSelf = false,
            selfTextHtml = null,
            suggestedSorting = Sorting(Sort.HOT),
            isOver18 = false,
            preview = null,
            isSpoiler = false,
            isArchived = false,
            isLocked = false,
            posterType = PosterType.REGULAR,
            author = "a",
            commentsNumber = "0",
            permalink = "/r/test/comments/1",
            isStickied = false,
            url = mediaUrl,
            created = 0L,
            mediaType = mediaType,
            mediaUrl = mediaUrl
        )

    @Test
    fun `captured gif card parses to a playable mp4 rendition and passes the gate`() = runBlocking {
        val doc = org.jsoup.Jsoup.parse(loadFixture("media_gif_p1.html"))
        // The GIF card's <shreddit-player> carries the signed playable URL.
        val gifCard = doc.select("shreddit-post")
            .firstOrNull { it.attr("post-type") == "gif" }
            ?: error("no gif card in the capture")
        val post = source.parsePostCardForTest(gifCard)
        assertNotNull("gif card did not parse", post)

        val data = post!!.data
        assertEquals("gif card must classify as REDDIT_GIF", MediaType.REDDIT_GIF, data.mediaType)

        val url = data.mediaUrl
        // The playable rendition must be a native reddit MP4 (v.redd.it HLS, or the
        // signed ?format=mp4 on preview/cf.preview.redd.it) — never the raw .gif.
        val host = url.substringAfter("://").substringBefore('/')
        val isNativeMp4 =
            host == "v.redd.it" ||
            (host in setOf("preview.redd.it", "cf.preview.redd.it") && "format=mp4" in url)
        assertTrue(
            "gif mediaUrl is not a playable native reddit MP4 rendition: $url",
            isNativeMp4
        )
        assertFalse("gif mediaUrl still the raw .gif (unplayable): $url", url.endsWith(".gif"))

        // The end-to-end assertion: the parsed post passes the autoplay gate.
        val entity = entityOf(data.name, data.mediaType, data.mediaUrl)
        assertTrue(
            "captured gif post must autoplay (the reported bug)",
            PostViewHolder.VideoPostViewHolder.canAutoplay(entity, true, true)
        )
    }

    @Test
    fun `captured native video card still passes the gate`() = runBlocking {
        val doc = org.jsoup.Jsoup.parse(loadFixture("media_gif_p1.html"))
        val videoCard = doc.select("shreddit-post")
            .firstOrNull { it.attr("post-type") == "video" }
            ?: error("no video card in the capture")
        val post = source.parsePostCardForTest(videoCard)
        assertNotNull("video card did not parse", post)

        val data = post!!.data
        assertEquals(
            "video card must classify as REDDIT_VIDEO",
            MediaType.REDDIT_VIDEO,
            data.mediaType
        )
        val entity = entityOf(data.name, data.mediaType, data.mediaUrl)
        assertTrue(
            "captured native video post must autoplay",
            PostViewHolder.VideoPostViewHolder.canAutoplay(entity, true, true)
        )
    }
}
