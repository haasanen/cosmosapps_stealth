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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pins the video-detail DOM contract from a live capture (2026-11-16, r/Unity3D
 * post 1xxm60o "Made a Wet Lens Effect", captured via the UA-override browser):
 * the detail page carries a post-type=video card whose media fields are ALL null
 * (no media / mediaPreview / mediaMetadata) and whose url is the bare
 * `https://v.redd.it/<id>` base. The playable URL therefore MUST come from the
 * unsigned HLS derivation (mediaUrl = .../HLSPlaylist.m3u8?f=hd) — the bare base
 * 302-redirects to an HTML page that no player can open. Comments (25 in the
 * capture) must parse from the same DOM.
 */
class VideoDetailNullMediaHlsTest {

    private lateinit var source: RedditOfficialSource

    private fun fixture(name: String): String {
        val s = javaClass.classLoader.getResourceAsStream("reddit_ssr/$name")
            ?: throw IllegalStateException("missing fixture $name")
        return s.bufferedReader().use { it.readText() }
    }

    @Before
    fun buildSource() {
        val stub = Interceptor { chain ->
            val req = chain.request()
            val body = when {
                req.url.encodedPath.startsWith("/svc/") -> ""
                else -> fixture("wetlens_p.html")
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
        source = RedditOfficialSource(client, NetworkModule.provideRedditMoshi(), Dispatchers.Default)
    }

    @Test
    fun `video post with all-null media fields derives its mediaUrl from the bare v redd it base`() = runBlocking {
        val listings = source.getPost(
            "/r/Unity3D/comments/1xxm60o/made_a_wet_lens_effect_for_my_water_simulation/",
            null,
            Sort.HOT
        )
        assertTrue("expected 2 listings", listings.size == 2)
        val posts = listings[0].data.children.filterIsInstance<PostChild>()
        assertTrue("no post parsed", posts.isNotEmpty())
        val p = posts.first().data

        // The DOM shape this test pins: bare base url, all media fields null.
        assertEquals("https://v.redd.it/i2no06sa8y1i1", p.url)
        assertEquals(null, p.media)
        assertEquals(null, p.mediaPreview)
        assertEquals(null, p.mediaMetadata)

        assertEquals(MediaType.REDDIT_VIDEO, p.mediaType)
        assertEquals(
            "https://v.redd.it/i2no06sa8y1i1/HLSPlaylist.m3u8?f=hd",
            p.mediaUrl
        )

        // Comments parse from the same DOM (capture has 25 real shreddit-comment cards).
        assertTrue(
            "expected comments to parse",
            listings[1].data.children.isNotEmpty()
        )
    }
}
