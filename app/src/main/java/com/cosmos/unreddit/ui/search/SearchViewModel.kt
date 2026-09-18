package com.cosmos.unreddit.ui.search

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.local.mapper.SubredditMapper2
import com.cosmos.unreddit.data.local.mapper.UserMapper2
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.model.User
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.SubredditEntity
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutChild
import com.cosmos.unreddit.data.remote.api.reddit.model.AboutUserChild
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.di.DispatchersModule
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.PostUtil
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: PostListRepository,
    preferencesRepository: PreferencesRepository,
    private val postMapper: PostMapper2,
    private val subredditMapper: SubredditMapper2,
    private val userMapper: UserMapper2,
    @DispatchersModule.DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository) {

    val contentPreferences: Flow<ContentPreferences> =
        preferencesRepository.getContentPreferences()

    private val _sorting: MutableStateFlow<Sorting> = MutableStateFlow(DEFAULT_SORTING)
    val sorting: StateFlow<Sorting> = _sorting

    private val _query: MutableStateFlow<String> = MutableStateFlow("")
    val query: StateFlow<String> get() = _query

    /**
     * Monotonic search generation. A plain [MutableStateFlow] for the query alone
     * CONFLATES an identical resubmit (`updateValue("linux")` when it's already
     * "linux" emits nothing), so [searchData] would not re-emit, [data] would not
     * restart, and [cachedIn] would hand back the SAME in-memory results — the
     * 2026-09-18 "search again after switching VPN country shows the old
     * subreddits" report. Bumping this on every explicit submit forces a fresh
     * network fetch even for an unchanged term.
     */
    private val _searchGeneration: MutableStateFlow<Long> = MutableStateFlow(0L)

    private val _lastRefreshPost: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshPost: StateFlow<Long> = _lastRefreshPost.asStateFlow()

    private val _lastRefreshSubreddit: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshSubreddit: StateFlow<Long> = _lastRefreshSubreddit.asStateFlow()

    private val _lastRefreshUser: MutableStateFlow<Long> =
        MutableStateFlow(System.currentTimeMillis())
    val lastRefreshUser: StateFlow<Long> = _lastRefreshUser.asStateFlow()

    val postDataFlow: Flow<PagingData<PostEntity>>
    val subredditDataFlow: Flow<PagingData<SubredditEntity>>
    val userDataFlow: Flow<PagingData<User>>

    private val searchData: Flow<Pair<Data.Fetch, Long>> = combine(
        query,
        sorting,
        _searchGeneration
    ) { q, s, generation ->
        Data.Fetch(q, s) to generation
    }

    private val userData: Flow<Data.User> = combine(
        historyIds,
        savedPostIds,
        contentPreferences
    ) { history, saved, prefs ->
        Data.User(history, saved, prefs)
    }

    val data: Flow<Pair<Data.Fetch, Data.User>> = searchData
        .dropWhile { it.first.query.isBlank() }
        .flatMapLatest { search -> userData.take(1).map { search.first to it } }

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
        data: Data.Fetch,
        user: Data.User
    ): Flow<PagingData<PostEntity>> {
        return repository.searchPost(data.query, data.sorting)
            .map { pagingData ->
                PostUtil.filterPosts(pagingData, user, postMapper, defaultDispatcher)
            }
    }

    private fun getSubreddits(
        data: Data.Fetch,
        user: Data.User
    ): Flow<PagingData<SubredditEntity>> {
        return repository.searchSubreddit(data.query, data.sorting)
            .map { pagingData ->
                pagingData
                    .map { subredditMapper.dataToEntity((it as AboutChild).data) }
                    .filter { user.contentPreferences.showNsfw || !it.over18 }
            }
            .flowOn(defaultDispatcher)
    }

    private fun getUsers(
        data: Data.Fetch,
        user: Data.User
    ): Flow<PagingData<User>> {
        return repository.searchUser(data.query, data.sorting)
            .map { pagingData ->
                pagingData
                    .map { userMapper.dataToEntity((it as AboutUserChild).data) }
                    .filter { user.contentPreferences.showNsfw || !it.over18 }
            }
            .flowOn(defaultDispatcher)
    }

    fun setSorting(sorting: Sorting) {
        _sorting.updateValue(sorting)
    }

    fun setQuery(query: String) {
        _query.updateValue(query)
        // A search is an explicit act: even an UNCHANGED term must refetch (the
        // user may have switched VPN country, so the same query now maps to a
        // different geo-localized result set). The StateFlow above would otherwise
        // conflate it away — see _searchGeneration.
        _searchGeneration.updateValue(_searchGeneration.value + 1L)
    }

    companion object {
        private val DEFAULT_SORTING = Sorting(Sort.RELEVANCE, TimeSorting.ALL)
    }
}
