package com.cosmos.unreddit.data.remote.api.reddit.source

import android.net.Uri
import android.text.Html
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutData
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserData
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentData
import com.cosmos.unreddit.data.remote.api.reddit.model.Data
import com.cosmos.unreddit.data.remote.api.reddit.model.JsonMore
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.di.DispatchersModule.IoDispatcher
import com.cosmos.unreddit.di.NetworkModule.RedditMoshi
import com.cosmos.unreddit.di.NetworkModule.RedditScrapOkHttp
import com.cosmos.unreddit.util.extension.interlace
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * "Reddit (official)" backend. Reads the **server-rendered** HTML that reddit.com itself
 * ships to a browser and parses it with jsoup. It is fully independent of the Arctic Shift
 * archive and of the RSS/embed channels: every screen is served by reddit.com's own pages.
 *
 * Contract notes (verified against live reddit.com captures, 2026-08-29/31):
 *  - A brand-new session's first request is answered with a small **JS challenge** page
 *    (~8 KB, zero content cards) whose inline script computes `solution = <16-hex literal>
 *    doubled` (`await (async e => e + e)(lit)`) and resubmits the form. This source
 *    reproduces that over plain HTTP: it extracts the literal and the form token from the
 *    page and GETs `https://www.reddit.com{form action}?<original query>&js_challenge=1&
 *    token=…&jsc_orig_r=&solution=<lit><lit>`. Solving issues the session cookies
 *    (`token_v2`, `loid`, `csrf_token`, `session_tracker`) which the [RedditCookieJar]
 *    persists for every follow-up request in the same client.
 *  - Feeds render only the first few `shreddit-post` cards in the initial HTML; the rest of
 *    the page load from an embedded continuation partial (`faceplate-partial` with
 *    `slot="load-after"` pointing at `/svc/shreddit/community-more-posts/…` or
 *    `/svc/shreddit/feeds/popular-feed`). The URL is HTML-escaped in the markup (`&amp;`) —
 *    jsoup decodes it — and is bound to the solved session, so it is fetched immediately
 *    with the same cookie-carrying client and its cards are merged in: a feed returns a
 *    full page (~27 cards) instead of the 3 that are server-rendered.
 *  - Home: the `/popular/` URL no longer exists on the live site (404 in a browser); the
 *    home feed is `https://www.reddit.com/r/popular/hot/`.
 *  - User posts: `/user/{u}/submitted/?count=25` (+ optional `sort=`, `after=`). The old
 *    `/user/{u}/{sort}/` URL is dead.
 *  - Post detail: `https://www.reddit.com/r/{sub}/comments/{id}/{slug}/` renders the OP
 *    (`shreddit-post view-context="CommentsPage"`) plus the top-level `shreddit-comment`
 *    cards in depth order. Comment bodies are lazy: each card carries a `reload-url`
 *    (`/svc/shreddit/comment/{t1_id}?…`) that returns the card with its markdown body, so
 *    the bodies are fetched in bounded parallel.
 *  - Search (global and in-subreddit) mixes two block markups: legacy
 *    `data-testid="search-post-with-content-preview"` and the newer `data-testid=
 *    "search-post-unit"`. Both carry a `search-telemetry-tracker` whose
 *    `data-faceplate-tracking-context` JSON holds the post id, title, author and subreddit.
 *    User search returns `search-author` blocks (profile links); community search returns
 *    `search-community` blocks.
 *  - A Cloudflare challenge page (small body + "Just a moment") is retried, then surfaced
 *    as an error. There is deliberately NO silent fallback to another source.
 */
