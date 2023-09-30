package com.cosmos.unreddit.ui.subreddit

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.service.LemmyService
import com.cosmos.stealth.sdk.util.Resource.Error
import com.cosmos.stealth.sdk.util.Resource.Exception
import com.cosmos.stealth.sdk.util.Resource.Success
import com.cosmos.unreddit.data.local.mapper.CommunityMapper
import com.cosmos.unreddit.data.local.mapper.FeedableMapper
import com.cosmos.unreddit.data.model.Community
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Query
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.StealthRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.latest
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubredditViewModel @Inject constructor(
    private val databaseRepository: DatabaseRepository,
    private val stealthRepository: StealthRepository,
    preferencesRepository: PreferencesRepository,
    private val feedableMapper: FeedableMapper,
    private val communityMapper: CommunityMapper,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, databaseRepository) {

    val contentPreferences: Flow<ContentPreferences> =
        preferencesRepository.getContentPreferences()

    private val _filtering: MutableStateFlow<Filtering> = MutableStateFlow(DEFAULT_FILTERING)
    val filtering: StateFlow<Filtering> = _filtering.asStateFlow()

    private val _subreddit: MutableStateFlow<String> = MutableStateFlow("")
    val subreddit: StateFlow<String> = _subreddit

    private val _service: MutableStateFlow<Service?> = MutableStateFlow(null)
    val service: StateFlow<Service?> = _service.asStateFlow()

    private val _about: MutableStateFlow<Resource<Community>> =
        MutableStateFlow(Resource.Loading())
    val about: StateFlow<Resource<Community>> = _about

    private val _isDescriptionCollapsed = MutableStateFlow(true)
    val isDescriptionCollapsed: StateFlow<Boolean> = _isDescriptionCollapsed

    var contentLayoutProgress: Float? = null
    var drawerContentLayoutProgress: Float? = null

    var isSubredditReachable: Boolean = false

    val isSubscribed: StateFlow<Boolean> = combine(
        subreddit,
        service,
        subscriptions
    ) { subreddit, service, subscriptions ->
        subscriptions.any {
            val fromService = it.name == subreddit && it.service == service?.name
            when (service?.name) {
                ServiceName.reddit, ServiceName.teddit -> fromService
                ServiceName.lemmy -> fromService && it.instance == service.instance
                null -> false
            }
        }
    }.flowOn(
        defaultDispatcher
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        false
    )

    val isSubscribable: StateFlow<Boolean> = combine(
        service,
        subscriptions
    ) { service, subscriptions ->
        when (service?.name) {
            ServiceName.lemmy -> {
                subscriptions.count {
                    it.service == ServiceName.lemmy && it.instance == service.instance
                } < LemmyService.MAX_COMMUNITIES
            }
            null -> false
            else -> true
        }
    }.flowOn(
        defaultDispatcher
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        true
    )

    private val subredditName: String
        get() = about.value.dataValue?.name ?: subreddit.value

    private val icon: String?
        get() = about.value.dataValue?.icon?.source?.url

    val feedItemDataFlow: Flow<PagingData<FeedItem>>

    val searchData: StateFlow<Data.FetchSingle> = combine(
        subreddit,
        service,
        filtering
    ) { subreddit, service, filtering ->
        if (service != null) Data.FetchSingle(Query(service, subreddit), filtering) else null
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

    private val _lastRefresh: MutableStateFlow<Long> = MutableStateFlow(System.currentTimeMillis())
    val lastRefresh: StateFlow<Long> = _lastRefresh.asStateFlow()

    init {
        feedItemDataFlow = searchData
            .onEach { println(it) }
            .dropWhile { it.query.query.isBlank() }
            .flatMapLatest { searchData -> userData.map { searchData to it } }
            .flatMapLatest { data -> getPosts(data.first, data.second) }
            .onEach { _lastRefresh.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)
    }

    private fun getPosts(
        data: Data.FetchSingle,
        user: Data.User
    ): Flow<PagingData<FeedItem>> {
        return stealthRepository.getCommunity(data.query, data.filtering)
            .map { it.mapFilter(latestUser ?: user, feedableMapper, defaultDispatcher) }
    }

    fun loadSubredditInfo(forceUpdate: Boolean) {
        val service = _service.value
        val community = _subreddit.value

        if (community.isNotBlank() && service != null) {
            if (_about.value !is Resource.Success || forceUpdate) {
                loadSubredditInfo(community, service)
            }
        } else {
            _about.value = Resource.Error()
        }
    }

    private fun loadSubredditInfo(subreddit: String, service: Service) {
        viewModelScope.launch {
            stealthRepository.getCommunityInfo(subreddit, service)
                .onStart {
                    _about.value = Resource.Loading()
                }
                .collect { response ->
                    when (response) {
                        is Success -> {
                            _about.value = Resource.Success(
                                communityMapper.dataToEntity(response.data)
                            )
                        }

                        is Error -> {
                            _about.value = Resource.Error(response.code, response.message)
                        }

                        is Exception -> {
                            _about.value = Resource.Error(throwable = response.throwable)
                        }
                    }
                }
        }
    }

    fun setSubreddit(subreddit: String) {
        _subreddit.updateValue(subreddit)
    }

    fun setService(service: Service) {
        _service.updateValue(service)
    }

    fun setFiltering(filtering: Filtering) {
        _filtering.updateValue(filtering)
    }

    fun toggleDescriptionCollapsed() {
        _isDescriptionCollapsed.value = !_isDescriptionCollapsed.value
    }

    fun toggleSubscription() {
        viewModelScope.launch {
            val profile = currentProfile.latest
            val service = _service.value

            if (profile != null && service != null) {
                if (isSubscribed.value) {
                    databaseRepository.unsubscribe(subredditName, profile.id)
                } else {
                    databaseRepository.subscribe(subredditName, profile.id, service, icon)
                }
            }
        }
    }

    companion object {
        private val DEFAULT_FILTERING = Filtering(Sort.trending)
    }
}
