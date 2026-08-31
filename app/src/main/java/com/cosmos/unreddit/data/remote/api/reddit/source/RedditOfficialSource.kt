package com.cosmos.unreddit.data.remote.api.reddit.source

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
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    override suspend fun getSubredditInfo(subreddit: String): Child = withContext(ioDispatcher) {
        val doc = Jsoup.parse(fetchPage("https://www.reddit.com/r/$subreddit/"))
        AboutChild(buildAboutData(subreddit, doc))
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
            val doc = Jsoup.parse(fetchPage(url))

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

    private suspend fun doGet(url: String, method: String, forPartial: Boolean): String? =
        runCatching {
            val req = newRequest(url, method, forPartial)
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()

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

    /** Selects the real post cards, dropping ads (a different tag) by construction. */
    private fun parsePostCards(doc: Document): List<PostChild> =
        doc.select("shreddit-post").mapNotNull { postChildFromElement(it) }

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
        el.select("img").firstOrNull {
            val src = it.attr("src")
            src.contains("redd.it") || src.contains("redditmedia")
        }?.attr("src")?.let { map["thumbnail"] = it }

        return runCatching { PostChild(parsePost(ensurePostDefaults(map, sub))) }.getOrNull()
    }

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
