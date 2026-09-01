package com.cosmos.unreddit.ui.postlist

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.cosmos.unreddit.data.feed.FeedCoordinator
import com.cosmos.unreddit.data.local.mapper.PostMapper2
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.data.model.db.PostEntity
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.model.preferences.DataPreferences
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.PostUtil
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PostListViewModel
@Inject constructor(
    private val repository: PostListRepository,
    private val preferencesRepository: PreferencesRepository,
    private val postMapper: PostMapper2,
    private val feedCoordinator: FeedCoordinator,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository) {

    val contentPreferences: Flow<ContentPreferences> =
        preferencesRepository.getContentPreferences()

    val profiles: Flow<List<Profile>> = repository.getAllProfiles()

    private val _sorting: MutableStateFlow<Sorting> = MutableStateFlow(DEFAULT_SORTING)
    val sorting: StateFlow<Sorting> = _sorting

    val subreddit: Flow<List<String>> = subscriptionsNames
        .distinctUntilChanged()
        .map { subscriptions ->
            if (subscriptions.isNotEmpty()) {
                subscriptions.shuffled()
            } else {
                listOf(DEFAULT_SUBREDDIT)
            }
        }
        .flowOn(defaultDispatcher)

    /**
     * The legacy Paging home feed (used by every source except the official one).
     * Built lazily so selecting the official source never triggers a second,
     * parallel fan-out of network requests behind the progressive feed.
     */
    val postDataFlow: Flow<PagingData<PostEntity>> by lazy {
        fetchData
            // Fetch last user data when search data is updated and merge them together
            .flatMapLatest { fetchData -> userData.map { fetchData to it } }
            .flatMapLatest { getPosts(it.first, it.second) }
            .onEach { _lastRefresh.value = System.currentTimeMillis() }
            .cachedIn(viewModelScope)
    }

    val fetchData: StateFlow<Data.FetchMultiple> = combine(
        subreddit,
        sorting
    ) { subreddit, sorting ->
        Data.FetchMultiple(subreddit, sorting)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Data.FetchMultiple(listOf(DEFAULT_SUBREDDIT), DEFAULT_SORTING)
    )

    private var latestUser: Data.User? = null

    private val userData: Flow<Data.User> = combine(
        historyIds, savedPostIds, contentPreferences
    ) { history, saved, prefs ->
        Data.User(history, saved, prefs)
    }.onEach {
        latestUser = it
    }.distinctUntilChanged { a, b -> a.contentPreferences == b.contentPreferences }

    private val _lastRefresh: MutableStateFlow<Long> = MutableStateFlow(System.currentTimeMillis())
    val lastRefresh: StateFlow<Long> = _lastRefresh.asStateFlow()

    var isDrawerOpen: Boolean = false

    //region Progressive home feed (official source only)

    /**
     * True when the home feed should be driven by the progressive [feedCoordinator]
     * (cache-first render, live subreddit-by-subreddit fill, local cache, configurable TTL
     * and per-post "(cached)" badge) instead of the legacy Paging feed.
     *
     * `null` until the DataStore has RESOLVED the source preference: seeding the
     * StateFlow with a concrete default (e.g. `false`) would make the legacy Paging
     * flow — which must not start while the official source is selected — fire its
     * fan-out for the milliseconds before the DataStore finishes loading.
     */
    val usesCoordinator: StateFlow<Boolean?> = preferencesRepository.getRedditSource()
        .map { it == DataPreferences.RedditSource.REDDIT_OFFICIAL.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Live progressive state from the coordinator (official source). */
    val feedState: StateFlow<FeedCoordinator.FeedState> = feedCoordinator.state

    /** Cache TTL in hours (user setting; default 24 h). */
    val cacheTtlHours: StateFlow<Int> = preferencesRepository.getCacheTtlHours()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_CACHE_TTL_HOURS)

    /** Everything the coordinator needs except the subreddit list + sort. */
    private data class CoordCtx(
        val profileId: Int,
        val historyIds: List<String>,
        val savedIds: List<String>,
        val showNsfw: Boolean,
        val ttlMs: Long
    )

    private val coordinatorCtx: StateFlow<CoordCtx> = combine(
        currentProfile,
        historyIds,
        savedPostIds,
        contentPreferences,
        cacheTtlHours
    ) { profile, history, saved, prefs, ttl ->
        CoordCtx(profile.id, history, saved, prefs.showNsfw, ttl * 3_600_000L)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CoordCtx(0, emptyList(), emptyList(), false, DEFAULT_CACHE_TTL_HOURS * 3_600_000L))

    init {
        // Drive the coordinator whenever subs, sort, profile, content or TTL change —
        // and (re)start it the moment the source preference resolves to the official
        // source, which can arrive AFTER the first subs/sort emission (datastore load
        // ordering), so `usesCoordinator` must be part of the trigger itself.
        viewModelScope.launch {
            combine(fetchData, coordinatorCtx, usesCoordinator) { fetch, ctx, active ->
                Trigger(ctx, fetch.query, fetch.sorting.generalSorting, active == true)
            }.distinctUntilChanged { a, b ->
                a.subs == b.subs && a.sort == b.sort && a.ctx == b.ctx && a.active == b.active
            }.collect { t ->
                if (!t.active) return@collect
                if (t.subs.isEmpty()) return@collect
                feedCoordinator.refresh(
                    profileId = t.ctx.profileId,
                    subs = t.subs,
                    sort = t.sort,
                    historyIds = t.ctx.historyIds,
                    savedIds = t.ctx.savedIds,
                    showNsfw = t.ctx.showNsfw,
                    ttlMs = t.ctx.ttlMs
                )
            }
        }
    }

    private data class Trigger(
        val ctx: CoordCtx,
        val subs: List<String>,
        val sort: Sort,
        val active: Boolean
    )

    /** Pull-to-refresh: re-run the coordinator cycle (official) or the paging refresh. */
    fun pullToRefresh() {
        if (usesCoordinator.value != true) return
        val fetch = fetchData.value
        val ctx = coordinatorCtx.value
        if (fetch.query.isNotEmpty()) {
            feedCoordinator.refresh(
                profileId = ctx.profileId,
                subs = fetch.query,
                sort = fetch.sorting.generalSorting,
                historyIds = ctx.historyIds,
                savedIds = ctx.savedIds,
                showNsfw = ctx.showNsfw,
                ttlMs = ctx.ttlMs
            )
        }
        _lastRefresh.value = System.currentTimeMillis()
    }

    /** Load the next page of the progressive home feed (scroll-triggered). */
    fun loadMoreFeed() {
        if (usesCoordinator.value != true) return
        val fetch = fetchData.value
        val ctx = coordinatorCtx.value
        if (fetch.query.isNotEmpty()) {
            feedCoordinator.loadMore(
                ctx.profileId,
                fetch.sorting.generalSorting,
                ctx.historyIds,
                ctx.savedIds
            )
        }
    }

    //endregion

    private fun getPosts(data: Data.FetchMultiple, user: Data.User): Flow<PagingData<PostEntity>> {
        return repository.getPosts(data.query, data.sorting)
            .map { pagingData ->
                PostUtil.filterPosts(pagingData, latestUser ?: user, postMapper, defaultDispatcher)
            }
    }

    fun setSorting(sorting: Sorting) {
        _sorting.updateValue(sorting)
    }

    fun selectProfile(profile: Profile) {
        viewModelScope.launch {
            preferencesRepository.setCurrentProfile(profile.id)
        }
    }

    companion object {
        private const val DEFAULT_SUBREDDIT = "popular"
        private val DEFAULT_SORTING = Sorting(Sort.HOT)
        private const val DEFAULT_CACHE_TTL_HOURS = 24
    }
}
