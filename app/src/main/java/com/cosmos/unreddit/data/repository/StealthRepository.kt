package com.cosmos.unreddit.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.cosmos.stealth.sdk.Stealth
import com.cosmos.stealth.sdk.data.model.api.Appendable
import com.cosmos.stealth.sdk.data.model.api.CommunityInfo
import com.cosmos.stealth.sdk.data.model.api.Feedable
import com.cosmos.stealth.sdk.data.model.api.FeedableType
import com.cosmos.stealth.sdk.data.model.api.Post
import com.cosmos.stealth.sdk.data.model.api.UserInfo
import com.cosmos.stealth.sdk.util.Resource
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Query
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.ServiceQuery
import com.cosmos.unreddit.data.remote.datasource.CommunityDataSource
import com.cosmos.unreddit.data.remote.datasource.FeedDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchDataSource.CommunitySearchDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchDataSource.FeedableSearchDataSource
import com.cosmos.unreddit.data.remote.datasource.SearchDataSource.UserSearchDataSource
import com.cosmos.unreddit.data.remote.datasource.UserDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StealthRepository @Inject constructor() {

    fun getPost(post: String, service: Service, filtering: Filtering): Flow<Resource<Post>> = flow {
        val response = Stealth.getPost(post, service.asSupportedService()) {
            filtering.sort?.let { sort = it }
            filtering.order?.let { order = it }
        }

        emit(response)
    }

    fun getFeed(
        query: List<ServiceQuery>,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Feedable>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            FeedDataSource(query, filtering, pageSize)
        }.flow
    }

    fun getCommunity(
        query: Query,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Feedable>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            CommunityDataSource(query, filtering, pageSize)
        }.flow
    }

    fun getCommunityInfo(
        community: String,
        service: Service
    ): Flow<Resource<CommunityInfo>> = flow {
        val response = Stealth.getCommunityInfo(community, service.asSupportedService())
        emit(response)
    }

    fun getUserPosts(
        query: Query,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Feedable>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            UserDataSource(query, filtering, FeedableType.post, pageSize)
        }.flow
    }

    fun getUserComments(
        query: Query,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Feedable>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            UserDataSource(query, filtering, FeedableType.comment, pageSize)
        }.flow
    }

    fun getUserInfo(
        user: String,
        service: Service,
    ): Flow<Resource<UserInfo>> = flow {
        val response = Stealth.getUserInfo(user, service.asSupportedService())
        emit(response)
    }

    fun searchInCommunity(
        query: Query,
        community: String,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Feedable>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            FeedableSearchDataSource(query, filtering, community, null, pageSize)
        }.flow
    }

    fun searchPosts(
        query: Query,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Feedable>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            FeedableSearchDataSource(query, filtering, null, null, pageSize)
        }.flow
    }

    fun searchCommunities(
        query: Query,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<CommunityInfo>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            CommunitySearchDataSource(query, filtering, null, null, pageSize)
        }.flow
    }

    fun searchUsers(
        query: Query,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<UserInfo>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            UserSearchDataSource(query, filtering, null, null, pageSize)
        }.flow
    }

    fun getMore(appendable: Appendable): Flow<Resource<List<Feedable>>> = flow {
        emit(Stealth.getMore(appendable))
    }

    companion object {
        private const val DEFAULT_LIMIT = 25
    }
}
