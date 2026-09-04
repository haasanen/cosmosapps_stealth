package com.cosmos.unreddit.data.remote.api.reddit.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Regression test for the 2026-09-02 device crash:
 *
 *   Flow invariant is violated: Emission from another coroutine is detected.
 *   Child of l0{Active}@4b7c933, expected child of z1{Active}@20064f0.
 *   FlowCollector is not thread-safe and concurrent emissions are prohibited.
 *   To mitigate this restriction please use 'channelFlow' builder instead of 'flow'
 *
 * Root cause: getSubredditFanOutProgressive used `flow { }` while several
 * concurrent async workers called `emit(...)` in parallel. `flow`'s emit is
 * bound to the outer collector and is not thread-safe across sibling
 * coroutines; `channelFlow`'s `channel.send` is.
 *
 * The test runs the REAL progressive fan-out over an OkHttpClient stub that
 * answers every request with 404. The 404 path is exactly the one that
 * exercises the concurrency: every sub "finishes" (lenient → empty list) and,
 * when streaming, sends a snapshot. With the old `flow {}` implementation the
 * flow throws IllegalStateException("Flow invariant is violated…"); with
 * `channelFlow` it completes with `subs.size + 1` emissions (one per finished
 * sub while streaming + the final one).
 *
 * 12 subs is enough: with FANOUT_CONCURRENCY=4 and FANOUT_JITTER_MS=700 there
 * are many overlapping worker completions, which reliably triggers the
 * invariant check under the old implementation.
 */
class RedditOfficialFanOutConcurrencyTest {

    private fun stubClientAlways404(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("stub 404")
                .body("no posts here".toResponseBody(null))
                .build()
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** Same adapter set as the DI module's provideRedditMoshi (NetworkModule.kt):
     *  PostData's generated adapter resolves RichText, GalleryData, etc. through it. */
    private fun testMoshi(): com.squareup.moshi.Moshi =
        com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
                .of(
                    com.cosmos.unreddit.data.remote.api.reddit.model.Child::class.java,
                    "kind"
                )
                .withSubtype(com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild::class.java, "t1")
                .withSubtype(com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild::class.java, "t2")
                .withSubtype(com.cosmos.unreddit.data.remote.api.reddit.model.PostChild::class.java, "t3")
                .withSubtype(com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild::class.java, "t5")
                .withSubtype(com.cosmos.unreddit.data.remote.api.reddit.model.MoreChild::class.java, "more"))
            .add(com.cosmos.unreddit.data.remote.api.reddit.adapter.MediaMetadataAdapter.Factory)
            .add(com.cosmos.unreddit.data.remote.api.reddit.adapter.RepliesAdapter())
            .add(com.cosmos.unreddit.data.remote.api.reddit.adapter.EditedAdapter())
            .add(com.cosmos.unreddit.data.remote.api.reddit.adapter.NullToEmptyStringAdapter())
            .build()

    private val testSubs: String =
        List(12) { "stub_sub_$it" }.joinToString("+")

    @Test
    fun streamingFanOutWithConcurrentWorkersCompletesWithoutInvariantViolation() =
        runBlocking {
            withTimeout(30_000) {
                val source = RedditOfficialSource(
                    okHttpClient = stubClientAlways404(),
                    moshi = testMoshi(),
                    ioDispatcher = Dispatchers.IO
                )

                val emissions = source.getSubredditFanOutProgressive(
                    multiredd = testSubs,
                    sort = com.cosmos.unreddit.data.model.Sort.HOT,
                    timeSorting = null,
                    after = null,
                    stream = true
                ).toList()

                // 12 subs + 1 final emission = 13
                assertEquals("expected subs.size + 1 emissions (one per finished sub while streaming + final)",
                    13, emissions.size)

                // The last emission must be the final one, and it must carry the
                // done/total progress accounting correctly.
                val last = emissions.last()
                assertTrue("last emission must be the final one", last.isFinal)
                assertEquals("progress.total should equal number of subs",
                    12, last.progress.total)
                assertEquals("progress.done should equal number of subs on final emit",
                    12, last.progress.done)

                // perSub is aligned to the ORIGINAL sub order and keeps every
                // finished sub's (possibly empty) list — mapNotNull only drops
                // nulls, not empty lists. So 12 entries, each empty (404 → lenient → []).
                assertEquals(12, last.perSub.size)
                assertTrue("every per-sub list should be empty (404s → lenient → no posts)",
                    last.perSub.all { it.isEmpty() })
                assertEquals(12, last.cursors.size)
                assertTrue("all cursors should be null (no data on 404s)",
                    last.cursors.values.all { it == null })
            }
        }

