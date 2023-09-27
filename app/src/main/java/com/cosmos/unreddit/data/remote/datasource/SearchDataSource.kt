package com.cosmos.unreddit.data.remote.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.cosmos.stealth.sdk.Stealth
import com.cosmos.stealth.sdk.data.model.api.AfterKey
import com.cosmos.stealth.sdk.data.model.api.CommunityInfo
import com.cosmos.stealth.sdk.data.model.api.CommunityResults
import com.cosmos.stealth.sdk.data.model.api.Feedable
import com.cosmos.stealth.sdk.data.model.api.FeedableResults
import com.cosmos.stealth.sdk.data.model.api.SearchResults
import com.cosmos.stealth.sdk.data.model.api.SearchType
import com.cosmos.stealth.sdk.data.model.api.UserInfo
import com.cosmos.stealth.sdk.data.model.api.UserResults
import com.cosmos.stealth.sdk.util.Resource
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.NetworkException
import com.cosmos.unreddit.data.model.Query
import com.squareup.moshi.JsonDataException
import retrofit2.HttpException
import java.io.IOException

abstract class SearchDataSource<Value : Any>(
    private val query: Query,
    private val filtering: Filtering,
    private val community: String?,
    private val user: String?,
    private val pageSize: Int
) : PagingSource<AfterKey, Value>() {

    protected abstract val type: SearchType

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<AfterKey>): LoadResult<AfterKey, Value> {
        return try {
            val response = Stealth.search(query.query, query.service.asSupportedService()) {
                params.key?.let { after(it) }

                filtering.sort?.let { sort = it }
                filtering.order?.let { order = it }
                filtering.time?.let { time = it }

                this@SearchDataSource.community?.let { community = it }
                this@SearchDataSource.user?.let { user = it }

                type = this@SearchDataSource.type

                limit = pageSize
            }

            when (response) {
                is Resource.Success -> getPage(response.data)

                is Resource.Error -> {
                    LoadResult.Error(NetworkException(response.code, response.message))
                }

                is Resource.Exception -> {
                    LoadResult.Error(response.throwable)
                }
            }
        } catch (exception: IOException) {
            LoadResult.Error(exception)
        } catch (exception: HttpException) {
            LoadResult.Error(exception)
        } catch (exception: JsonDataException) {
            LoadResult.Error(exception)
        }
    }

    protected abstract fun getPage(searchResults: SearchResults): LoadResult.Page<AfterKey, Value>

    override fun getRefreshKey(state: PagingState<AfterKey, Value>): AfterKey? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey
        }
    }

    class FeedableSearchDataSource(
        query: Query,
        filtering: Filtering,
        community: String?,
        user: String?,
        pageSize: Int
    ) : SearchDataSource<Feedable>(query, filtering, community, user, pageSize) {

        override val type: SearchType
            get() = SearchType.feedable

        override fun getPage(searchResults: SearchResults): LoadResult.Page<AfterKey, Feedable> {
            val results = searchResults as FeedableResults
            return LoadResult.Page(results.results, null, results.after)
        }
    }

    class CommunitySearchDataSource(
        query: Query,
        filtering: Filtering,
        community: String?,
        user: String?,
        pageSize: Int
    ) : SearchDataSource<CommunityInfo>(query, filtering, community, user, pageSize) {

        override val type: SearchType
            get() = SearchType.community

        override fun getPage(searchResults: SearchResults): LoadResult.Page<AfterKey, CommunityInfo> {
            val results = searchResults as CommunityResults
            return LoadResult.Page(results.results, null, results.after)
        }
    }

    class UserSearchDataSource(
        query: Query,
        filtering: Filtering,
        community: String?,
        user: String?,
        pageSize: Int
    ) : SearchDataSource<UserInfo>(query, filtering, community, user, pageSize) {

        override val type: SearchType
            get() = SearchType.user

        override fun getPage(searchResults: SearchResults): LoadResult.Page<AfterKey, UserInfo> {
            val results = searchResults as UserResults
            return LoadResult.Page(results.results, null, results.after)
        }
    }
}
