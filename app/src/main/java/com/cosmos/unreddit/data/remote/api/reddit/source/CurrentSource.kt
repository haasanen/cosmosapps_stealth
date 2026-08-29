package com.cosmos.unreddit.data.remote.api.reddit.source

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.model.preferences.DataPreferences
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import com.cosmos.unreddit.data.remote.api.reddit.model.Listing
import com.cosmos.unreddit.data.remote.api.reddit.model.MoreChildren
import com.cosmos.unreddit.data.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentSource @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val redditSource: RedditSource,
    private val redditScrapingSource: RedditScrapingSource,
    private val tedditSource: TedditSource,
    private val arcticShiftSource: ArcticShiftSource,
    private val redditOfficialSource: RedditOfficialSource
) : BaseRedditSource {

    private val mutex = Mutex()

    private var source: BaseRedditSource
    private var sourceType: DataPreferences.RedditSource

    init {
        val sourceValue = runBlocking { preferencesRepository.getRedditSource().first() }
        sourceType = DataPreferences.RedditSource.fromValue(sourceValue)
        source = getRedditSource(sourceValue)
    }

    override suspend fun getSubreddit(
        subreddit: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.getSubreddit(subreddit, sort, timeSorting, after)
    }

    override suspend fun getSubredditInfo(subreddit: String): Child {
        return source.getSubredditInfo(subreddit)
    }

    override suspend fun searchInSubreddit(
        subreddit: String,
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.searchInSubreddit(subreddit, query, sort, timeSorting, after)
    }

    override suspend fun getPost(permalink: String, limit: Int?, sort: Sort): List<Listing> {
        return source.getPost(permalink, limit, sort)
    }

    override suspend fun getMoreChildren(children: String, linkId: String): MoreChildren {
        // TODO: Replace by source when an endpoint is available for Teddit
        return redditSource.getMoreChildren(children, linkId)
    }

    override suspend fun getUserInfo(user: String): Child {
        return source.getUserInfo(user)
    }

    override suspend fun getUserPosts(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.getUserPosts(user, sort, timeSorting, after)
    }

    override suspend fun getUserComments(
        user: String,
        sort: Sort,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.getUserComments(user, sort, timeSorting, after)
    }

    override suspend fun searchPost(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        // Arctic has its own (prefix-based) search; the "Reddit (official)" source has a
        // working live full-text search; the other sources fall back to the official API
        // (TODO: Replace by source when an endpoint is available for Teddit)
        return when (sourceType) {
            DataPreferences.RedditSource.ARCTIC -> source.searchPost(query, sort, timeSorting, after)
            DataPreferences.RedditSource.REDDIT_OFFICIAL -> source.searchPost(query, sort, timeSorting, after)
            else -> redditSource.searchPost(query, sort, timeSorting, after)
        }
    }

    override suspend fun searchUser(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        // Arctic has its own (prefix-based) user search; other sources fall back to the
        // official API (TODO: Replace by source when an endpoint is available for Teddit)
        return if (sourceType == DataPreferences.RedditSource.ARCTIC) {
            source.searchUser(query, sort, timeSorting, after)
        } else {
            redditSource.searchUser(query, sort, timeSorting, after)
        }
    }

    override suspend fun searchSubreddit(
        query: String,
        sort: Sort?,
        timeSorting: TimeSorting?,
        after: String?
    ): Listing {
        return source.searchSubreddit(query, sort, timeSorting, after)
    }

    suspend fun setRedditSource(value: Int) {
        mutex.withLock {
            sourceType = DataPreferences.RedditSource.fromValue(value)
            source = getRedditSource(value)
        }
    }

    private fun getRedditSource(value: Int): BaseRedditSource {
        return when(DataPreferences.RedditSource.fromValue(value)) {
            DataPreferences.RedditSource.REDDIT -> redditSource
            DataPreferences.RedditSource.TEDDIT -> tedditSource
            DataPreferences.RedditSource.REDDIT_SCRAP -> redditScrapingSource
            DataPreferences.RedditSource.ARCTIC -> arcticShiftSource
            DataPreferences.RedditSource.REDDIT_OFFICIAL -> redditOfficialSource
        }
    }
}
