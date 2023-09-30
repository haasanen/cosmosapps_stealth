package com.cosmos.unreddit.data.remote.datasource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.cosmos.stealth.sdk.Stealth
import com.cosmos.stealth.sdk.data.model.api.After
import com.cosmos.stealth.sdk.data.model.api.Feedable
import com.cosmos.stealth.sdk.util.Resource
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.NetworkException
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.ServiceQuery
import com.squareup.moshi.JsonDataException
import retrofit2.HttpException
import java.io.IOException

class FeedDataSource(
    private val query: List<ServiceQuery>,
    private val filtering: Filtering,
    private val redditSource: Service,
    private val pageSize: Int
) : PagingSource<List<After>, Feedable>() {

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<List<After>>): LoadResult<List<After>, Feedable> {
        return try {
            val response = Stealth.getFeed {
                query.forEach {
                    addService(it.service.mapService(redditSource).asSupportedService()) {
                        communities { it.communities }
                    }
                }

                after { params.key }

                filtering.sort?.let { sort = it }
                filtering.order?.let { order = it }
                filtering.time?.let { time = it }

                limit = pageSize
            }

            when (response) {
                is Resource.Success -> {
                    LoadResult.Page(response.data.items, null, response.data.after)
                }
                is Resource.Error -> {
                    println(response.code)
                    LoadResult.Error(NetworkException(response.code, response.message))
                }
                is Resource.Exception -> {
                    println(response.throwable)
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

    override fun getRefreshKey(state: PagingState<List<After>, Feedable>): List<After>? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey
        }
    }
}
