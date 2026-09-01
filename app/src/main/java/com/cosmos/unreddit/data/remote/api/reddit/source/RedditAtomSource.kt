package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Reddit (Atom)" — the same official reddit.com, but the home feed is read from
 * reddit's own Atom endpoint (`/r/{multi}/hot/.rss`) instead of the server-rendered
 * HTML pages.
 *
 * Why it exists: the SSR pages the website itself uses sit behind Cloudflare, and a
 * challenged client sees a blank feed. The Atom endpoint is served by the same
 * reddit.com infrastructure but answers without a challenge, so it is the reliable
 * option for networks where CF is aggressive (flaky home lines, public Wi-Fi,
 * travel). Trade-off: Atom entries carry no score and no comment count — they show
 * as 0, the same limitation the Arctic source has on multiredd feeds.
 *
 * Only the FEED methods (home feed, single subreddit, user feeds) use Atom.
 * Everything else — post detail, comments, user info, search — is the SSR page data,
 * which is richer and still the correct source for those screens.
 */
@Singleton
class RedditAtomSource @Inject constructor(
    private val officialSource: RedditOfficialSource
) : BaseRedditSource {

    override suspend fun getSubreddit(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withAtom { officialSource.getSubredditViaAtom(subreddit, sort, timeSorting, after) }

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = withAtom { officialSource.getUserPostsViaAtom(user, sort, timeSorting, after) }

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = officialSource.getUserComments(user, sort, timeSorting, after)

    // ---- Everything else: the full-featured official source ----

    override suspend fun getSubredditInfo(subreddit: String): Child =
        officialSource.getSubredditInfo(subreddit)

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = officialSource.searchInSubreddit(subreddit, query, sort, timeSorting, after)

    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> =
        officialSource.getPost(permalink, limit, sort)

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren =
        officialSource.getMoreChildren(children, linkId)

    override suspend fun getUserInfo(user: String): Child = officialSource.getUserInfo(user)

    override suspend fun searchPost(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = officialSource.searchPost(query, sort, timeSorting, after)

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = officialSource.searchUser(query, sort, timeSorting, after)

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing = officialSource.searchSubreddit(query, sort, timeSorting, after)

    /**
     * Atom can fail the same way SSR does (a challenged network may still answer the
     * RSS endpoint with a challenge page). Surface the failure rather than silently
     * serving nothing.
     */
    private suspend fun withAtom(block: suspend () -> Listing): Listing {
        return try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw java.io.IOException(
                "The reddit.com Atom feed is unreachable right now (${e.message ?: "connection failed"}). " +
                    "This is usually a Cloudflare challenge — wait a moment and pull to refresh, " +
                    "or switch the source back to 'Reddit (official)' in Settings.",
                e
            )
        }
    }
}
