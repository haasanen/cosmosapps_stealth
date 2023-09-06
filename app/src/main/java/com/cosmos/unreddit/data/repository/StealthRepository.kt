package com.cosmos.unreddit.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.cosmos.stealth.sdk.data.model.api.Feedable
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.ServiceQuery
import com.cosmos.unreddit.data.remote.datasource.FeedDataSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StealthRepository @Inject constructor() {

    fun getFeed(
        query: List<ServiceQuery>,
        filtering: Filtering,
        pageSize: Int = DEFAULT_LIMIT
    ): Flow<PagingData<Feedable>> {
        return Pager(PagingConfig(pageSize = pageSize)) {
            FeedDataSource(query, filtering, pageSize)
        }.flow
    }

    companion object {
        private const val DEFAULT_LIMIT = 25
    }
}
