package com.cosmos.unreddit.ui.search

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time
import com.cosmos.unreddit.data.local.mapper.CommunityMapper
import com.cosmos.unreddit.data.local.mapper.FeedableMapper
import com.cosmos.unreddit.data.local.mapper.UserMapper
import com.cosmos.unreddit.data.model.Community
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Query
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.User2
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.StealthRepository
import com.cosmos.unreddit.di.DispatchersModule
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.orFalse
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
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val stealthRepository: StealthRepository,
    private val repository: PostListRepository,
    databaseRepository: DatabaseRepository,
    preferencesRepository: PreferencesRepository,
    private val feedableMapper: FeedableMapper,
    private val communityMapper: CommunityMapper,
    private val userMapper: UserMapper,
    @DispatchersModule.DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository, databaseRepository) {

    val contentPreferences: Flow<ContentPreferences> =
        preferencesRepository.getContentPreferences()

    private val _filtering: MutableStateFlow<Filtering> = MutableStateFlow(DEFAULT_FILTERING)
    val filtering: StateFlow<Filtering> = _filtering.asStateFlow()

    private val _query: MutableStateFlow<String> = MutableStateFlow("")
    val query: StateFlow<String> get() = _query

    private val _service: MutableStateFlow<Service?> = MutableStateFlow(null)
    val service: StateFlow<Service?> = _service.asStateFlow()

    private val _lastRefreshPost: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshPost: StateFlow<Long> = _lastRefreshPost.asStateFlow()

    private val _lastRefreshSubreddit: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshSubreddit: StateFlow<Long> = _lastRefreshSubreddit.asStateFlow()

    private val _lastRefreshUser: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshUser: StateFlow<Long> = _lastRefreshUser.asStateFlow()

    val postDataFlow: Flow<PagingData<FeedItem>>
    val subredditDataFlow: Flow<PagingData<Community>>
    val userDataFlow: Flow<PagingData<User2>>

    private val searchData: StateFlow<Data.FetchSingle> = combine(
        query,
        service,
        filtering
    ) { query, service, filtering ->
        service?.run { Data.FetchSingle(Query(service, query), filtering) }
    }.filterNotNull().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Data.FetchSingle(Query(Service(ServiceName.reddit), ""), DEFAULT_FILTERING)
    )

    private val userData: Flow<Data.User> = combine(
        historyIds,
        savedPostIds,
        contentPreferences
    ) { history, saved, prefs ->
        Data.User(history, saved, prefs)
    }

    val data: Flow<Pair<Data.FetchSingle, Data.User>> = searchData
        .dropWhile { it.query.query.isBlank() }
        .flatMapLatest { searchData -> userData.take(1).map { searchData to it } }

    init {
        postDataFlow = data
            .flatMapLatest { data -> getPosts(data.first, data.second) }
            .onEach { _lastRefreshPost.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)

        subredditDataFlow = data
            .flatMapLatest { data -> getSubreddits(data.first, data.second) }
            .onEach { _lastRefreshSubreddit.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)

        userDataFlow = data
            .flatMapLatest { data -> getUsers(data.first, data.second) }
            .onEach { _lastRefreshUser.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)
    }

    private fun getPosts(
        data: Data.FetchSingle,
        user: Data.User
    ): Flow<PagingData<FeedItem>> {
        return stealthRepository.searchPosts(data.query, data.filtering)
            .map { it.mapFilter(user, feedableMapper, defaultDispatcher) }
    }

    private fun getSubreddits(
        data: Data.FetchSingle,
        user: Data.User
    ): Flow<PagingData<Community>> {
        return stealthRepository.searchCommunities(data.query, data.filtering)
            .map { pagingData ->
                pagingData
                    .map { communityMapper.dataToEntity(it) }
                    .filter { user.contentPreferences.showNsfw || !it.nsfw.orFalse() }
            }
            .flowOn(defaultDispatcher)
    }

    private fun getUsers(
        data: Data.FetchSingle,
        user: Data.User
    ): Flow<PagingData<User2>> {
        return stealthRepository.searchUsers(data.query, data.filtering)
            .map { pagingData ->
                pagingData
                    .map { userMapper.dataToEntity(it) }
                    .filter { user.contentPreferences.showNsfw || !it.nsfw.orFalse() }
            }
            .flowOn(defaultDispatcher)
    }

    fun setService(service: Service) {
        _service.updateValue(service)
    }

    fun setFiltering(filtering: Filtering) {
        _filtering.updateValue(filtering)
    }

    fun setQuery(query: String) {
        _query.updateValue(query)
    }

    companion object {
        private val DEFAULT_FILTERING = Filtering(Sort.relevance, time = Time.all)
    }
}
