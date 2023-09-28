package com.cosmos.unreddit.ui.user

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.cosmos.stealth.sdk.data.model.api.Order
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.util.Resource.Error
import com.cosmos.stealth.sdk.util.Resource.Exception
import com.cosmos.stealth.sdk.util.Resource.Success
import com.cosmos.unreddit.data.local.mapper.FeedableMapper
import com.cosmos.unreddit.data.local.mapper.UserMapper
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Query
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.User2
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.StealthRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserViewModel @Inject constructor(
    private val databaseRepository: DatabaseRepository,
    private val stealthRepository: StealthRepository,
    preferencesRepository: PreferencesRepository,
    private val feedableMapper: FeedableMapper,
    private val userMapper: UserMapper,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, databaseRepository) {

    val contentPreferences: Flow<ContentPreferences> =
        preferencesRepository.getContentPreferences()

    private val _filtering: MutableStateFlow<Filtering> = MutableStateFlow(DEFAULT_FILTERING)
    val filtering: StateFlow<Filtering> = _filtering.asStateFlow()

    private val _user: MutableStateFlow<String> = MutableStateFlow("")
    val user: StateFlow<String> = _user

    private val _service: MutableStateFlow<Service?> = MutableStateFlow(null)
    val service: StateFlow<Service?> = _service.asStateFlow()

    private val _page: MutableStateFlow<Int> = MutableStateFlow(0)
    val page: StateFlow<Int> get() = _page

    private val _about: MutableStateFlow<Resource<User2>> = MutableStateFlow(Resource.Loading())
    val about: StateFlow<Resource<User2>> = _about

    var layoutState: Int? = null

    private val savedCommentIds: Flow<List<String>> = currentProfile.flatMapLatest {
        databaseRepository.getSavedCommentIds(it.id)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    private val _lastRefreshPost: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshPost: StateFlow<Long> = _lastRefreshPost.asStateFlow()

    private val _lastRefreshComment: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshComment: StateFlow<Long> = _lastRefreshComment.asStateFlow()

    val postDataFlow: Flow<PagingData<FeedItem>>
    val commentDataFlow: Flow<PagingData<FeedItem>>

    private val searchData: StateFlow<Data.FetchSingle> = combine(
        user,
        service,
        filtering
    ) { user, service, filtering ->
        service?.run { Data.FetchSingle(Query(service, user), filtering) }
    }.filterNotNull().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Data.FetchSingle(Query(Service(ServiceName.reddit), ""), DEFAULT_FILTERING)
    )

    private var latestUser: Data.User? = null

    private val userData: Flow<Data.User> = combine(
        historyIds,
        savedPostIds,
        contentPreferences,
        savedCommentIds
    ) { history, saved, prefs, savedComments ->
        Data.User(history, saved, prefs, savedComments)
    }.onEach {
        latestUser = it
    }.distinctUntilChangedBy {
        it.contentPreferences
    }

    val data: Flow<Pair<Data.FetchSingle, Data.User>> = searchData
        .dropWhile { it.query.query.isBlank() }
        .flatMapLatest { searchData -> userData.map { searchData to it } }

    init {
        postDataFlow = data
            .flatMapLatest { data -> getPosts(data.first, data.second) }
            .onEach { _lastRefreshPost.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)

        commentDataFlow = data
            .flatMapLatest { data -> getComments(data.first, data.second) }
            .onEach { _lastRefreshComment.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)
    }

    private fun getPosts(
        data: Data.FetchSingle,
        user: Data.User
    ): Flow<PagingData<FeedItem>> {
        return stealthRepository.getUserPosts(data.query, data.filtering)
            .map { it.mapFilter(latestUser ?: user, feedableMapper, defaultDispatcher) }
    }

    private fun getComments(
        data: Data.FetchSingle,
        user: Data.User
    ): Flow<PagingData<FeedItem>> {
        return stealthRepository.getUserComments(data.query, data.filtering)
            .map { pagingData ->
                pagingData
                    .map { feedableMapper.dataToEntity(it) }
                    .map { comment ->
                        comment.apply {
                            (this as? CommentItem)?.saved =
                                (latestUser ?: user).savedComments?.contains(this.id) ?: false
                        }
                    }
            }
            .flowOn(defaultDispatcher)
    }

    fun loadUserInfo(forceUpdate: Boolean) {
        val service = _service.value
        val user = _user.value

        if (user.isNotBlank() && service != null) {
            if (_about.value !is Resource.Success || forceUpdate) {
                loadUserInfo(user, service)
            }
        } else {
            _about.value = Resource.Error()
        }
    }

    private fun loadUserInfo(user: String, service: Service) {
        viewModelScope.launch {
            stealthRepository.getUserInfo(user, service)
                .onStart {
                    _about.value = Resource.Loading()
                }
                .collect { response ->
                    when (response) {
                        is Success -> {
                            _about.value = Resource.Success(
                                userMapper.dataToEntity(response.data)
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

    fun setService(service: Service) {
        _service.updateValue(service)
    }

    fun setFiltering(filtering: Filtering) {
        _filtering.updateValue(filtering)
    }

    fun setUser(user: String) {
        _user.updateValue(user)
    }

    fun setPage(position: Int) {
        _page.updateValue(position)
    }

    companion object {
        private val DEFAULT_FILTERING = Filtering(Sort.date, Order.desc)
    }
}