@Singleton
class RedditOfficialSource @Inject constructor(
    @RedditScrapOkHttp private val okHttpClient: OkHttpClient,
    @RedditMoshi moshi: Moshi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BaseRedditSource {

    private val postAdapter: JsonAdapter<PostData> = moshi.adapter(PostData::class.java)
    private val commentAdapter: JsonAdapter<CommentData> = moshi.adapter(CommentData::class.java)

    //region Subreddit

    override suspend fun getSubreddit(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        if (subreddit.contains("+")) {
            // A multiredd home feed. A JOINED multiredd URL is unusable for logged-out
            // clients (live-verified 2026-09-01: reddit.com answers 301 -> the front
            // page for both 3-sub and 73-sub joins), so the feed is assembled per
            // subreddit: one SSR request per sub (bounded concurrency), merged and
            // interlaced — the same data the website shows, with real scores and
            // comment counts. Cursor list (one `after` per sub, aligned to the sub
            // order) is threaded back for pagination.
            getSubredditFanOut(subreddit, sort, timeSorting, after)
        } else {
            val url = subredditFeedUrl(subreddit, sort, timeSorting, after)
            val body = fetchPage(url)
            val doc = Jsoup.parse(body)
            val children = loadFeedContinuation(doc, parsePostCards(doc))
            requireFeedHasPosts(url, body, children)
            Listing(
                KIND_LISTING,
                ListingData(null, children.size, children, nextPostCursor(children), null)
            )
        }
    }

    /**
     * A multiredd home feed, assembled one subreddit at a time. A JOINED
     * multiredd URL is unusable for logged-out clients (reddit.com answers 301 ->
     * the front page for both 3-sub and 73-sub joins, live-verified 2026-09-01),
     * so each subreddit's own SSR feed is fetched (bounded concurrency, the same
     * page the website shows a logged-in user) and the results are merged and
     * interlaced — giving real scores, comment counts and previews.
     *
     * Pagination: one `after` cursor per subreddit, aligned to the subreddit
     * order, joined with [FANOUT_CURSOR_SEP] into the single opaque cursor string
     * the paging layer hands back. A subreddit that fails (challenge, 404, empty)
     * is skipped rather than blanking the whole feed; only a total failure throws.
     */
    private suspend fun getSubredditFanOut(
        multiredd: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        val subs = multiredd.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        val cursors = subs.indices.map { i ->
            after?.split(FANOUT_CURSOR_SEP)?.getOrNull(i)?.takeIf { it.isNotBlank() }
        }
        val semaphore = Semaphore(FANOUT_CONCURRENCY)
        val perSub = coroutineScope {
            subs.mapIndexed { i, sub ->
                async {
                    semaphore.withPermit { fetchSubPostsLenient(sub, sort, timeSorting, cursors[i]) }
                }
            }.awaitAll()
        }
        val merged = mergeFanOut(perSub, sort)
        if (merged.isEmpty()) {
            // Every per-sub fetch failed (e.g. reddit.com's CF layer is challenging this
            // client hard right now). Degrade to reddit.com's own Atom feed so the home
            // screen is not blank. The Atom feed has no scores/comment counts; it is
            // still reddit.com, not a third-party source.
            System.out.println("[RedditOfficialSource] Multiredd fan-out produced no posts; falling back to the Atom feed")
            return getSubredditViaAtom(multiredd, sort, timeSorting, null)
        }
        val nextCursors = perSub.map { nextPostCursor(it) }
        val encoded = nextCursors.joinToString(FANOUT_CURSOR_SEP) { it ?: "" }
        return Listing(KIND_LISTING, ListingData(null, merged.size, merged, encoded, null))
    }

    /**
     * Fetch one subreddit's feed page (SSR + continuation partial) for the fan-out
     * path. Lenient: a single dead subreddit returns an empty list instead of
     * throwing, so one challenge/404 does not blank the whole home feed.
     */
    private suspend fun fetchSubPostsLenient(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): List<PostChild> = try {
        val url = subredditFeedUrl(subreddit, sort, timeSorting, after)
        val body = fetchPage(url)
        val doc = Jsoup.parse(body)
        loadFeedContinuation(doc, parsePostCards(doc))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Merge the per-subreddit feeds into one list, de-duplicated by fullname.
     * NEW sorts by date, TOP by score; every other sort interlaces the lists so
     * the feed reads as a balanced mix rather than sub-blocks (matches the
     * SmartPostListDataSource merge semantics).
     */
    private fun mergeFanOut(perSub: List<List<PostChild>>, sort: Sort): List<PostChild> {
        val seen = HashSet<String>()
        val deduped = perSub.map { list -> list.filter { seen.add(it.data.name) } }
            .filter { it.isNotEmpty() }
        if (deduped.isEmpty()) return emptyList()
        return when (sort) {
            Sort.NEW -> deduped.flatten().sortedByDescending { it.data.created }
            Sort.TOP -> deduped.flatten().sortedByDescending { it.data.score }
            else -> deduped.interlace()
        }
    }

    //region Progressive fan-out (home feed)

    /**
     * A snapshot of fan-out progress for the UI's progress header: how many
     * subreddits are done of the total, which are in flight right now, and which
     * one just finished.
     */
    data class FanOutProgress(
        val total: Int,
        val done: Int,
        val noData: Int,
        val inFlight: List<String>,
        val lastFinished: String?
    )

    /**
     * One emission of the progressive fan-out.
     *
     * [FanOutPage.perSub] holds the finished subreddits' posts, aligned to the
     * ORIGINAL subreddit order (not completion order), so the collector can re-merge
     * on every emission — with cache, with its own priority rules — without the
     * content reshuffling between updates. When streaming (home-feed page 1) an
     * emission happens after every subreddit finishes; otherwise exactly one final
     * emission is produced.
     */
    data class FanOutPage(
        val perSub: List<List<PostChild>>,
        val cursors: Map<String, String?>,
        val progress: FanOutProgress,
        val isFinal: Boolean
    )

    /**
     * A multiredd feed, one subreddit at a time — the streaming sibling of
     * [getSubredditFanOut], used by the home feed.
     *
     * Subreddits are fetched in a SHUFFLED order per call so the same subs are
     * never the first the user sees (a user who does not wait for the full load
     * would otherwise never see the late ones). Concurrency is
     * [FANOUT_CONCURRENCY] and every sub gets a random pre-delay: reddit.com's
     * Cloudflare layer blocks repeated identical request patterns, so the
     * timing varies between requests and between runs.
     *
     * [after] has the same shape as in [getSubredditFanOut]: one `after` cursor
     * per subreddit, aligned to the subreddit order of [multiredd], joined with
     * [FANOUT_CURSOR_SEP].
     *
     * A subreddit that fails (challenge, 404, empty) is skipped rather than
     * blanking the feed; its [FanOutPage.cursors] entry is null so deeper pages
     * simply stop for that sub.
     */
    fun getSubredditFanOutProgressive(
        multiredd: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?,
        stream: Boolean
    ): Flow<FanOutPage> = channelFlow {
        val subs = multiredd.split("+").map { it.trim() }.filter { it.isNotEmpty() }
        val afterParts = after?.split(FANOUT_CURSOR_SEP)
        val cursors = subs.zip(
            (0 until subs.size).map { afterParts?.getOrNull(it)?.takeIf { c -> c.isNotBlank() } }
        ).toMap()

        val results = ConcurrentHashMap<String, List<PostChild>>()
        // Null-tolerant: a sub with no next page has a null cursor. (ConcurrentHashMap
        // rejects null values — the 2026-09-02 test caught the resulting NPE.)
        val cursorsOut = Collections.synchronizedMap(LinkedHashMap<String, String?>())
        val doneCount = AtomicInteger()
        val noDataCount = AtomicInteger()
        val inFlight = Collections.synchronizedSet(LinkedHashSet<String>())
        val lastFinished = AtomicReference<String?>(null)
        val semaphore = Semaphore(FANOUT_CONCURRENCY)
        val random = Random()

        // Emission order is the ORIGINAL subreddit order, not completion order,
        // so the interleave is stable across emissions (no reshuffling of the same
        // content between updates). The collector re-merges perSub on each emission.
        // channelFlow (not flow): several fan-out workers finish concurrently and
        // each sends a snapshot; `flow`'s collect{}-based emit is not thread-safe
        // and throws "Emission from another coroutine is detected" (see the
        // device crash of 2026-09-02). channel.send() serializes the sends.
        suspend fun sendSnapshot(final: Boolean) {
            val finished = subs.mapNotNull { results[it] }
            channel.send(
                FanOutPage(
                    perSub = finished,
                    cursors = cursorsOut.toMap(),
                    progress = FanOutProgress(
                        total = subs.size,
                        done = doneCount.get(),
                        noData = noDataCount.get(),
                        inFlight = ArrayList(inFlight).take(3),
                        lastFinished = lastFinished.get()
                    ),
                    isFinal = final
                )
            )
        }

        coroutineScope {
            subs.shuffled().map { sub ->
                async {
                    semaphore.withPermit {
                        inFlight.add(sub)
                        try {
                            // Stagger starts: a burst of 73 identical requests at once
                            // is exactly the pattern CF looks for.
                            delay(random.nextInt(FANOUT_JITTER_MS.toInt()).toLong())
                            val posts = fetchSubPostsLenient(sub, sort, timeSorting, cursors[sub])
                            results[sub] = posts
                            // nextPostCursor is null when the sub has no next page;
                            // ConcurrentHashMap rejects null values — use the null-tolerant map.
                            cursorsOut[sub] = nextPostCursor(posts)
                            if (posts.isEmpty()) noDataCount.incrementAndGet()
                        } finally {
                            inFlight.remove(sub)
                            doneCount.incrementAndGet()
                            lastFinished.set(sub)
                        }
                        if (stream) sendSnapshot(false)
                    }
                }
            }.awaitAll()
        }
        sendSnapshot(true)
    }

    //endregion

    override suspend fun getSubredditInfo(subreddit: String): Child = withContext(ioDispatcher) {
        val doc = Jsoup.parse(fetchPage("https://www.reddit.com/r/$subreddit/"))
        AboutChild(buildAboutData(subreddit, doc))
    }

    /**
     * Multiredd feed via reddit's official Atom endpoint. The multiredd SSR HTML is
     * login-gated for anonymous clients (a full page with zero post elements), but the
     * Atom feed for the same multiredd is served openly. Verified live (2026-08-31):
     * `https://www.reddit.com/r/{multi}/hot/.rss?over18=1` returns 25 entries for
     * 8-sub and 30-sub lists, and `after=<t3_id>` paginates with no overlap.
     *
     * Entries carry title, link, id (t3_...), author name, category (subreddit),
     * published, and an HTML `<content>` block from which the external link and the
     * thumbnail are extracted. Score and comment counts are not part of the Atom feed;
     * they default to 0 (the Arctic source shows the same limitation on its multiredd
     * feeds).
     */
    suspend fun getSubredditViaAtom(
        multiredd: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {        val url = buildString {
            append("https://www.reddit.com/r/").append(multiredd).append('/')
            append(feedSortPath(sort)).append("/.rss?over18=1")
            if (sort == Sort.TOP || sort == Sort.CONTROVERSIAL) {
                timeSorting?.type?.let { append("&t=").append(it) }
            }
            if (!after.isNullOrBlank()) append("&after=").append(after)
        }
        var lastError = "no response from server"
        var body: String? = null
        for (attempt in 0 until SSR_RETRIES) {
            val req = newRequest(url, "GET", forPartial = false)
                .newBuilder()
                .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .build()
            val resp = runCatching { okHttpClient.newCall(req).execute() }.getOrNull()
            if (resp != null) {
                resp.use {
                    if (it.isSuccessful) {
                        val b = it.body?.string()
                        if (!b.isNullOrBlank()) { body = b } else { lastError = "empty response body" }
                    } else {
                        lastError = "HTTP ${it.code}"
                    }
                }
            } else {
                lastError = "connection failed"
            }
            if (body != null) break
            if (attempt < SSR_RETRIES - 1) delay(SSR_RETRY_BASE_MS * (1L shl attempt))
        }
        val xml = body ?: throw IOException("reddit.com multiredd feed $url failed ($lastError)")
        val doc = Jsoup.parse(xml)
        val entries = doc.select("entry").mapNotNull { atomPostFromEntry(it) }
        if (entries.isEmpty()) {
            val entryCount = doc.select("entry").size
            throw IOException(
                "reddit.com returned no posts for $url (feed ${xml.length} chars, " +
                    "entries=$entryCount; the multiredd feed may have changed — please report this)"
            )
        }
        return Listing(
            KIND_LISTING,
            ListingData(null, entries.size, entries, nextPostCursor(entries), null)
        )
    }

    /** Maps one Atom `<entry>` to a [PostChild] using the shared [parsePost] pipeline. */
    private fun atomPostFromEntry(entry: Element): PostChild? {
        val name = entry.selectFirst("id")?.ownText()?.trim()
            ?.takeIf { it.startsWith("t3_") } ?: return null
        val title = entry.selectFirst("title")?.ownText()?.trim() ?: ""
        val link = entry.selectFirst("link")?.attr("href") ?: ""
        val authorName = entry.selectFirst("author name")?.ownText()?.trim()
            ?.removePrefix("/u/") ?: ""
        val sub = entry.selectFirst("category")?.attr("label")
            ?.removePrefix("r/")
            ?.takeIf { it.isNotBlank() } ?: "unknown"
        val published = entry.selectFirst("published")?.ownText()
            ?.takeIf { it.isNotBlank() } ?: entry.selectFirst("updated")?.ownText() ?: ""
        // The HTML content block carries the external link and the preview image.
        // `[link]` is the post's own permalink for self-posts, the external URL
        // otherwise; `[comments]` is always the post permalink.
        val contentHtml = entry.selectFirst("content")?.data() ?: ""
        val linkHref = Regex("""<a href="([^"]+)"">\[link\]</a>""").find(contentHtml)?.groupValues?.get(1)
        val commentsHref = Regex("""<a href="([^"]+)"">\[comments\]</a>""").find(contentHtml)?.groupValues?.get(1)
        val permalink = commentsHref?.ifBlank { link } ?: link
        val externalUrl = linkHref?.takeIf { it.isNotBlank() && it != permalink }
        val isSelf = externalUrl == null
        val url = if (isSelf) permalink else externalUrl

        val map = mutableMapOf<String, Any?>(
            "name" to name,
            "id" to name.removePrefix("t3_"),
            "subreddit" to sub,
            "subreddit_name_prefixed" to "r/$sub",
            "title" to title,
            "author" to authorName,
            "created_utc" to parseAtomTimestamp(published),
            "permalink" to permalink,
            "url" to url,
            "domain" to if (isSelf) "self.$sub"
            else runCatching { Uri.parse(url).host ?: "unknown" }.getOrDefault("unknown"),
            "is_self" to isSelf,
            "score" to 0,
            "num_comments" to 0,
            "upvote_ratio" to 0.0,
            "link_flair_richtext" to emptyList<Any>()
        )
        // Media posts carry a media:thumbnail element; link images embed a redd.it img tag
        // in the (HTML-escaped) <content> block, which .data() returns undecoded.
        entry.getElementsByTag("media:thumbnail").firstOrNull()?.attr("url")?.takeIf { it.isNotBlank() }?.let {
            map["thumbnail"] = it
        }
        Regex("""src="([^"]+redd\.it[^"]*|[^"]+redditmedia[^"]*)"""").find(contentHtml)?.groupValues?.get(1)?.let {
            map.putIfAbsent("thumbnail", it)
        }
        return runCatching { PostChild(parsePost(ensurePostDefaults(map, sub))) }.getOrNull()
    }

    /** Atom timestamps: `2026-08-31T15:41:07+00:00` -> epoch seconds. */
    private fun parseAtomTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            val cleaned = value
                .replace(Regex("Z$"), "+00:00")
                .replace(Regex("\\.\\d+$"), "")
            java.time.OffsetDateTime.parse(cleaned).toEpochSecond()
        }.getOrDefault(0L)
    }

    /**
     * A user's posts via reddit's Atom endpoint — the same `entry` format as the
     * subreddit feed, so the shared [atomPostFromEntry] pipeline applies unchanged.
     * Used by the "Reddit (Atom)" source; same limitation (no scores/comment counts).
     */
    suspend fun getUserPostsViaAtom(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        val url = buildString {
            append("https://www.reddit.com/user/").append(user).append("/submitted/.rss?count=").append(PAGE_SIZE)
            append("&sort=").append(feedSortPath(sort))
            if (sort == Sort.TOP || sort == Sort.CONTROVERSIAL) {
                timeSorting?.type?.let { append("&t=").append(it) }
            }
            if (!after.isNullOrBlank()) append("&after=").append(after)
        }
        val xml = fetchAtomBody(url)
        val doc = Jsoup.parse(xml)
        val entries = doc.select("entry").mapNotNull { atomPostFromEntry(it) }
        if (entries.isEmpty()) {
            val entryCount = doc.select("entry").size
            throw IOException(
                "reddit.com returned no posts for $url (feed ${xml.length} chars, " +
                    "entries=$entryCount)"
            )
        }
        return Listing(KIND_LISTING, ListingData(null, entries.size, entries, nextPostCursor(entries), null))
    }

    /**
     * Shared Atom fetch: `Accept: application/atom+xml` with the same retries as the
     * SSR path, so a challenged or throttled request is handled identically.
     */
    private suspend fun fetchAtomBody(url: String): String {
        var lastError = "no response from server"
        var body: String? = null
        for (attempt in 0 until SSR_RETRIES) {
            val req = newRequest(url, "GET", forPartial = false)
                .newBuilder()
                .header("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .build()
            val resp = runCatching { okHttpClient.newCall(req).execute() }.getOrNull()
            if (resp != null) {
                resp.use {
                    if (it.isSuccessful) {
                        val b = it.body?.string()
                        if (!b.isNullOrBlank()) { body = b } else { lastError = "empty response body" }
                    } else {
                        lastError = "HTTP ${it.code}"
                    }
                }
            } else {
                lastError = "connection failed"
            }
            if (body != null) break
            if (attempt < SSR_RETRIES - 1) delay(SSR_RETRY_BASE_MS * (1L shl attempt))
        }
        return body ?: throw IOException("reddit.com Atom feed $url failed ($lastError)")
    }

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val url = buildString {
            append("https://www.reddit.com/r/$subreddit/search/?q=")
            append(encode(query))
            append("&restrict_sr=1&sort=")
            append(searchSort(sort))
            if (!after.isNullOrBlank()) append("&after=").append(after)
        }
        val doc = Jsoup.parse(fetchPage(url))
        val children = parseSearchBlocks(doc)
        Listing(KIND_LISTING, ListingData(null, children.size, children, null, null))
    }

    //endregion

    //region Post detail

    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> =
        withContext(ioDispatcher) {
            val url = if (permalink.startsWith("/")) "https://www.reddit.com$permalink"
            else "https://www.reddit.com/$permalink"
            var body = fetchPage(url)
            var doc = Jsoup.parse(body)
            // Slugless permalinks (e.g. /r/x/comments/abc123/) are served as a JS-redirect
            // stub pointing at the slugged URL. Follow it once so the detail page loads
            // for the permalinks the Atom feed hands over.
            if (doc.select("shreddit-post").isEmpty()) {
                val redirectTarget = shredditRedirectTarget(doc)
                if (redirectTarget != null) {
                    val redirectUrl = if (redirectTarget.startsWith("/")) "https://www.reddit.com$redirectTarget"
                    else redirectTarget
                    body = fetchPage(redirectUrl)
                    doc = Jsoup.parse(body)
                }
            }

            val opElement = doc.select("shreddit-post").firstOrNull { it.attr("view-context") == "CommentsPage" }
                ?: doc.select("shreddit-post").firstOrNull()
                ?: throw IOException("Post not found: $permalink")
            val op = postChildFromElement(opElement)
                ?: throw IOException("Post not found: $permalink")

            val postListing = Listing(
                KIND_LISTING,
                ListingData(null, null, listOf(op), null, null)
            )

            val comments = fetchComments(doc, op.data.subreddit, op.data.name, op.data.title)
            val commentsListing = Listing(
                KIND_LISTING,
                ListingData(null, comments.size, comments, null, null)
            )

            listOf(postListing, commentsListing)
        }

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren =
        withContext(ioDispatcher) {
            val ids = children.split(",").map { it.trim() }.filter { it.startsWith("t1_") }
            val base = "https://www.reddit.com"
            val things = ids.mapNotNull { id ->
                runCatching {
                    val body = fetchPartial("$base/svc/shreddit/comment/$id") ?: return@runCatching null
                    val d = Jsoup.parse(body)
                    val el = d.select("shreddit-comment").firstOrNull() ?: return@runCatching null
                    buildCommentChild(
                        name = el.attr("thingId").ifBlank { id },
                        author = el.attr("author"),
                        depth = el.attr("depth").toIntOrNull() ?: 0,
                        createdAttr = el.attr("created"),
                        scoreAttr = el.attr("score"),
                        permalink = el.attr("permalink"),
                        linkId = linkId,
                        subreddit = null,
                        linkTitle = null,
                        bodyHtml = d.select(".md").firstOrNull()?.html() ?: ""
                    )
                }.getOrNull()
            }
            MoreChildren(JsonMore(Data(things)))
        }

    //endregion

    //region User

    override suspend fun getUserInfo(user: String): Child = withContext(ioDispatcher) {
        val doc = Jsoup.parse(fetchPage("https://www.reddit.com/user/$user/"))
        AboutUserChild(buildAboutUserData(user, doc))
    }

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val url = userPostsUrl(user, sort, after)
        val doc = Jsoup.parse(fetchPage(url))
        val children = loadFeedContinuation(doc, parsePostCards(doc))
        Listing(KIND_LISTING, ListingData(null, children.size, children, nextPostCursor(children), null))
    }

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val url = buildString {
            append("https://www.reddit.com/user/$user/comments/?count=").append(PAGE_SIZE)
            if (!after.isNullOrBlank()) append("&after=").append(after)
        }
        val doc = Jsoup.parse(fetchPage(url))
        val children = parseProfileComments(doc, user)
        Listing(
            KIND_LISTING,
            ListingData(null, children.size, children, nextCommentCursor(children), null)
        )
    }

    //endregion

    //region Search

    override suspend fun searchPost(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val url = buildString {
            append("https://www.reddit.com/search/?q=").append(encode(query))
            append("&sort=").append(searchSort(sort))
        }
        val doc = Jsoup.parse(fetchPage(url))
        val children = parseSearchBlocks(doc)
        Listing(KIND_LISTING, ListingData(null, children.size, children, null, null))
    }

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val url = "https://www.reddit.com/search/?q=${encode(query)}&type=user"
        val doc = Jsoup.parse(fetchPage(url))
        val children = doc.select("[data-testid=search-author]").mapNotNull { block ->
            val name = trackerField(block, "profile", "name")
                ?: block.selectFirst("a[href^=/user/]")?.attr("href")
                    ?.removePrefix("/user/")?.trimEnd('/')?.take(1)
                ?: return@mapNotNull null
            val icon = block.selectFirst("img")?.attr("src")
            AboutUserChild(
                AboutUserData(
                    subreddit = null,
                    id = null,
                    iconImg = icon,
                    name = name,
                    snoovatarImg = icon
                )
            )
        }.distinctBy { (it as AboutUserChild).data.name }
        Listing(KIND_LISTING, ListingData(null, children.size, children, null, null))
    }

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val url = "https://www.reddit.com/search/?q=${encode(query)}&type=community"
        val doc = Jsoup.parse(fetchPage(url))
        val children = doc.select("[data-testid=search-community]").mapNotNull { block ->
            val name = trackerField(block, "subreddit", "name")
                ?: block.selectFirst("a[href^=/r/]")?.attr("href")?.removePrefix("/r/")?.trimEnd('/')
                ?: return@mapNotNull null
            val icon = block.selectFirst("img")?.attr("src")
            AboutChild(
                AboutData(
                    wikiEnabled = null,
                    displayName = name,
                    headerImg = null,
                    title = name,
                    primaryColor = null,
                    activeUserCount = null,
                    iconImg = icon,
                    subscribers = null,
                    quarantine = null,
                    publicDescriptionHtml = "",
                    communityIcon = "",
                    bannerBackgroundImage = "",
                    keyColor = null,
                    bannerBackgroundColor = null,
                    over18 = null,
                    descriptionHtml = "",
                    url = "/r/$name/",
                    created = 0L
                )
            )
        }.distinctBy { (it as AboutChild).data.displayName }
        Listing(KIND_LISTING, ListingData(null, children.size, children, null, null))
    }

    //endregion

    //region HTTP

    /**
     * Fetches a full page, solving the JS challenge when reddit.com answers with one,
     * retrying on failure, then throws if still unusable.
     */
    private suspend fun fetchPage(url: String): String {
        var lastError = "no response from server"
        for (attempt in 0 until SSR_RETRIES) {
            val body = doGet(url, "GET", forPartial = false) ?: continue
            if (isCloudflareChallenge(body)) {
                lastError = "Cloudflare challenge page"
                if (attempt < SSR_RETRIES - 1) delay(SSR_RETRY_BASE_MS * (1L shl attempt))
                continue
            }
            val solved = solveJsChallenge(body, url)
            if (solved != null) return solved
            if (parseJsChallenge(body) == null) return body // not a challenge: real page
            lastError = "JS challenge could not be solved"
            if (attempt < SSR_RETRIES - 1) delay(SSR_RETRY_BASE_MS * (1L shl attempt))
        }
        throw IOException("reddit.com did not return a usable page ($lastError). Please try again.")
    }
    /**
     * If [body] is a JS challenge page, solves it over plain HTTP (doubled-literal solution
     * + form token) and returns the real page; otherwise returns null.
     *
     * The challenge's inline script copies every current URL query param into hidden inputs
     * and submits the form; we do the same. The resubmitted request goes through the same
     * OkHttp client so the session cookies it issues persist in the [RedditCookieJar] for
     * the follow-up requests (feeds, continuations, partials).
     */
    private suspend fun solveJsChallenge(body: String, originalUrl: String): String? {
        // Some networks (mobile carriers in particular) answer the solved resubmit with yet
        // another challenge page carrying a fresh token. The browser rides the same flow; we
        // loop up to [CHALLENGE_MAX_ROUNDS] rounds, re-sending the session cookies each time.
        var current = body
        for (round in 1..CHALLENGE_MAX_ROUNDS) {
            val challenge = parseJsChallenge(current) ?: return null
            val solvedUrl = buildChallengeResubmitUrl(challenge, originalUrl)
            val solved = doGet(solvedUrl, "GET", forPartial = false) ?: return null
            if (isCloudflareChallenge(solved)) return null
            val next = parseJsChallenge(solved)
            if (next == null) return solved // real page
            if (next.token == challenge.token) return null // same token: it will not progress
            current = solved // a fresh challenge: solve it again
        }
        return null
    }

    /**
     * Extracts the challenge form data from a challenge page, or null when the body is not a
     * challenge (real pages are large and never carry the challenge form/script).
     */
    private fun parseJsChallenge(body: String): ChallengeForm? {
        if (body.length > CHALLENGE_MAX_SIZE) return null // real pages are large
        val doc = Jsoup.parse(body)
        val tokenInput = doc.selectFirst("form input[name=token]") ?: return null
        val form = tokenInput.closest("form") ?: return null
        val token = tokenInput.attr("value")
        if (token.isNullOrBlank()) return null
        val action = form.attr("action").ifBlank { return null }
        val literal = JS_CHALLENGE_LITERAL.find(body)?.groupValues?.get(1)
            ?: return null
        return ChallengeForm(action = action, token = token, literal = literal)
    }

    /**
     * Builds the challenge resubmit URL the way the browser's form submit does: the form
     * action plus the original page's own query params (the browser copies them into hidden
     * inputs) and the hidden fields (`js_challenge`, `token`, `jsc_orig_r`, `solution`).
     * The solution is the 16-hex literal doubled — exactly what
     * `await (async e => e + e)(lit)` produces. The form action is relative
     * (e.g. `/r/Android/hot/`) and is resolved against the site root.
     */
    private fun buildChallengeResubmitUrl(challenge: ChallengeForm, originalUrl: String): String {
        val originalQuery = originalUrl.substringAfter('?', missingDelimiterValue = "")
        val solution = challenge.literal + challenge.literal
        val action = if (challenge.action.startsWith("/")) "https://www.reddit.com" + challenge.action
        else challenge.action
        val sep = if (action.contains("?")) "&" else "?"
        val params = buildList {
            if (originalQuery.isNotBlank()) add(originalQuery)
            add("js_challenge=1")
            add("token=" + challenge.token)
            add("jsc_orig_r=")
            add("solution=" + solution)
        }.joinToString("&")
        return action + sep + params
    }

    /**
     * Fetches a lazy partial (comment body / continuation). Tries GET then POST; returns the
     * body or null. A null body means the caller degrades gracefully (e.g. an empty comment
     * body, or a page without its continuation cards) instead of failing the whole request.
     */
    private suspend fun fetchPartial(url: String): String? {
        for (method in listOf("GET", "POST")) {
            val body = doGet(url, method, forPartial = true)
            if (body != null && !isCloudflareChallenge(body)) return body
        }
        return null
    }

    private suspend fun doGet(url: String, method: String, forPartial: Boolean): String? {
        // TEMP diagnostics (JVM-safe in FeedDebug; removed once root cause is found).
        com.cosmos.unreddit.ui.postlist.FeedDebug.log("HTTP ${method} $url")
        val t0 = System.currentTimeMillis()
        val result = try {
            val req = newRequest(url, method, forPartial)
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: the caller's scope must be able to cancel this
            // request. Swallowing it here made the caller believe the network call had
            // simply returned null and keep working (or retrying) after cancellation.
            throw e
        } catch (e: Exception) {
            com.cosmos.unreddit.ui.postlist.FeedDebug.log("HTTP FAILED ${System.currentTimeMillis() - t0}ms: ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        com.cosmos.unreddit.ui.postlist.FeedDebug.log("HTTP done ${System.currentTimeMillis() - t0}ms (body=${result?.length ?: 0})")
        return result
    }

    private fun newRequest(url: String, method: String, forPartial: Boolean): Request {
        val builder = Request.Builder().url(url)
        builder.addHeader("User-Agent", USER_AGENT)
        builder.addHeader(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        builder.addHeader("Accept-Language", "en-US,en;q=0.9")
        builder.addHeader("Upgrade-Insecure-Requests", "1")
        if (forPartial) {
            builder.addHeader("Sec-Fetch-Dest", "empty")
            builder.addHeader("Sec-Fetch-Mode", "cors")
        } else {
            builder.addHeader("Sec-Fetch-Dest", "document")
            builder.addHeader("Sec-Fetch-Mode", "navigate")
        }
        builder.addHeader("Sec-Fetch-Site", "same-origin")
        builder.addHeader("Sec-Fetch-User", "?1")
        builder.method(method, null)
        return builder.build()
    }

    private fun isCloudflareChallenge(body: String): Boolean {
        if (body.length > 30_000) return false // real pages are large
        val s = body.lowercase()
        return s.contains("just a moment") ||
            s.contains("cf-chl") ||
            s.contains("challenge-platform") ||
            s.contains("attention required")
    }

    /**
     * Fetches the embedded "load more" continuation partial for [doc] (the `faceplate-partial`
     * with `slot="load-after"` whose src points at a more-posts feed) and merges its
     * `shreddit-post` cards onto [initial], de-duplicated by fullname.
     *
     * The partial is bound to the solved session and is fetched immediately with the same
     * cookie-carrying client. Returns [initial] unchanged when the page has no
     * continuation or the partial cannot be fetched — a continuation is an enhancement,
     * never a failure.
     */
    private suspend fun loadFeedContinuation(
        doc: Document,
        initial: List<PostChild>
    ): List<PostChild> {
        val src = doc.selectFirst(CONTINUATION_SELECTOR)?.attr("src") ?: return initial
        if (src.isBlank()) return initial
        val baseUrl = if (doc.location().isNotBlank()) doc.location() else "https://www.reddit.com"
        val url = if (src.startsWith("/")) baseUrl + src else src
        val body = runCatching { fetchPartial(url) }.getOrNull() ?: return initial
        val partial = runCatching { Jsoup.parse(body) }.getOrNull() ?: return initial
        val more = partial.select("shreddit-post").mapNotNull { postChildFromElement(it) }
        if (more.isEmpty()) return initial
        val seen = initial.mapTo(HashSet()) { it.data.name }
        val merged = initial.toMutableList()
        more.filter { seen.add(it.data.name) }.forEach(merged::add)
        return merged
    }

    /**
     * A feed that resolves to zero posts is never a valid reddit.com answer — a real feed
     * page always carries at least one server-rendered card. Zero means the page we got was
     * not a feed (an unrecognized challenge/interstitial variant, a soft-block, or a layout
     * change the parser no longer recognizes). Silently rendering an empty list for that was
     * the "black front page" bug: the user saw nothing and there was no error to act on.
     *
     * Throwing instead surfaces a retry banner whose message names exactly what the phone
     * received (size + <title> + recognizable markers), which is what unblocks the next
     * fix. Detail pages and searches can legitimately be empty, so this applies to feeds
     * only.
     */
    private fun requireFeedHasPosts(url: String, body: String, children: List<PostChild>) {
        if (children.isNotEmpty()) return
        val doc = Jsoup.parse(body)
        val title = doc.title().ifBlank { "(no title)" }
        val markers = listOf(
            "challenge" to ("name=\"token\"" in body),
            "cf-clearance" to ("cf-chl" in body || "challenge-platform" in body),
            "just-a-moment" to ("just a moment" in body.lowercase()),
            "post-cards" to ("shreddit-post" in body),
            "partial" to ("faceplate-partial" in body)
        ).filter { it.second }.joinToString(", ")
        throw IOException(
            "reddit.com returned no posts for $url " +
                "(page ${body.length} chars, title \"$title\"; markers: ${markers.ifBlank { "none recognized" }}). " +
                "The page layout may have changed — please report this."
        )
    }

    //endregion

    //region URL builders

    private fun subredditFeedUrl(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): String = buildString {
        append("https://www.reddit.com")
        if (subreddit.equals(POPULAR, ignoreCase = true)) {
            // "/popular/" is dead on the live site; the home feed is r/popular.
            append("/r/popular/").append(feedSortPath(sort)).append("/")
        } else {
            append("/r/").append(subreddit).append("/").append(feedSortPath(sort)).append("/")
        }
        append("?count=").append(PAGE_SIZE)
        if (sort == Sort.TOP || sort == Sort.CONTROVERSIAL) {
            timeSorting?.type?.let { append("&t=").append(it) }
        }
        if (!after.isNullOrBlank()) append("&after=").append(after)
    }

    /**
     * `/user/{u}/submitted/?count=25` — the live URL for a user's posts (the old
     * `/user/{u}/{sort}/` is 404). `sort=` accepts hot/new/top/controversial; `after=`
     * advances pagination with the last post's fullname.
     */
    private fun userPostsUrl(user: String, sort: Sort, after: String?): String = buildString {
        append("https://www.reddit.com/user/").append(user).append("/submitted/?count=").append(PAGE_SIZE)
        append("&sort=").append(feedSortPath(sort))
        if (!after.isNullOrBlank()) append("&after=").append(after)
    }

    private fun feedSortPath(sort: Sort): String = when (sort) {
        Sort.HOT, Sort.RELEVANCE, Sort.COMMENTS, Sort.BEST, Sort.QA -> "hot"
        Sort.NEW -> "new"
        Sort.TOP -> "top"
        Sort.RISING -> "rising"
        Sort.CONTROVERSIAL -> "controversial"
        Sort.OLD -> "new"
    }

    private fun searchSort(sort: Sort?): String = when (sort) {
        null, Sort.RELEVANCE -> "relevance"
        Sort.NEW -> "new"
        Sort.HOT -> "hot"
        Sort.TOP -> "top"
        Sort.CONTROVERSIAL -> "controversial"
        else -> "relevance"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    //endregion

    //region Post parsing

    /**
     * Reddit serves slugless permalinks as a page whose only post signal is a
     * `shreddit-redirect` `ac-call` with `location.replace(&quot;/r/x/comments/id/slug/&quot;)`.
     * Returns that target, or null when the page is not a redirect stub.
     */
    internal fun shredditRedirectTarget(doc: Document): String? {
        val call = doc.select("#shreddit-redirect ac-call").firstOrNull() ?: return null
        val method = call.attr("method")
        val m = Regex("location\\.replace\\(&quot;(.*?)&quot;\\)").find(method)
            ?: Regex("""location\.replace\("([^"]+)"""").find(method)
            ?: return null
        return m.groupValues.get(1).takeIf { it.isNotBlank() }
    }

    /** Selects the real post cards, dropping ads (a different tag) by construction. */
    private fun parsePostCards(doc: Document): List<PostChild> =
        doc.select("shreddit-post").mapNotNull { postChildFromElement(it) }

    /** Test-only: parse a single SSR post card (thumbnail selection, defaults, mapping). */
    internal fun parsePostCardForTest(el: Element): PostChild? = postChildFromElement(el)

    private fun postChildFromElement(el: Element): PostChild? {
        val name = el.attr("id").takeIf { it.startsWith("t3_") } ?: return null
        val sub = el.attr("subreddit-name").ifBlank { "unknown" }
        val permalink = el.attr("permalink")
        val author = el.attr("author")
        val title = el.attr("post-title")
        val created = el.attr("created-timestamp").let {
            if (it.isBlank()) 0L else parseRedditTimestamp(it)
        }
        val score = el.attr("score").toIntOrNull() ?: 0
        val comments = el.attr("comment-count").toIntOrNull() ?: 0
        val ratio = el.attr("upvote-ratio").toDoubleOrNull()
        val domain = el.attr("domain").ifBlank { "self.$sub" }
        val postType = el.attr("post-type")
        val contentHref = el.attr("content-href")
        val isSelf = postType == "text" || domain.startsWith("self.")
        val absolutePermalink = if (permalink.startsWith("/")) "https://www.reddit.com$permalink" else permalink
        val url = if (isSelf) absolutePermalink else (contentHref.ifBlank { absolutePermalink })

        val map = mutableMapOf<String, Any?>(
            "name" to name,
            "id" to name.removePrefix("t3_"),
            "subreddit" to sub,
            "subreddit_name_prefixed" to (el.attr("subreddit-prefixed-name").ifBlank { "r/$sub" }),
            "title" to title,
            "author" to author,
            "created_utc" to created,
            "permalink" to permalink,
            "url" to url,
            "domain" to domain,
            "is_self" to isSelf,
            "score" to score,
            "num_comments" to comments,
            "upvote_ratio" to (ratio ?: 0.0),
            "link_flair_richtext" to emptyList<Any>()
        )
        // Prefer the post's own preview/poster. The card's first redd.it/redditmedia
        // <img> is often the AUTHOR or COMMUNITY avatar (styles.redditmedia.com/
        // .../profileIcon, a.thumbs.redditmedia.com, redditstatic.com/avatars), which
        // would render as a broken/irrelevant thumbnail. So: pick a genuine preview
        // first, and only fall back to any non-avatar redd.it/redditmedia image.
        val imgSources = el.select("img").mapNotNull { img ->
            img.attr("src").takeIf { it.isNotBlank() }
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
        }
        val thumbnail = imgSources.firstOrNull(::isPreviewImageUrl)
            ?: imgSources.firstOrNull {
                (it.contains("redd.it") || it.contains("redditmedia")) && !isAvatarImageUrl(it)
            }
        thumbnail?.let { map["thumbnail"] = it }

        // SSR cards do not embed gallery_data / media_metadata (those only exist in the
        // JSON API). Gallery pages render each page as a media-lightbox-img element
        // (src or data-lazy-src), in order — inject them as the API-shaped objects so
        // the standard gallery pipeline (mediaType, gallery, previewUrl) works.
        if (el.attr("gallery").isNotBlank() || postType == "gallery") {
            val gallery = parseSsrGallery(el, name.removePrefix("t3_"))
            if (gallery.data.isNotEmpty()) {
                map["is_gallery"] = true
                map["gallery_data"] = gallery.data
                map["media_metadata"] = gallery.metadata
            }
        }

        return runCatching { PostChild(parsePost(ensurePostDefaults(map, sub))) }.getOrNull()
    }

    /**
     * The SSR gallery pages of a post: every `media-lightbox-img` is one gallery page,
     * in card order (later pages are lazy: `data-lazy-src` instead of `src`).
     * Returns API-shaped objects — `gallery_data.items[]` (ordered captions/ids) and
     * `media_metadata` (id → item map, the shape MediaMetadataAdapter expects) — keyed
     * by generated media ids so `PostData.gallery` can join the two.
     */
    private data class SsrGallery(val data: Map<String, Any?>, val metadata: Map<String, Any?>)

    private fun parseSsrGallery(el: Element, postId: String): SsrGallery {
        val dataItems = mutableListOf<Map<String, Any?>>()
        val metadataItems = mutableListOf<Map<String, Any?>>()
        var index = 0
        for (img in el.select("img.media-lightbox-img")) {
            // Gallery pages are served on cf.preview.redd.it — only exclude avatars.
            val url = (img.attr("src").ifBlank { img.attr("data-src") })
                .ifBlank { img.attr("data-lazy-src") }
                .takeIf { it.isNotBlank() && !isAvatarImageUrl(it) }
                ?: continue
            val mediaId = "t3_${postId}_${index}"
            dataItems.add(mapOf("media_id" to mediaId, "caption" to null))
            // MediaMetadata.items[] — one GalleryItem per page; PostData.gallery
            // joins media_id → GalleryItem.id, then reads GalleryItem.s (the image).
            metadataItems.add(
                mapOf(
                    "id" to mediaId,
                    "m" to "image/jpeg",
                    "s" to mapOf(
                        "u" to url,
                        "x" to (img.attr("width").toIntOrNull() ?: 0),
                        "y" to (img.attr("height").toIntOrNull() ?: 0)
                    )
                )
            )
            index++
        }
        return SsrGallery(mapOf("items" to dataItems), mapOf("items" to metadataItems))
    }

    /**
     * A genuine post preview/poster: Reddit serves image-post thumbnails on
     * `*.preview.redd.it` (cf.preview) and video/link-post cards on
     * `external-preview.redd.it`. Avatars (profileIcon), subreddit icons and the
     * `a.thumbs` community badges are never a post preview.
     *
     * `external-preview.redd.it` is matched explicitly — its host does NOT contain
     * the `.preview.redd.it` substring (hyphen, not dot), so relying on that check
     * alone would only catch it via the non-avatar fallback.
     */
    private fun isPreviewImageUrl(url: String): Boolean =
        ".preview.redd.it" in url ||
            "external-preview.redd.it" in url ||
            url.contains("preview-image")

    /**
     * Anything that identifies a user or community rather than the post media.
     */
    private fun isAvatarImageUrl(url: String): Boolean =
        "profileIcon" in url ||
            "styles.redditmedia.com" in url ||
            "redditstatic.com/avatars" in url ||
            "a.thumbs.redditmedia.com" in url ||
            "emoji.redditmedia.com" in url

    //endregion

    //region Comment parsing

    /**
     * Parses the SSR comment cards (already in depth order) and hydrates each body from its
     * lazy `reload-url` partial, in bounded parallel. A failed body fetch leaves that
     * comment's body empty rather than failing the page.
     */
    private suspend fun fetchComments(
        doc: Document,
        subreddit: String,
        postName: String,
        postTitle: String
    ): List<Child> = coroutineScope {
        val cards = doc.select("shreddit-comment")
        if (cards.isEmpty()) return@coroutineScope emptyList()
        val base = if (doc.location().isNotBlank()) doc.location() else "https://www.reddit.com"
        val semaphore = Semaphore(BODY_CONCURRENCY)
        cards.map { el ->
            async {
                semaphore.withPermit {
                    val reloadUrl = el.attr("reload-url")
                    val bodyHtml = if (reloadUrl.isNotBlank()) {
                        runCatching {
                            fetchPartial("$base$reloadUrl")?.let { Jsoup.parse(it).select(".md").firstOrNull()?.html() } ?: ""
                        }.getOrDefault("")
                    } else ""
                    buildCommentChild(
                        name = el.attr("thingId").ifBlank { return@withPermit null },
                        author = el.attr("author"),
                        depth = el.attr("depth").toIntOrNull() ?: 0,
                        createdAttr = el.attr("created"),
                        scoreAttr = el.attr("score"),
                        permalink = el.attr("permalink"),
                        linkId = el.attr("postId").ifBlank { postName },
                        subreddit = subreddit,
                        linkTitle = postTitle,
                        bodyHtml = bodyHtml
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun buildCommentChild(
        name: String,
        author: String,
        depth: Int,
        createdAttr: String,
        scoreAttr: String,
        permalink: String,
        linkId: String,
        subreddit: String?,
        linkTitle: String?,
        bodyHtml: String
    ): CommentChild {
        val created = if (createdAttr.isBlank()) 0L else parseRedditTimestamp(createdAttr)
        val score = scoreAttr.toIntOrNull() ?: 0
        val map = mutableMapOf<String, Any?>(
            "name" to name,
            "id" to name.removePrefix("t1_"),
            "author" to author,
            "created_utc" to created,
            "score" to score,
            "score_hidden" to scoreAttr.isBlank(),
            "depth" to depth,
            "permalink" to permalink,
            "body_html" to bodyHtml,
            "link_id" to linkId,
            "subreddit_name_prefixed" to (subreddit?.let { "r/$it" } ?: "")
        )
        if (linkTitle != null) map["link_title"] = linkTitle
        return runCatching { CommentChild(parseComment(ensureCommentDefaults(map))) }
            .getOrNull()
            ?: CommentChild(
                parseComment(
                    ensureCommentDefaults(
                        mapOf(
                            "name" to "t1_unknown",
                            "id" to "unknown",
                            "author" to author,
                            "created_utc" to 0L,
                            "score" to 0,
                            "body_html" to bodyHtml,
                            "link_id" to linkId
                        )
                    )
                )
            )
    }

    /** Parses the profile comment cards on a user's /comments/ page. */
    private suspend fun parseProfileComments(doc: Document, user: String): List<Child> = coroutineScope {
        val cards = doc.select("shreddit-profile-comment")
        if (cards.isEmpty()) return@coroutineScope emptyList()
        val base = if (doc.location().isNotBlank()) doc.location() else "https://www.reddit.com"
        val semaphore = Semaphore(BODY_CONCURRENCY)
        cards.map { el ->
            async {
                semaphore.withPermit {
                    val name = el.attr("comment-id").ifBlank { return@withPermit null }
                    val href = el.attr("href")
                    val ref = PERMALINK_REF.find(href)
                    val subreddit = ref?.groupValues?.getOrNull(1)
                    val postId = ref?.groupValues?.getOrNull(2)?.let { "t3_$it" }
                    val reloadUrl = el.attr("reload-url")
                    val bodyHtml = if (reloadUrl.isNotBlank()) {
                        runCatching {
                            fetchPartial("$base$reloadUrl")?.let { Jsoup.parse(it).select(".md").firstOrNull()?.html() } ?: ""
                        }.getOrDefault("")
                    } else ""
                    buildCommentChild(
                        name = name,
                        author = user,
                        depth = 0,
                        createdAttr = el.selectFirst("faceplate-timeago")?.attr("ts") ?: "",
                        scoreAttr = "",
                        permalink = href,
                        linkId = (postId ?: "").ifBlank { "t3_" },
                        subreddit = subreddit,
                        linkTitle = el.text().take(200).takeIf { it.isNotBlank() },
                        bodyHtml = bodyHtml
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    //endregion

    //region Search post parsing

    /**
     * Parses search post results. reddit.com serves the result list as a mix of legacy
     * `search-post-with-content-preview` blocks and newer `search-post-unit` blocks (global
     * and in-subreddit alike); both carry a `search-telemetry-tracker` whose
     * `data-faceplate-tracking-context` JSON holds the post id, title, author and subreddit.
     */
    private fun parseSearchBlocks(doc: Document): List<PostChild> =
        doc.select("[data-testid=search-post-with-content-preview], [data-testid=search-post-unit]")
            .mapNotNull { block ->
                val tracker = block.selectFirst("search-telemetry-tracker")?.attr("data-faceplate-tracking-context")
                    ?: ""
                val id = trackerField(block, "post", "id")
                    ?: REGEX_T3.find(tracker)?.value
                    ?: block.selectFirst("a[href*=/comments/]")?.attr("href")
                        ?.let { REGEX_POST_ID.find(it)?.let { m -> "t3_${m.groupValues[1]}" } }
                if (id == null) return@mapNotNull null
                val title = block.selectFirst("[data-testid=post-title-text]")?.text()?.trim()
                    ?: trackerPostTitle(block)
                    ?: ""
                val link = block.selectFirst("a[href*=/comments/]")?.attr("href") ?: ""
                val sub = trackerField(block, "subreddit", "name")
                    ?: PERMALINK_REF.find(link)?.groupValues?.getOrNull(1)
                    ?: "unknown"
                val author = trackerField(block, "profile", "name")
                    ?: REGEX_AUTHOR.find(tracker)?.groupValues?.getOrNull(1)
                    ?: ""
                val map = mutableMapOf<String, Any?>(
                    "name" to id,
                    "id" to id.removePrefix("t3_"),
                    "subreddit" to sub,
                    "subreddit_name_prefixed" to "r/$sub",
                    "title" to title,
                    "author" to author,
                    "created_utc" to 0L,
                    "permalink" to link,
                    "url" to (REGEX_URL.find(block.html())?.groupValues?.getOrNull(1) ?: link),
                    "domain" to "self.$sub",
                    "is_self" to false,
                    "score" to 0,
                    "num_comments" to 0,
                    "upvote_ratio" to 0.0,
                    "link_flair_richtext" to emptyList<Any>()
                )
                runCatching { PostChild(parsePost(ensurePostDefaults(map, sub))) }.getOrNull()
            }
            .distinctBy { it.data.name }

    /** Reads `."<field>" : "<value>"` (or the object form) out of a tracker block's JSON. */
    private fun trackerField(block: Element, group: String, field: String): String? {
        val tracker = block.selectFirst("search-telemetry-tracker")?.attr("data-faceplate-tracking-context")
            ?: return null
        val obj = Regex("\"$group\"\\s*:\\s*\\{[^}]*?\"$field\"\\s*:\\s*\"([^\"]*)\"")
            .find(tracker)?.groupValues?.get(1)
        if (obj != null) return obj
        return Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(tracker)?.groupValues?.get(1)
    }

    private fun trackerPostTitle(block: Element): String? {
        val tracker = block.selectFirst("search-telemetry-tracker")?.attr("data-faceplate-tracking-context")
            ?: return null
        return Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(tracker)?.groupValues?.get(1)
    }

    //endregion

    //region About builders

    private fun buildAboutData(sub: String, doc: Document): AboutData {
        val header = doc.selectFirst("shreddit-subreddit-header")
        val name = header?.attr("name")?.ifBlank { sub } ?: sub
        val displayName = header?.attr("display-name")?.ifBlank { name } ?: name
        val description = header?.attr("description")?.ifBlank { "" } ?: ""
        val weeklyActive = header?.attr("weekly-active-users")?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
        // The "Created …" line is the faceplate-timeago that sits next to the literal
        // "Created" text; picking the first timeago on the page grabs a tooltip instead.
        val created = doc.select("rpl-tooltip").firstOrNull { it.text().startsWith("Created ") }
            ?.selectFirst("faceplate-timeago")?.attr("ts")
            ?.let { runCatching { parseTimestampMillis(it) / 1000 }.getOrDefault(0L) }
            ?: 0L
        val icon = header?.select("img")?.firstOrNull { it.attr("src").contains("redditmedia.com") }?.attr("src")
            ?: header?.attr("icon-img")
        return AboutData(
            wikiEnabled = null,
            displayName = displayName,
            headerImg = header?.attr("header-img"),
            title = name,
            primaryColor = null,
            activeUserCount = weeklyActive,
            iconImg = icon,
            subscribers = parseMembersCount(doc),
            quarantine = null,
            publicDescriptionHtml = description,
            communityIcon = "",
            bannerBackgroundImage = "",
            keyColor = null,
            bannerBackgroundColor = null,
            over18 = null,
            descriptionHtml = description,
            url = "/r/$sub/",
            created = created
        )
    }

    private fun buildAboutUserData(user: String, doc: Document): AboutUserData {
        val karmaEl = doc.selectFirst("[data-testid=karma-number]")
        val totalKarma = karmaEl?.text()?.replace(",", "")?.toIntOrNull() ?: -1
        val tooltip = karmaEl?.parent()?.text() ?: ""
        val postKarma = Regex("(\\d[\\d,]*)\\s*post karma", RegexOption.IGNORE_CASE)
            .find(tooltip)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: -1
        val commentKarma = Regex("(\\d[\\d,]*)\\s*comment karma", RegexOption.IGNORE_CASE)
            .find(tooltip)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: -1
        val avatar = doc.selectFirst("[data-testid=profile-icon] img")?.attr("src")
            ?: doc.selectFirst("img.snoovatar")?.attr("src")
        return AboutUserData(
            isSuspended = false,
            isEmployee = false,
            subreddit = null,
            id = null,
            iconImg = avatar,
            linkKarma = postKarma,
            totalKarma = totalKarma,
            name = user,
            created = -1L,
            snoovatarImg = avatar,
            commentKarma = commentKarma
        )
    }

    private fun parseMembersCount(doc: Document): Int? {
        val text = doc.text()
        val m = Regex("(\\d[\\d,]*\\.?\\d*)\\s*([KM])?\\s*(?:members|subscribers)", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val num = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].uppercase()) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            else -> 1.0
        }
        return (num * mult).toInt()
    }

    //endregion

    //region Moshi model helpers (Reddit-JSON-shaped maps, same pipeline as before)

    private fun parsePost(map: Map<String, Any?>): PostData =
        postAdapter.fromJson(toJson(map)) ?: throw IOException("Invalid post data")

    private fun parseComment(map: Map<String, Any?>): CommentData =
        commentAdapter.fromJson(toJson(map)) ?: throw IOException("Invalid comment data")

    private fun toJson(value: Any?): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.jsonValue(value)
        writer.flush()
        return buffer.readUtf8()
    }

    /** Pre-fills the non-optional PostData fields a parsed object does not carry. */
    private fun ensurePostDefaults(
        map: Map<String, Any?>,
        subreddit: String?
    ): MutableMap<String, Any?> {
        val result = map.toMutableMap()
        if (subreddit != null) {
            result.putIfAbsent("subreddit_name_prefixed", "r/$subreddit")
            result.putIfAbsent("subreddit", subreddit)
        } else {
            result.putIfAbsent("subreddit", "")
        }
        result.putIfAbsent("title", "")
        result.putIfAbsent("name", "")
        result.putIfAbsent("link_flair_richtext", emptyList<Any>())
        result.putIfAbsent("total_awards_received", 0)
        result.putIfAbsent("is_original_content", false)
        result.putIfAbsent("score", 0)
        result.putIfAbsent("is_self", false)
        result.putIfAbsent("archived", false)
        result.putIfAbsent("over_18", false)
        result.putIfAbsent("all_awardings", emptyList<Any>())
        result.putIfAbsent("spoiler", false)
        result.putIfAbsent("locked", false)
        result.putIfAbsent("author", "")
        result.putIfAbsent("num_comments", 0)
        result.putIfAbsent("permalink", "")
        result.putIfAbsent("stickied", false)
        result.putIfAbsent("domain", "")
        result.putIfAbsent("url", "")
        result.putIfAbsent("created_utc", 0L)
        result.putIfAbsent("is_video", false)
        return result
    }

    /** Pre-fills the non-optional CommentData fields a parsed object does not carry. */
    private fun ensureCommentDefaults(map: Map<String, Any?>): MutableMap<String, Any?> {
        val result = map.toMutableMap()
        result.putIfAbsent("id", "")
        result.putIfAbsent("name", "")
        result.putIfAbsent("total_awards_received", 0)
        result.putIfAbsent("all_awardings", emptyList<Any>())
        result.putIfAbsent("body_html", "")
        result.putIfAbsent("edited", false)
        result.putIfAbsent("is_submitter", false)
        result.putIfAbsent("stickied", false)
        result.putIfAbsent("score_hidden", false)
        result.putIfAbsent("created_utc", 0L)
        result.putIfAbsent("controversiality", 0)
        result.putIfAbsent("score", 0)
        result.putIfAbsent("link_id", "")
        result.putIfAbsent("subreddit_name_prefixed", "")
        if (result["permalink"] == null) {
            val commentId = (result["name"] as? String)?.removePrefix("t1_")
            val sub = (result["subreddit_name_prefixed"] as? String)?.removePrefix("r/")
            val postId = (result["link_id"] as? String)?.removePrefix("t3_")
            result["permalink"] = if (commentId != null && sub != null && postId != null) {
                "/r/$sub/comments/$postId/$commentId"
            } else {
                ""
            }
        }
        return result
    }

    //endregion

    //region Misc

    /** Parses `2026-08-30T08:45:00.921000+0000` (and `…Z` / `…+00:00`) to epoch seconds. */
    private fun parseRedditTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        return runCatching {
            val cleaned = value
                .replace(Regex("\\.\\d+"), "")
                .replace(Regex("Z$"), "+0000")
                .replace(Regex("([+-])(\\d{2})(\\d{2})$"), "$1$2:$3")
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            sdf.parse(cleaned)?.time?.div(1000) ?: 0L
        }.getOrDefault(0L)
    }

    private fun parseTimestampMillis(value: String): Long = parseRedditTimestamp(value) * 1000

    /** Last post's fullname is the `after` cursor for the next SSR feed page. */
    private fun nextPostCursor(children: List<Child>): String? =
        children.filterIsInstance<PostChild>().lastOrNull()?.data?.name

    /** Last comment's fullname advances `/user/{u}/comments/?after=`. */
    private fun nextCommentCursor(children: List<Child>): String? =
        children.filterIsInstance<CommentChild>().lastOrNull()?.data?.name

    //endregion

    /** The challenge page's form: relative action, hidden token, and the 16-hex literal. */
    private data class ChallengeForm(val action: String, val token: String, val literal: String)

    companion object {
        private const val KIND_LISTING = "Listing"
        private const val POPULAR = "popular"
        private const val PAGE_SIZE = 25
        private const val SSR_RETRIES = 3
        private const val SSR_RETRY_BASE_MS = 1_000L
        // Multiredd fan-out: concurrency kept low enough that reddit.com's CF layer
        // does not challenge a burst of per-sub feed requests (73 subs at 4-wide
        // finished in seconds in live tests, 2026-09-01).
        private const val FANOUT_CONCURRENCY = 4
        // Random 0..FANOUT_JITTER_MS pre-delay per subreddit request so the burst
        // does not look like one identical pattern (CF shaping).
        private const val FANOUT_JITTER_MS = 700L
        // Separator for the per-sub cursor list threaded through the opaque cursor
        // string. Subreddit names never contain ';'; `after` t3_ ids are base36.
        private const val FANOUT_CURSOR_SEP = ";"

        /** Public alias of [FANOUT_CURSOR_SEP] for the feed coordinator. */
        const val FANOUT_CURSOR_SEPARATOR = FANOUT_CURSOR_SEP
        // The JS-challenge solve may need to repeat: a flagged network can answer a solved
        // resubmit with a *new* challenge (fresh token) before finally returning the feed.
        // The browser rides this flow; we allow a few rounds before giving up.
        private const val CHALLENGE_MAX_ROUNDS = 3
        private const val BODY_CONCURRENCY = 6
        // Real reddit.com pages are 300 KB+; a challenge page is ~8 KB.
        private const val CHALLENGE_MAX_SIZE = 64_000
        // `await (async e => e + e)("<16 hex>")` — the literal is doubled as the solution.
        private val JS_CHALLENGE_LITERAL =
            Regex("""\(\s*async\s+e\s*=>\s*e\s*\+\s*e\s*\)\s*\(\s*"([0-9a-fA-F]{16})"\s*\)""")
        // The embedded "load more" partial (its `src` is HTML-escaped; jsoup decodes it).
        private val CONTINUATION_SELECTOR = "faceplate-partial[slot=load-after]"

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        private val PERMALINK_REF = Regex("/r([^/]+)/comments/([a-z0-9]+)/")
        private val REGEX_T3 = Regex("t3_[a-z0-9]+")
        private val REGEX_POST_ID = Regex("/comments/([a-z0-9]+)/")
        private val REGEX_AUTHOR = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"")
        private val REGEX_URL = Regex("href=\"(https?://[^\"]+)\"")
    }
}