    /**
     * The non-streaming variant must produce exactly one final emission and
     * still not hit the invariant — it's the same channelFlow, just no
     * intermediate sends. Guards against a future refactor that re-adds
     * `emit` in a worker.
     */
    @Test
    fun nonStreamingFanOutProducesExactlyOneFinalEmission() =
        runBlocking {
            withTimeout(30_000) {
                val source = RedditOfficialSource(
                    okHttpClient = stubClientAlways404(),
                    moshi = testMoshi(),
                    ioDispatcher = Dispatchers.IO
                )

                val emissions = source.getSubredditFanOutProgressive(
                    multiredd = testSubs,
                    sort = com.cosmos.unreddit.data.model.Sort.HOT,
                    timeSorting = null,
                    after = null,
                    stream = false
                ).toList()

                assertEquals("non-streaming must produce exactly one emission",
                    1, emissions.size)
                assertTrue("it must be the final one", emissions.single().isFinal)
            }
        }

    /**
     * A confirmed Cloudflare block page (the content-based "blocked by network
     * security" page from the 2026-09-03 device log) must surface as an
     * actionable [RedditOfficialSource.FeedBlockedException] — NOT a silent
     * switch to the Atom endpoint, and NOT a plain empty feed. The streaming
     * path throws it after the final snapshot; the non-streaming multiredd
     * path throws it instead of returning an empty listing.
     */
    @Test
    fun streamingFanOutWithConfirmedCfBlockThrowsFeedBlocked() {
        val source = RedditOfficialSource(
            okHttpClient = stubClientCfBlock(),
            moshi = testMoshi(),
            ioDispatcher = Dispatchers.IO
        )
        assertThrows(RedditOfficialSource.FeedBlockedException::class.java) {
            runBlocking {
                withTimeout(60_000) {
                    source.getSubredditFanOutProgressive(
                        multiredd = testSubs,
                        sort = com.cosmos.unreddit.data.model.Sort.HOT,
                        timeSorting = null,
                        after = null,
                        stream = true
                    ).toList()
                }
            }
        }
    }

    @Test
    fun nonStreamingMultireddCfBlockThrowsFeedBlocked() {
        val source = RedditOfficialSource(
            okHttpClient = stubClientCfBlock(),
            moshi = testMoshi(),
            ioDispatcher = Dispatchers.IO
        )
        assertThrows(RedditOfficialSource.FeedBlockedException::class.java) {
            runBlocking {
                withTimeout(60_000) {
                    source.getSubreddit(
                        testSubs,
                        com.cosmos.unreddit.data.model.Sort.HOT,
                        null,
                        null
                    )
                }
            }
        }
    }

    /**
     * A transient failure that is NOT a CF block page (a bare 404) must still end
     * the cycle as an empty feed — no FeedBlockedException. This is the regression
     * guard that the hard-block error only fires on a *confirmed* block page, not on
     * any zero-post result (a genuinely empty subreddit must not report "blocked").
     */
    @Test
    fun streamingFanOutWithTransient404DoesNotThrowFeedBlocked() {
        val source = RedditOfficialSource(
            okHttpClient = stubClientAlways404(),
            moshi = testMoshi(),
            ioDispatcher = Dispatchers.IO
        )
        runBlocking {
            withTimeout(30_000) {
                val emissions = source.getSubredditFanOutProgressive(
                    multiredd = "a+b+c",
                    sort = com.cosmos.unreddit.data.model.Sort.HOT,
                    timeSorting = null,
                    after = null,
                    stream = true
                ).toList()
                // Completes with a final empty snapshot; no exception.
                assertTrue("must end with the final snapshot", emissions.last().isFinal)
                assertTrue("final per-sub lists must all be empty (404s -> lenient)",
                    emissions.last().perSub.all { it.isEmpty() })
            }
        }
    }

    /**
     * Regression for the 2026-09-03 device failure: reddit.com answered all 73 subs with
     * 200 OK pages of ~8.4 KB (uniform size, a per-sub nonce) that parse to ZERO post
     * cards and match NO Cloudflare marker ("just a moment", "cf-chl", "blocked by
     * network security", …). fetchSubPostsLenient used to treat each as a merely empty
     * subreddit, so the whole cycle ended as a silent blank feed (masked at the time by
     * a separate concurrency crash). A real feed page — even a genuinely EMPTY
     * subreddit — is a full 30 KB+ SSR document, so a small zero-card page is reported
     * as a confirmed block: this test stubs exactly that shape and expects the
     * actionable [RedditOfficialSource.FeedBlockedException], and the early-abort keeps
     * it fast.
     */
    @Test
    fun streamingFanOutWithSmallMarkerlessBlockPagesThrowsFeedBlocked() {
        val source = RedditOfficialSource(
            okHttpClient = stubClientSmallBlockPages(),
            moshi = testMoshi(),
            ioDispatcher = Dispatchers.IO
        )
        assertThrows(RedditOfficialSource.FeedBlockedException::class.java) {
            runBlocking {
                withTimeout(60_000) {
                    source.getSubredditFanOutProgressive(
                        multiredd = testSubs,
                        sort = com.cosmos.unreddit.data.model.Sort.HOT,
                        timeSorting = null,
                        after = null,
                        stream = true
                    ).toList()
                }
            }
        }
    }

