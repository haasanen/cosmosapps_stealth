package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.ArcticApi
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutData
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserData
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentChild
import com.cosmos.unreddit.data.remote.api.reddit.model.CommentData
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.ListingData
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.remote.api.reddit.model.PostChild
import com.cosmos.unreddit.data.remote.api.reddit.model.PostData
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.di.DispatchersModule.IoDispatcher
import com.cosmos.unreddit.di.NetworkModule.Arctic
import com.cosmos.unreddit.di.NetworkModule.RedditMoshi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backend based on the [Arctic Shift](https://github.com/photon-reddit/arctic_shift) archive.
 *
 * Arctic stores native Reddit JSON objects:
 * - search/ids endpoints return **bare** objects in `{"data": [...]}` (no `kind`/`data` wrapper),
 * - the comments tree endpoint returns the standard `{"kind": "t1", "data": {...}}` nodes with
 *   nested `replies` listings.
 *
 * This source wraps the bare objects into the `Child` shape the app's Moshi models expect and
 * pre-fills the fields the generated adapters require but Arctic may omit (e.g.
 * `subreddit_name_prefixed`, `total_awards_received`).
 *
 * Pagination uses the `before` epoch cursor, which is **exclusive** on this API, so pages do
 * not overlap and no duplicate filtering is needed.
 *
 * Limitations (Arctic has no such endpoints or data):
 * - only time ordering is available (`sort=asc|desc`); HOT/TOP/RISING/... collapse to
 *   newest-first, OLD to oldest-first,
 * - the home feed ("popular") maps to a global post search,
 * - global full-text search is not supported (Arctic requires a subreddit/author scope),
 * - no more-children expansion (the tree is requested fully expanded),
 * - multi-subreddit queries are requested per-subreddit and merged by creation date,
 * - the user list returns aggregate data (no `created_utc`, no flair).
 */
@Singleton
class ArcticShiftSource @Inject constructor(
    @Arctic private val arcticApi: ArcticApi,
    @RedditMoshi moshi: Moshi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : BaseRedditSource {

    private val postDataAdapter: JsonAdapter<PostData> = moshi.adapter(PostData::class.java)
    private val commentDataAdapter: JsonAdapter<CommentData> = moshi.adapter(CommentData::class.java)
    private val aboutDataAdapter: JsonAdapter<AboutData> = moshi.adapter(AboutData::class.java)

    //region Subreddit

    override suspend fun getSubreddit(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        // "popular" is the app's home-feed sentinel: Arctic has no such feed endpoint, so it
        // maps to a global post search. (An explicit multi-sub query containing "popular" keeps
        // the literal subreddit.)
        val children = if (subreddit.contains("+")) {
            fetchMergedPosts(subreddit.split("+"), timeSorting, after)
        } else {
            val sub = subreddit.takeUnless { it.equals(POPULAR, ignoreCase = true) }
            fetchPostPage(sub, null, null, sort, timeSorting, after, LIMIT)
        }
        Listing(KIND_LISTING, ListingData(null, null, children, nextPostCursor(children), null))
    }

    override suspend fun getSubredditInfo(subreddit: String): Child = withContext(ioDispatcher) {
        val fullname = resolveSubredditFullname(subreddit)
        val raw = arcticApi.getSubredditByIds(fullname)
        val object_ = jsonList(raw.string()).firstOrNull()
            ?: throw IOException("Subreddit '$subreddit' not found")
        AboutChild(parseAbout(ensureAboutDefaults(object_)))
    }

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val children = fetchPostPage(subreddit, null, query, sort, timeSorting, after, LIMIT)
        Listing(KIND_LISTING, ListingData(null, null, children, nextPostCursor(children), null))
    }

    //endregion

    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> =
        withContext(ioDispatcher) {
            val (subreddit, postId) = parsePermalink(permalink)
            val raw = arcticApi.getPostByIds("t3_$postId")
            val object_ = jsonList(raw.string()).firstOrNull()
                ?: throw IOException("Post not found: $permalink")
            val postData = parsePost(ensurePostDefaults(object_, subreddit))

            // Exactly two listings: [0] = the post, [1] = the comment tree (the app's contract)
            val postListing = Listing(
                KIND_LISTING,
                ListingData(null, null, listOf(PostChild(postData)), null, null)
            )

            val comments = fetchCommentTree(postId, postData.subreddit)
            val commentsListing = Listing(
                KIND_LISTING,
                ListingData(null, comments.size, comments, null, null)
            )

            listOf(postListing, commentsListing)
        }

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren =
        withContext(ioDispatcher) {
            // The comment tree is requested fully expanded and Arctic has no more-children endpoint.
            throw IOException("More children are not supported by this source")
        }

    //region User

    override suspend fun getUserInfo(user: String): Child = withContext(ioDispatcher) {
        val raw = arcticApi.searchUsers(author = user, limit = 1)
        val object_ = jsonList(raw.string())
            .firstOrNull { str(it, "author")?.equals(user, ignoreCase = true) == true }
            ?: throw IOException("User '$user' not found")
        AboutUserChild(buildAboutUserData(object_, user))
    }

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val children = fetchPostPage(null, user, null, sort, timeSorting, after, LIMIT)
        Listing(KIND_LISTING, ListingData(null, null, children, nextPostCursor(children), null))
    }

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val cursor = parseCursor(after)
        val raw = arcticApi.searchComments(
            author = user,
            sort = sortOrder(sort, timeSorting),
            before = cursor,
            limit = LIMIT
        )
        val children = jsonList(raw.string()).map { object_ ->
            CommentChild(parseComment(ensureCommentDefaults(object_, null, null)))
        }
        Listing(KIND_LISTING, ListingData(null, null, children, nextCommentCursor(children), null))
    }

    //endregion

    //region Search

    override suspend fun searchPost(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        // Arctic full-text search requires a subreddit or author scope; a global query is not
        // supported by the API, so there is nothing to return.
        Listing(KIND_LISTING, ListingData(null, null, emptyList(), null, null))
    }

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        // Arctic's user search is a prefix search on aggregate data; there is no cursor.
        val raw = arcticApi.searchUsers(authorPrefix = query, limit = 50)
        val children = jsonList(raw.string()).map { object_ ->
            AboutUserChild(buildAboutUserData(object_, query))
        }
        Listing(KIND_LISTING, ListingData(null, null, children, null, null))
    }

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withContext(ioDispatcher) {
        val raw = arcticApi.searchSubreddits(prefix = query)
        val children = jsonList(raw.string()).map { object_ ->
            AboutChild(parseAbout(ensureAboutDefaults(object_)))
        }
        Listing(KIND_LISTING, ListingData(null, null, children, null, null))
    }

    //endregion

    //region Internals

    private suspend fun fetchPostPage(
        subreddit: String?,
        author: String?,
        query: String?,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?,
        limit: Int
    ): List<PostChild> {
        val cursor = parseCursor(after)
        val raw = arcticApi.searchPosts(
            subreddit = subreddit,
            author = author,
            query = query,
            sort = sortOrder(sort, timeSorting),
            before = cursor,
            limit = limit
        )
        return jsonList(raw.string()).map { object_ ->
            PostChild(parsePost(ensurePostDefaults(object_, subreddit)))
        }
    }

    private suspend fun fetchMergedPosts(
        subreddits: List<String>,
        timeSorting: TimeSorting?,
        after: String?
    ): List<PostChild> = coroutineScope {
        // A single "a+b" query needs one request per subreddit; merge by creation date.
        // `before` is exclusive on this API, so no dedup against the cursor is required.
        val pages = subreddits.map { name ->
            async {
                runCatching { fetchPostPage(name, null, null, null, timeSorting, after, LIMIT) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll()
        pages.flatten()
            .sortedByDescending { it.data.created }
            .distinctBy { it.data.name }
    }

    private suspend fun fetchCommentTree(postId: String, subreddit: String): List<CommentChild> {
        val linkId = "t3_$postId"
        val raw = arcticApi.getCommentsTree(linkId = linkId)
        // The tree endpoint wraps its result: {"data": [{"kind":"t1","data":{...}}]}
        return jsonList(raw.string()).mapNotNull { node ->
            if (str(node, "kind") == "t1") {
                val mutableNode = node as? MutableMap<String, Any?> ?: return@mapNotNull null
                fixCommentNode(mutableNode, 0, subreddit, linkId)
                runCatching {
                    val data = mutableNode["data"] as? Map<String, Any?> ?: return@runCatching null
                    CommentChild(parseComment(data))
                }.getOrNull()
            } else {
                // "more" nodes at the top level cannot be expanded by this source, so they are dropped
                null
            }
        }
    }

    /**
     * Pre-fills the fields the generated adapters require and injects the depth, recursively
     * into the nested `replies` listings, before the node is handed to Moshi. The nested nodes
     * stay raw: the outer Moshi parse handles them (and each was fixed in place by this call).
     */
    private fun fixCommentNode(
        node: MutableMap<String, Any?>,
        depth: Int,
        subreddit: String,
        linkId: String
    ) {
        val data = node["data"] as? MutableMap<String, Any?> ?: return
        val fixed = ensureCommentDefaults(data, subreddit, linkId).also { it["depth"] = depth }
        node["data"] = fixed

        when (val replies = fixed["replies"]) {
            null, is Boolean -> {}
            is String -> if (replies.isNotEmpty()) fixed["replies"] = ""
            is Map<*, *> -> {
                val listing = (replies as? MutableMap<String, Any?>) ?: mutableMapOf()
                listing["kind"] = "Listing"
                val listingData = (listing["data"] as? MutableMap<String, Any?>)
                    ?: mutableMapOf<String, Any?>()
                listing["data"] = listingData
                val children = (listingData["children"] as? List<*>) ?: emptyList<Any>()
                for (item in children) {
                    val child = item as? MutableMap<String, Any?> ?: continue
                    when (str(child, "kind")) {
                        "t1" -> fixCommentNode(child, depth + 1, subreddit, linkId)
                        "more" -> {
                            val md = (child["data"] as? MutableMap<String, Any?>) ?: mutableMapOf()
                            md.putIfAbsent("count", 0)
                            md.putIfAbsent("name", "")
                            md.putIfAbsent("id", "")
                            md.putIfAbsent("parent_id", linkId)
                            md.putIfAbsent("children", emptyList<Any>())
                            child["data"] = md
                        }
                    }
                }
                fixed["replies"] = listing
            }
            else -> fixed["replies"] = ""
        }
    }

    private fun buildAboutUserData(object_: Map<String, Any?>, fallbackName: String): AboutUserData {
        val meta = object_["_meta"] as? Map<*, *>
        return AboutUserData(
            isSuspended = false,
            isEmployee = false,
            subreddit = null,
            id = str(object_, "id"),
            iconImg = null,
            linkKarma = int(meta, "post_karma") ?: 0,
            totalKarma = int(meta, "total_karma") ?: 0,
            name = str(object_, "author") ?: fallbackName,
            created = long(meta, "earliest_post_at") ?: -1,
            snoovatarImg = null,
            commentKarma = int(meta, "comment_karma") ?: 0
        )
    }

    private suspend fun resolveSubredditFullname(subreddit: String): String {
        if (subreddit.startsWith("t5_")) return subreddit
        // Resolve the name to its base36 id (Arctic needs the t5_ fullname for /ids)
        val raw = runCatching { arcticApi.searchSubreddits(prefix = subreddit) }.getOrNull()
        val objects = raw?.string()?.let { runCatching { jsonList(it) }.getOrNull() } ?: emptyList()
        val match = objects.firstOrNull {
            (str(it, "display_name") ?: str(it, "name"))?.equals(subreddit, ignoreCase = true) == true
        }
        val id = match?.let { str(it, "id") }
        if (id != null && !id.startsWith("t5_")) {
            return "t5_$id"
        }
        // Fallback: assume the given string is already a base36 id
        return "t5_$subreddit"
    }

    private fun parsePermalink(permalink: String): Pair<String, String> {
        // /r/{subreddit}/comments/{postId}/... (or the bare post id)
        val match = Regex("/r/([^/]+)/comments/([a-z0-9]+)").find(permalink)
        if (match != null) {
            return match.groupValues[1] to match.groupValues[2]
        }
        val id = permalink.trim('/').substringAfterLast('/')
        return "" to id
    }

    /** Arctic only offers asc/desc on created_utc; all app sorts collapse to time ordering. */
    private fun sortOrder(sort: Sort?, timeSorting: TimeSorting?): String =
        if (sort == Sort.OLD) "asc" else "desc"

    private fun parseCursor(after: String?): Long? = after?.toLongOrNull()

    /**
     * `before` is exclusive on this API, so the minimum created_utc of the page is the next
     * cursor without any overlap, for both asc and desc ordering.
     */
    private fun nextPostCursor(children: List<PostChild>): String? =
        children.minByOrNull { it.data.created }?.let { it.data.created.toString() }

    private fun nextCommentCursor(children: List<CommentChild>): String? =
        children.minByOrNull { it.data.created }?.let { it.data.created.toString() }

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

    private fun int(map: Map<*, *>?, name: String): Int? {
        val value = map?.get(name)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun long(map: Map<*, *>?, name: String): Long? {
        val value = map?.get(name)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun parsePost(map: Map<String, Any?>): PostData =
        postDataAdapter.fromJson(toJson(map)) ?: throw IOException("Invalid post data")

    private fun parseComment(map: Map<String, Any?>): CommentData =
        commentDataAdapter.fromJson(toJson(map)) ?: throw IOException("Invalid comment data")

    private fun parseAbout(map: Map<String, Any?>): AboutData =
        aboutDataAdapter.fromJson(toJson(map)) ?: throw IOException("Invalid subreddit data")

    /**
     * Pre-fills the non-optional PostData fields that Arctic may omit on some objects
     * (popular and author feeds have been observed to miss several).
     */
    private fun ensurePostDefaults(
        map: Map<String, Any?>,
        subreddit: String?
    ): MutableMap<String, Any?> {
        val result = map.toMutableMap()
        if (subreddit != null) {
            result.putIfAbsent("subreddit_name_prefixed", "r/$subreddit")
            result.putIfAbsent("subreddit", subreddit)
        } else {
            // Global/author feeds carry the subreddit on each object; fall back to empty
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

    /**
     * Pre-fills the non-optional CommentData fields that Arctic tree/search objects may omit.
     */
    private fun ensureCommentDefaults(
        map: Map<String, Any?>,
        subreddit: String?,
        linkId: String?
    ): MutableMap<String, Any?> {
        val result = map.toMutableMap()
        result.putIfAbsent("subreddit_name_prefixed", subreddit?.let { "r/$it" } ?: "")
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
        result.putIfAbsent("link_id", linkId ?: "")
        if (result["permalink"] == null) {
            val commentId = (result["name"] as? String)?.removePrefix("t1_")
            val sub = (result["subreddit_name_prefixed"] as? String)?.removePrefix("r/")
            if (commentId != null && sub != null && linkId != null) {
                result["permalink"] = "/r/$sub/comments/${linkId.removePrefix("t3_")}/$commentId"
            } else {
                result["permalink"] = ""
            }
        }
        return result
    }

    /**
     * Pre-fills the non-optional AboutData fields that Arctic subreddit objects may omit.
     */
    private fun ensureAboutDefaults(map: Map<String, Any?>): MutableMap<String, Any?> {
        val result = map.toMutableMap()
        result.putIfAbsent("display_name", "")
        result.putIfAbsent("title", "")
        result.putIfAbsent("community_icon", "")
        result.putIfAbsent("banner_background_image", "")
        result.putIfAbsent("created_utc", 0L)
        if (result["url"] == null) {
            val name = str(result, "display_name")
            result["url"] = if (name != null) "/r/$name" else ""
        }
        return result
    }

    companion object {
        private const val LIMIT = 100
        private const val KIND_LISTING = "Listing"
        private const val POPULAR = "popular"
    }
}
