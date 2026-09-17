package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.di.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Diagnostic (2026-09-17 "nsfw preview blur removal setting doesn't work"):
 * drives the REAL parser against the NSFW capture and prints, for every
 * card reddit marks nsfw, the preview/blur-URL pair the app loads in the
 * HIDDEN vs SHOWN state — so the toggle's actual data is visible at the
 * byte level. Not an assertion test (the findings are pinned in
 * NsfwSpoilerHandlingTest / the frost-baked-poster tests); run it with
 * --tests ...NsfwPreviewDumpTest and read the system-out.
 */
class NsfwPreviewDumpTest {

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
                .body(loadFixture("nsfw_feed_hot.html").toResponseBody("text/html".toMediaTypeOrNull()))
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
    fun `dump preview and blur url per nsfw card`() {
        val src = buildSource()
        val doc = org.jsoup.Jsoup.parse(loadFixture("nsfw_feed_hot.html"))
        val cards = doc.select("shreddit-post").toList()
        for (card in cards) {
            val reason = card.select("shreddit-blurred-container").firstOrNull()?.attr("reason") ?: "-"
            val pc = src.parsePostCardForTest(card, doc) ?: continue
            val d = pc.data
            // previewUrl = mediaPreview.images[0] ?: gallery :mediaMetadata :url :
            // thumbnail. Its fallback branch touches android.webkit.MimeTypeMap
            // (not mocked in JVM tests), so print the constituents instead.
            val mp = d.mediaPreview?.images?.getOrNull(0)?.imageSource?.url
            val hidden = d.previewBlurUrl
            val host = { u: String? -> u?.substringAfter("://")?.substringBefore('/') ?: "-" }
            val hostShown = host(mp)
            val hostThumb = host(d.thumbnail)
            val hostHidden = host(hidden)
            println(
                "id=${d.name} nsfw=${d.isOver18} type=${d.postType.name} " +
                    "mediaType=${d.mediaType.name} frostBaked=${d.frostBakedPreview} " +
                    "mpHost=$hostShown mpBlur=${mp?.let { "blur=" in it } ?: false} " +
                    "thumbHost=$hostThumb thumbBlur=${d.thumbnail?.let { "blur=" in it } ?: false} " +
                    "hiddenHost=$hostHidden domain=${d.domain}"
            )
        }
    }
}
