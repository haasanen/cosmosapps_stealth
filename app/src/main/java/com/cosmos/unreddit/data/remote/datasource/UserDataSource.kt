package com.cosmos.unreddit.data.remote.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.cosmos.stealth.sdk.Stealth
import com.cosmos.stealth.sdk.data.model.api.After
import com.cosmos.stealth.sdk.data.model.api.Feedable
import com.cosmos.stealth.sdk.data.model.api.FeedableType
import com.cosmos.stealth.sdk.util.Resource
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.NetworkException
import com.cosmos.unreddit.data.model.Query
import com.squareup.moshi.JsonDataException
import retrofit2.HttpException
import java.io.IOException

class UserDataSource(
    private val query: Query,
    private val filtering: Filtering,
    private val type: FeedableType,
    private val pageSize: Int
) : PagingSource<After, Feedable>() {

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<After>): LoadResult<After, Feedable> {
        return try {
            val response = Stealth.getUser(query.query, query.service.asSupportedService()) {
                params.key?.let { after(it) }

                filtering.sort?.let { sort = it }
                filtering.order?.let { order = it }
                filtering.time?.let { time = it }

                type = this@UserDataSource.type

                limit = pageSize
            }

            when (response) {
                is Resource.Success -> {
                    LoadResult.Page(
                        response.data.feed.items,
                        null,
                        response.data.feed.after?.firstOrNull()
                    )
                }

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

    override fun getRefreshKey(state: PagingState<After, Feedable>): After? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey
        }
    }
}