    /**
     * OkHttp stub answering 200 with a small (~8.4 KB) page that carries no post cards
     * and no recognizable Cloudflare marker — the exact shape the device saw on
     * 2026-09-03 (73 x ~8,410 bytes, body=8409..8418 in the log).
     */
    private fun stubClientSmallBlockPages(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val nonce = java.util.UUID.randomUUID().toString()
            val blockPage =
                "<html><head><title>Some interstitial</title></head>" +
                    "<body><!-- $nonce -->" +
                    " ".repeat(8400) +
                    "</body></html>"
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(blockPage.toResponseBody(null))
                .build()
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** OkHttp stub that answers every request with a Cloudflare block page. */
    private fun stubClientCfBlock(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val blockPage =
                "<html><head><title>Blocked</title></head>" +
                    "<body>blocked by network security</body></html>"
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(blockPage.toResponseBody(null))
                .build()
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Sanity check: the streaming variant with a single sub must produce two
     * emissions (the per-sub snapshot when it finishes + the final one). This
     * guards against a regression where `channel.send(true)` is skipped.
     */
    @Test
    fun singleSubStreamingProducesTwoEmissions() =
        runBlocking {
            withTimeout(30_000) {
                val source = RedditOfficialSource(
                    okHttpClient = stubClientAlways404(),
                    moshi = testMoshi(),
                    ioDispatcher = Dispatchers.IO
                )

                val emissions = source.getSubredditFanOutProgressive(
                    multiredd = "lonesub",
                    sort = com.cosmos.unreddit.data.model.Sort.HOT,
                    timeSorting = null,
                    after = null,
                    stream = true
                ).toList()

                assertEquals("single sub streaming: one per-sub snapshot + one final",
                    2, emissions.size)
                assertFalse("first emission must not be final", emissions[0].isFinal)
                assertTrue("second emission must be final", emissions[1].isFinal)
            }
        }

    // ── ISSUE A: failed subs vs confirmed-empty subs must be distinguishable ─────────
    // The home feed keeps a failed sub's cached posts but drops a confirmed-empty
    // sub's. That decision is driven ENTIRELY by FanOutPage.failedSubs, so the fan-out
    // itself must populate it precisely: a transient failure (5xx) is FAILED, a 200
    // page that parses to zero posts is CONFIRMED EMPTY, a normal page is a success.

    /** A 200 page with ZERO post cards but a realistic size (> 30 KB): a "quiet but
     *  reachable" feed, not a block/interstitial variant. */
    private fun emptyFeedPage(): String =
        "<html><head><title>Quiet subreddit</title></head>" +
            "<body>" + "<p>no posts</p>".repeat(6_000) + "</body></html>"

    /** Stub: [flakySub] answers 500 (transient failure), [emptySub] answers a large
     *  zero-card feed (confirmed empty), everything else answers the real fixture. */
    private fun stubClientMixed(flakySub: String, emptySub: String, okFixture: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val path = chain.request().url.encodedPath
                val isFlaky = path.contains("/$flakySub/")
                val body = when {
                    isFlaky -> "boom"
                    path.contains("/$emptySub/") -> emptyFeedPage()
                    else -> javaClass.classLoader
                        .getResourceAsStream("reddit_ssr/$okFixture")
                        ?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("missing fixture $okFixture")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (isFlaky) 500 else 200)
                    .message("stub")
                    .body(body.toResponseBody(null))
                    .build()
            })
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

    @Test
    fun failedAndConfirmedEmptySubsAreDistinguishedInFinalEmission() =
        runBlocking {
            withTimeout(60_000) {
                val source = RedditOfficialSource(
                    okHttpClient = stubClientMixed("flaky", "quiet", "ra_android_p1.html"),
                    moshi = testMoshi(),
                    ioDispatcher = Dispatchers.IO
                )

                val emissions = source.getSubredditFanOutProgressive(
                    multiredd = "oksub+flaky+quiet",
                    sort = com.cosmos.unreddit.data.model.Sort.HOT,
                    timeSorting = null,
                    after = null,
                    stream = true
                ).toList()

                val last = emissions.last()
                assertTrue("last emission must be the final one", last.isFinal)

                // The transient failure (500 -> retries exhausted -> IOException) is the
                // ONLY failed sub. The confirmed-empty sub (200, zero cards) is NOT a
                // failure — its cache may be dropped as intended.
                assertEquals(
                    "only the 5xx sub is failed; the 200-empty sub is confirmed empty",
                    setOf("flaky"),
                    last.failedSubs
                )

                // perSub stays aligned to the original sub order on the final emission.
                assertEquals(3, last.perSub.size)
                assertTrue("the successful sub delivers its posts", last.perSub[0].isNotEmpty())
                assertTrue("the failed sub yields no new posts this cycle", last.perSub[1].isEmpty())
                assertTrue("the confirmed-empty sub yields no new posts", last.perSub[2].isEmpty())
            }
        }
}
