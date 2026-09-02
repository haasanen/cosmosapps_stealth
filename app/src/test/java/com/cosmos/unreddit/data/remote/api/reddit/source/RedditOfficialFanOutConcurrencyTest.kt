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
}
