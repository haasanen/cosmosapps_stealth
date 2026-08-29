package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.ArcticApi
import com.cosmos.unreddit.data.remote.api.reddit.RedditRssApi
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutData
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentData
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.di.DispatchersModule.IoDispatcher
import com.cosmos.unreddit.di.NetworkModule.Arctic
import com.cosmos.unreddit.di.NetworkModule.RedditMoshi
import com.cosmos.unreddit.di.NetworkModule.Rss
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Reddit (official)" backend: reads the **live** www.reddit.com Atom (RSS) feeds and
 * falls back to the [ArcticShiftSource] archive for everything a feed cannot carry.
 *
 * Why this design (verified live 2026-08-29):
 * - the `.json` API and the HTML pages of reddit.com require a login (403 / login wall);
 * - the `…/.rss` Atom feeds are the only reddit.com endpoints that still answer
 *   anonymously and return full post data,
 * - a feed carries at most ~25-30 entries and has **no pagination cursor**.
 *
 * Behaviour:
 * - First page of a single-subreddit feed (HOT/NEW/TOP/RISING), the home feed
 *   (`r/popular`), in-subreddit search, global search and user post/comment lists
 *   come from the live feed.
 * - Every subsequent page continues from the Arctic Shift archive, using the
 *   feed's oldest (desc) / newest (asc) `created_utc` as the exclusive epoch cursor
 *   (Arctic's cursor semantics, verified), so pages never overlap.
 * - Posts read from the feed are re-fetched by id from Arctic to restore the fields
 *   a feed lacks (score, comment count, NSFW flag, media, url). If the archive has
 *   not indexed a post yet (it is brand new) or the call fails (rate limit), the
 *   feed-derived object is kept as-is.
 * - Multi-subreddit lists skip the live feed (a 100-sub feed would be 100 anonymous
 *   requests, which hits the rate limit) and use the archive directly.
 *
 * Limitations (documented, not bugs):
 * - no live feed for CONTROVERSIAL/OLD/RELEVANCE sorts or multi-sub lists (archive),
 * - scores are unknown for brand-new posts until Arctic indexes them (shown as 0),
 * - in-subreddit search: page 1 is live (relevance), pages 2+ come from the archive
 *   in time order,
 * - post detail, comment trees, subreddit/user info and scoped searches are served
 *   by the archive (delegated to [ArcticShiftSource]).
 */
@Singleton
class RedditOfficialSource @Inject constructor(
    @Rss private val rssApi: RedditRssApi,
    @Arctic private val arcticApi: ArcticApi,
    private val arcticShiftSource: ArcticShiftSource,
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
        val ascending = sort == Sort.OLD
        val children = when {
            // Continuation pages always come from the archive (a feed has no cursor)
            after != null -> if (subreddit.contains("+")) {
                fetchMergedArchive(subreddit.split("+"), sort, timeSorting, after)
            } else {
                archivePostPage(
                    subreddit.takeUnless { it.equals(POPULAR, ignoreCase = true) },
                    null, null, sort, timeSorting, after
                )
            }

            // Multi-sub lists use the archive directly (see class KDoc for the reason)
            subreddit.contains("+") -> fetchMergedArchive(
                subreddit.split("+"), sort, timeSorting, null
            )

            // Live first page for the sorts that exist as feeds; any live failure
            // (rate limit, block, missing feed variant) degrades to the archive
            else -> runCatching {
                when (sort) {
                    Sort.HOT -> parseFeedPosts(
                        fetchFeed { rssApi.subredditFeed(rssSubreddit(subreddit)) }
                    )

                    Sort.NEW -> parseFeedPosts(
                        fetchFeed { rssApi.subredditNewFeed(rssSubreddit(subreddit)) }
                    )

                    Sort.TOP -> parseFeedPosts(
                        fetchFeed {
                            rssApi.subredditTopFeed(
                                rssSubreddit(subreddit),
                                timeSorting?.type ?: "hour"
                            )
                        }
                    )

                    Sort.RISING -> parseFeedPosts(
                        fetchFeed { rssApi.subredditRisingFeed(rssSubreddit(subreddit)) }
                    )

                    // No live feed for these sorts; the archive orders by time
                    else -> null
                }
            }.getOrNull() ?: runCatching {
                archivePostPage(
                    subreddit.takeUnless { it.equals(POPULAR, ignoreCase = true) },
                    null, null, sort, timeSorting, null
                )
            }.getOrDefault(emptyList())
        }
        Listing(
            KIND_LISTING,
            ListingData(null, null, children, nextCursor(children, ascending), null)
        )
    }

    override suspend fun getSubredditInfo(subreddit: String): Child =
        arcticShiftSource.getSubredditInfo(subreddit)

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val ascending = sort == Sort.OLD
        val children = if (after != null) {
            archivePostPage(subreddit, null, query, sort, timeSorting, after)
        } else {
            // Live relevance-ordered page 1; degrades to the archive on any failure
            runCatching {
                parseFeedPosts(
                    fetchFeed { rssApi.subredditSearchFeed(rssSubreddit(subreddit), query) }
                )
            }.getOrNull() ?: runCatching {
                archivePostPage(subreddit, null, query, sort, timeSorting, null)
            }.getOrDefault(emptyList())
        }
        Listing(
            KIND_LISTING,
            ListingData(null, null, children, nextCursor(children, ascending), null)
        )
    }

    //endregion

    // Delegated to the archive: the feed channel has no post-detail or tree endpoint
    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> =
        arcticShiftSource.getPost(permalink, limit, sort)

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren =
        arcticShiftSource.getMoreChildren(children, linkId)

    //region User

    override suspend fun getUserInfo(user: String): Child =
        arcticShiftSource.getUserInfo(user)

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val ascending = sort == Sort.OLD
        val children = if (after != null) {
            arcticShiftSource.getUserPosts(user, sort, timeSorting, after).data.children
        } else {
            // /user/{u}/new/.rss lists the user's submissions (t3 entries; parseFeedPosts
            // drops t1 comment entries); degrades to the archive on any failure
            runCatching {
                parseFeedPosts(fetchFeed { rssApi.userPostsFeed(user) })
            }.getOrNull()
                ?: arcticShiftSource.getUserPosts(user, sort, timeSorting, null).data.children
        }
        Listing(
            KIND_LISTING,
            ListingData(null, null, children, nextCursor(children, ascending), null)
        )
    }

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val ascending = sort == Sort.OLD
        val children = if (after != null) {
            arcticShiftSource.getUserComments(user, sort, timeSorting, after).data.children
        } else {
            // /user/{u}/.rss mixes comments and submissions; keep t1 entries only,
            // degrading to the archive on any failure
            runCatching {
                parseFeedComments(fetchFeed { rssApi.userCommentsFeed(user) })
            }.getOrNull()
                ?: arcticShiftSource.getUserComments(user, sort, timeSorting, null).data.children
        }
        Listing(
            KIND_LISTING,
            ListingData(null, null, children, nextCursor(children, ascending), null)
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
        val ascending = sort == Sort.OLD
        val children = if (after != null) {
            // The live feed is a single page and Arctic's global full-text search is
            // not supported (it requires a subreddit/author scope), so continuation
            // terminates here with an empty page
            emptyList<PostChild>()
        } else {
            parseFeedPosts(fetchFeed { rssApi.globalSearchFeed(query) })
        }
        Listing(
            KIND_LISTING,
            ListingData(null, null, children, nextCursor(children, ascending), null)
        )
    }

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = arcticShiftSource.searchUser(query, sort, timeSorting, after)

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = arcticShiftSource.searchSubreddit(query, sort, timeSorting, after)

    //endregion

    //region Feed parsing

    /**
     * Fetches a feed body, retrying once after a delay when the response is not a
     * feed (rate-limit / challenge page). The feeds have no pagination, so a single
     * retry is all that makes sense.
     */
    private suspend fun fetchFeed(block: suspend () -> ResponseBody): String {
        val first = runCatching { block().string() }.getOrNull()
        if (isFeed(first)) return first!!
        delay(RETRY_DELAY_MS)
        val second = runCatching { block().string() }.getOrNull()
        if (isFeed(second)) return second!!
        throw IOException("reddit.com RSS feed unavailable (blocked or rate limited)")
    }

    private fun isFeed(body: String?): Boolean =
        body != null && body.contains("<feed", ignoreCase = true) &&
            body.contains("<entry", ignoreCase = true)

    private fun rssSubreddit(subreddit: String): String =
        if (subreddit.equals(POPULAR, ignoreCase = true)) POPULAR else subreddit

    /** Parses a post feed into [PostChild]s and re-fetches full objects from the archive. */
    private suspend fun parseFeedPosts(xml: String): List<PostChild> {
        val entries = splitEntries(xml)
            .filter { idOf(it)?.startsWith("t3_") == true }
            .mapNotNull { entry -> feedToPostMap(entry)?.let { it.toMutableMap() } }
        if (entries.isEmpty()) return emptyList()
        val posts = entries.map { map ->
            runCatching { PostChild(parsePost(ensurePostDefaults(map, str(map, "subreddit")))) }
                .getOrNull()
        }.filterNotNull()
        return enrichPosts(posts)
    }

    /** Parses a comment feed into [CommentChild]s. */
    private fun parseFeedComments(xml: String): List<CommentChild> =
        splitEntries(xml)
            .mapNotNull { entry -> feedToCommentMap(entry)?.let { it.toMutableMap() } }
            .mapNotNull { map ->
                runCatching { CommentChild(parseComment(ensureCommentDefaults(map))) }
                    .getOrNull()
            }

    //region Feed field extraction

    private fun splitEntries(xml: String): List<String> =
        ENTRY_REGEX.findAll(xml).map { it.groupValues[1] }.toList()

    private fun idOf(entry: String): String? = TAG_ID.find(entry)?.groupValues?.get(1)

    private fun titleOf(entry: String): String =
        unescapeXml(TAG_TITLE.find(entry)?.groupValues?.get(1) ?: "")

    private fun dateOf(entry: String): Long? =
        (TAG_PUBLISHED.find(entry) ?: TAG_UPDATED.find(entry))
            ?.groupValues?.get(1)
            ?.let(::parseFeedDate)

    private fun contentOf(entry: String): String =
        unescapeXml(TAG_CONTENT.find(entry)?.groupValues?.get(1) ?: "")

    private fun linkOf(entry: String): String? =
        TAG_LINK.find(entry)?.groupValues?.get(1)

    /** Builds the Reddit-JSON-shaped post object a feed entry provides. */
    private fun feedToPostMap(entry: String): Map<String, Any?>? {
        val id = idOf(entry) ?: return null
        if (!id.startsWith("t3_")) return null
        val href = linkOf(entry)?.let { it.substringAfter("www.reddit.com") } ?: return null
        val sub = PERMALINK_SUB.find(href)?.groupValues?.get(1) ?: return null
        val postId = PERMALINK_ID.find(href)?.groupValues?.get(1) ?: return null
        val content = contentOf(entry)
        val author = AUTHOR_LINK.find(content)?.groupValues?.get(1)
        val selftext = MD_DIV.find(content)?.groupValues?.get(1)
        val linkUrl = CONTENT_LINK.find(content)?.groupValues?.get(1)
        val thumbnail = IMG_SRC.find(content)?.groupValues?.get(1)
        val created = dateOf(entry) ?: return null

        val isSelf = selftext != null
        return mapOf(
            "id" to postId,
            "name" to id,
            "subreddit" to sub,
            "subreddit_name_prefixed" to "r/$sub",
            "title" to titleOf(entry),
            "author" to (author ?: ""),
            "created_utc" to created,
            "permalink" to "/r/$sub/comments/$postId/",
            "url" to (if (isSelf) href else (linkUrl ?: href)),
            "is_self" to isSelf,
            "selftext_html" to selftext,
            "domain" to (if (isSelf) "self.$sub" else (URL_DOMAIN(linkUrl) ?: "reddit.com"))
        ).let { base ->
            base.toMutableMap().also {
                if (thumbnail != null) it["thumbnail"] = thumbnail
            }.toMap()
        }
    }

    /** Builds the Reddit-JSON-shaped comment object a feed entry provides. */
    private fun feedToCommentMap(entry: String): Map<String, Any?>? {
        val id = idOf(entry) ?: return null
        if (!id.startsWith("t1_")) return null
        val href = linkOf(entry)?.let { it.substringAfter("www.reddit.com") } ?: return null
        val sub = PERMALINK_SUB.find(href)?.groupValues?.get(1) ?: return null
        val postId = PERMALINK_ID.find(href)?.groupValues?.get(1) ?: return null
        val body = MD_DIV.find(contentOf(entry))?.groupValues?.get(1) ?: return null
        val author = COMMENT_AUTHOR.find(titleOf(entry))?.groupValues?.get(1) ?: ""
        val created = dateOf(entry) ?: return null
        val commentId = id.removePrefix("t1_")

        return mapOf(
            "id" to commentId,
            "name" to id,
            "author" to author,
            "created_utc" to created,
            "subreddit_name_prefixed" to "r/$sub",
            "link_id" to "t3_$postId",
            "link_permalink" to "/r/$sub/comments/$postId/",
            "link_title" to titleOf(entry).removePrefix("/u/$author on "),
            "body_html" to body,
            "permalink" to "/r/$sub/comments/$postId/$commentId"
        )
    }

    //endregion

    //region Archive helpers (same semantics as ArcticShiftSource, verified)

    private suspend fun archivePostPage(
        subreddit: String?,
        author: String?,
        query: String?,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): List<PostChild> {
        // Both cursors are exclusive on the Arctic API (verified): `before` bounds desc
        // pages, `after` bounds asc pages, so a page never repeats the cursor item.
        val cursor = after?.toLongOrNull()
        val ascending = sort == Sort.OLD
        val raw = arcticApi.searchPosts(
            subreddit = subreddit,
            author = author,
            query = query,
            sort = if (ascending) "asc" else "desc",
            before = if (ascending) null else cursor,
            after = if (ascending) cursor else null,
            limit = PAGE_LIMIT
        )
        return jsonList(raw.string()).map { object_ ->
            PostChild(parsePost(ensurePostDefaults(object_, subreddit)))
        }
    }

    private suspend fun fetchMergedArchive(
        subreddits: List<String>,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): List<PostChild> = coroutineScope {
        val pages = subreddits.map { name ->
            async {
                runCatching {
                    archivePostPage(name, null, null, sort, timeSorting, after)
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        pages.flatten()
            .sortedByDescending { it.data.created }
            .distinctBy { it.data.name }
    }

    /**
     * Re-fetches feed posts by id from the archive to restore the fields a feed lacks
     * (score, comment count, NSFW, media, url). Falls back to the feed-derived object
     * when the archive does not have the post yet (brand new) or the call fails
     * (rate limit).
     */
    private suspend fun enrichPosts(posts: List<PostChild>): List<PostChild> {
        if (posts.isEmpty()) return posts
        return runCatching {
            val ids = posts.joinToString(",") { it.data.name }
            val raw = arcticApi.getPostByIds(ids)
            // The response body is consumable exactly once - parse it into a map first
            val byName = jsonList(raw.string())
                .mapNotNull { object_ -> str(object_, "name")?.let { name -> name to object_ } }
                .toMap()
            posts.map { post ->
                byName[post.data.name]
                    ?.let { object_ ->
                        runCatching {
                            PostChild(parsePost(ensurePostDefaults(object_, post.data.subreddit)))
                        }.getOrNull()
                    }
                    ?: post
            }
        }.getOrElse { posts }
    }

    /** Emits the epoch cursor for the next archive page (oldest for desc, newest for asc). */
    private fun nextCursor(children: List<Child>, ascending: Boolean = false): String? {
        val timestamps = children.mapNotNull { child ->
            when (child) {
                is PostChild -> child.data.created
                is CommentChild -> child.data.created
                else -> null
            }
        }
        return (if (ascending) timestamps.maxOrNull() else timestamps.minOrNull())?.toString()
    }

    /** Extracts the `data` array of a `{"data": [...]}` Arctic response as raw maps. */
    private fun jsonList(body: String): List<Map<String, Any?>> {
        val top = readObject(body) ?: return emptyList()
        if (top["data"] == null) {
            val error = top["error"]
            if (error is String && error.isNotEmpty()) throw IOException("Arctic error: $error")
            return emptyList()
        }
        return (top["data"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { it as? Map<String, Any?> }
            ?: emptyList()
    }

    private fun readObject(body: String): Map<String, Any?>? {
        return try {
            val reader = JsonReader.of(Buffer().writeUtf8(body))
            reader.isLenient = true
            // Moshi 1.x readJsonValue() returns mutable LinkedHashTreeMaps and ArrayLists
            reader.readJsonValue() as? Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    private fun toJson(value: Any?): String {
        val buffer = Buffer()
        val writer = JsonWriter.of(buffer)
        writer.jsonValue(value)
        writer.flush()
        return buffer.readUtf8()
    }

    private fun str(map: Map<*, *>?, name: String): String? =
        (map?.get(name) as? String)?.takeIf { it.isNotEmpty() }

    private fun parsePost(map: Map<String, Any?>): PostData =
        postAdapter.fromJson(toJson(map)) ?: throw IOException("Invalid post data")

    private fun parseComment(map: Map<String, Any?>): CommentData =
        commentAdapter.fromJson(toJson(map)) ?: throw IOException("Invalid comment data")

    /** Pre-fills the non-optional PostData fields a feed-derived object does not carry. */
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

    /** Pre-fills the non-optional CommentData fields a feed-derived object does not carry. */
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

    private fun parseFeedDate(value: String): Long? {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                return sdf.parse(value)?.time?.div(1000)
            } catch (_: Exception) {
                // try the next format
            }
        }
        return null
    }

    /** Decodes the XML entities reddit's Atom feeds use (including numeric ones). */
    private fun unescapeXml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
        }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: ""
        }

    private fun URL_DOMAIN(url: String?): String? =
        url?.let { runCatching { it.substringBefore('/').substringAfter("://") }.getOrNull() }

    //endregion

    companion object {
        private const val KIND_LISTING = "Listing"
        private const val POPULAR = "popular"
        private const val PAGE_LIMIT = 100
        private const val RETRY_DELAY_MS = 10_000L

        private val ENTRY_REGEX = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
        private val TAG_ID = Regex("<id>(.*?)</id>")
        private val TAG_TITLE = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
        private val TAG_PUBLISHED = Regex("<published>(.*?)</published>")
        private val TAG_UPDATED = Regex("<updated>(.*?)</updated>")
        private val TAG_LINK = Regex("<link[^>]*href=\"([^\"]+)\"")
        private val TAG_CONTENT = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
        private val PERMALINK_SUB = Regex("/r/([^/]+)/comments/")
        private val PERMALINK_ID = Regex("/comments/([a-z0-9]+)/")
        private val AUTHOR_LINK = Regex("/user/([A-Za-z0-9_-]+)\">")
        private val COMMENT_AUTHOR = Regex("^/u/([A-Za-z0-9_-]+)\\s+on\\s")
        private val MD_DIV = Regex("<div class=\"md\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
        private val CONTENT_LINK = Regex("<span><a href=\"([^\"]+)\">\\[link\\]</a></span>")
        private val IMG_SRC = Regex("<img src=\"([^\"]+)\"")
    }
}
