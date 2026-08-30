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
 * Contract notes (verified against live reddit.com captures):
 *  - Feeds: `https://www.reddit.com/r/{sub}/{sort}/?count=25&after=t3_xxx` renders
 *    `shreddit-post` cards; `shreddit-ad-post` (ads) is a different tag and is never
 *    selected, so ads drop out. Deep pagination is the main-page `after=t3_<fullname>`
 *    param (verified: clean 10-unique chain, no overlap).
 *  - Post detail: `https://www.reddit.com/r/{sub}/comments/{id}/{slug}/` renders the OP
 *    (`shreddit-post view-context="CommentsPage"`) plus ~25 `shreddit-comment` cards in
 *    depth order. Comment bodies are lazy: each card carries a `reload-url`
 *    (`/svc/shreddit/comment/{t1_id}?…`) that returns the card with its markdown body, so
 *    the bodies are fetched in bounded parallel. A slugless detail URL is a JS shell, so
 *    the feed-supplied slug permalink is required.
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
        val doc = Jsoup.parse(fetchPage(url))
        val children = parsePostCards(doc)
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
        val children = parsePostCards(doc)
        Listing(KIND_LISTING, ListingData(null, children.size, children, nextPostCursor(children), null))
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
        val url = userPageUrl(user, sort, after)
        val doc = Jsoup.parse(fetchPage(url))
        val children = parsePostCards(doc)
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
        Listing(KIND_LISTING, ListingData(null, children.size, children, null, null))
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
        val children = parseSearchPostBlocks(doc)
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
        val children = doc.select("a[href^=/user/]").mapNotNull { a ->
            val name = a.attr("href")
                .removePrefix("/user/")
                .trimEnd('/')
                .takeIf { it.isNotEmpty() && !it.contains('/') }
                ?: return@mapNotNull null
            AboutUserChild(
                AboutUserData(
                    subreddit = null,
                    id = null,
                    iconImg = a.selectFirst("img")?.attr("src"),
                    name = name,
                    snoovatarImg = null
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
            val tracker = block.selectFirst("search-telemetry-tracker")?.attr("data-faceplate-tracking-context")
                ?: ""
            val name = TRACKER_SUB_NAME.find(tracker)?.groupValues?.get(1)
                ?: block.selectFirst("a[href^=/r/]")?.attr("href")?.removePrefix("/r/")?.trimEnd('/')
                ?: block.text().removePrefix("r/").trim().takeIf { it.isNotEmpty() }
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
        }
        Listing(KIND_LISTING, ListingData(null, children.size, children, null, null))
    }

    //endregion

    //region HTTP

    /** Fetches a full page, retrying on challenge/failure, then throws if unusable. */
    private suspend fun fetchPage(url: String): String {
        for (attempt in 0 until SSR_RETRIES) {
            val body = doGet(url, "GET", forPartial = false)
            if (body != null && !isCloudflareChallenge(body)) return body
            if (attempt < SSR_RETRIES - 1) delay(SSR_RETRY_BASE_MS * (1L shl attempt))
        }
        throw IOException(
            "reddit.com did not return a usable page (Cloudflare challenge or rate limit). Please try again."
        )
    }

    /**
     * Fetches a lazy partial (comment body). Tries GET then POST; returns the body or null.
     * A null body means the caller degrades gracefully (e.g. an empty comment body) instead
     * of failing the whole detail page.
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
            append("/popular/")
        } else {
            append("/r/").append(subreddit).append("/").append(feedSortPath(sort)).append("/")
        }
        append("?count=").append(PAGE_SIZE)
        if (sort == Sort.TOP || sort == Sort.CONTROVERSIAL) {
            timeSorting?.type?.let { append("&t=").append(it) }
        }
        if (!after.isNullOrBlank()) append("&after=").append(after)
    }

    private fun userPageUrl(user: String, sort: Sort, after: String?): String = buildString {
        append("https://www.reddit.com/user/").append(user).append("/").append(feedSortPath(sort))
            .append("/?count=").append(PAGE_SIZE)
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

    private fun parseSearchPostBlocks(doc: Document): List<PostChild> =
        doc.select("[data-testid=search-post-with-content-preview]").mapNotNull { block ->
            val tracker = block.selectFirst("search-telemetry-tracker")?.attr("data-faceplate-tracking-context")
                ?: ""
            val title = block.selectFirst("[data-testid=post-title-text]")?.text()
                ?: block.selectFirst(".post-title, [data-testid=post-title]")?.text()
                ?: ""
            val link = block.selectFirst("a[href*=/comments/]")?.attr("href") ?: ""
            val id = REGEX_T3.find(tracker)?.value
                ?: REGEX_POST_ID.find(link)?.let { "t3_${it.groupValues[1]}" }
            if (id == null) return@mapNotNull null
            val sub = TRACKER_SUB_NAME.find(tracker)?.groupValues?.get(1)
                ?: PERMALINK_REF.find(link)?.groupValues?.getOrNull(1)
                ?: "unknown"
            val created = 0L
            val map = mutableMapOf<String, Any?>(
                "name" to id,
                "id" to id.removePrefix("t3_"),
                "subreddit" to sub,
                "subreddit_name_prefixed" to "r/$sub",
                "title" to title,
                "author" to (REGEX_AUTHOR.find(tracker)?.groupValues?.get(1) ?: ""),
                "created_utc" to created,
                "permalink" to link,
                "url" to (REGEX_URL.find(block.html())?.groupValues?.get(1) ?: link),
                "domain" to "self.$sub",
                "is_self" to false,
                "score" to 0,
                "num_comments" to 0,
                "upvote_ratio" to 0.0,
                "link_flair_richtext" to emptyList<Any>()
            )
            runCatching { PostChild(parsePost(ensurePostDefaults(map, sub))) }.getOrNull()
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
        val created = doc.selectFirst("faceplate-timeago")?.attr("ts")
            ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() / 1000 }.getOrDefault(0L) }
            ?: 0L
        return AboutData(
            wikiEnabled = null,
            displayName = displayName,
            headerImg = header?.attr("header-img"),
            title = name,
            primaryColor = null,
            activeUserCount = weeklyActive,
            iconImg = header?.attr("icon-img"),
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

    /** Last post's fullname is the `after` cursor for the next SSR feed page. */
    private fun nextPostCursor(children: List<Child>): String? =
        children.filterIsInstance<PostChild>().lastOrNull()?.data?.name

    //endregion

    companion object {
        private const val KIND_LISTING = "Listing"
        private const val POPULAR = "popular"
        private const val PAGE_SIZE = 25
        private const val SSR_RETRIES = 3
        private const val SSR_RETRY_BASE_MS = 1_000L
        private const val BODY_CONCURRENCY = 6

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        private val PERMALINK_REF = Regex("/r/([^/]+)/comments/([a-z0-9]+)/")
        private val REGEX_T3 = Regex("t3_[a-z0-9]+")
        private val REGEX_POST_ID = Regex("/comments/([a-z0-9]+)/")
        private val REGEX_AUTHOR = Regex("\"author\"\\s*:\\s*\"([^\"]+)\"")
        private val REGEX_URL = Regex("href=\"(https?://[^\"]+)\"")
        private val TRACKER_SUB_NAME = Regex("\"subreddit\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"")
    }
}
