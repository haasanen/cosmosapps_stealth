package com.cosmos.unreddit.ui.subreddit

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time
import com.cosmos.unreddit.data.local.mapper.FeedableMapper
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Query
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.StealthRepository
import com.cosmos.unreddit.di.DispatchersModule
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.updateValue
import com.cosmos.unreddit.util.mapFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SubredditSearchViewModel @Inject constructor(
    databaseRepository: DatabaseRepository,
    private val stealthRepository: StealthRepository,
    preferencesRepository: PreferencesRepository,
    private val feedableMapper: FeedableMapper,
    @DispatchersModule.DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, databaseRepository) {

    val contentPreferences: Flow<ContentPreferences> =
        preferencesRepository.getContentPreferences()

    private val _filtering: MutableStateFlow<Filtering> = MutableStateFlow(DEFAULT_FILTERING)
    val filtering: StateFlow<Filtering> = _filtering.asStateFlow()

    private val _query: MutableStateFlow<String> = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _subreddit: MutableStateFlow<String> = MutableStateFlow("")
    val subreddit: StateFlow<String> = _subreddit

    private val _service: MutableStateFlow<Service?> = MutableStateFlow(null)
    val service: StateFlow<Service?> = _service.asStateFlow()

    val feedItemDataFlow: Flow<PagingData<FeedItem>>

    val searchData: StateFlow<Data.FetchSingle> = combine(
        query,
        service,
        filtering
    ) { query, service, filtering ->
        if (service != null) Data.FetchSingle(Query(service, query), filtering) else null
    }.filterNotNull().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Data.FetchSingle(Query(Service(ServiceName.reddit), ""), DEFAULT_FILTERING)
    )

    private var latestUser: Data.User? = null

    private val userData: Flow<Data.User> = combine(
        historyIds, savedPostIds, contentPreferences
    ) { history, saved, prefs ->
        Data.User(history, saved, prefs)
    }.onEach {
        latestUser = it
    }.distinctUntilChangedBy {
        it.contentPreferences
    }

    init {
        feedItemDataFlow = searchData
            .dropWhile { it.query.query.isBlank() }
            .flatMapLatest { searchData -> userData.map { searchData to it } }
            .flatMapLatest { data -> getPosts(data.first, data.second) }
            .cachedIn(viewModelScope)
    }

    private fun getPosts(
        data: Data.FetchSingle,
        user: Data.User
    ): Flow<PagingData<FeedItem>> {
        return stealthRepository.searchInCommunity(data.query, subreddit.value, data.filtering)
            .map { it.mapFilter(latestUser ?: user, feedableMapper, defaultDispatcher) }
    }

    fun setQuery(subreddit: String) {
        _query.updateValue(subreddit)
    }

    fun setService(service: Service) {
        _service.updateValue(service)
    }

    fun setFiltering(filtering: Filtering) {
        _filtering.updateValue(filtering)
    }

    fun setSubreddit(subreddit: String) {
        _subreddit.updateValue(subreddit)
    }

    companion object {
        private val DEFAULT_FILTERING = Filtering(Sort.relevance, time = Time.all)
    }
}
