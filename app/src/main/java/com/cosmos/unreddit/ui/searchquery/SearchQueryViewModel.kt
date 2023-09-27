package com.cosmos.unreddit.ui.searchquery

import androidx.lifecycle.viewModelScope
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time
import com.cosmos.stealth.sdk.data.model.service.RedditService
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.repository.AssetsRepository
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SearchQueryViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    repository: PostListRepository,
    databaseRepository: DatabaseRepository,
    private val assetsRepository: AssetsRepository,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository, databaseRepository) {

    private val _filtering: MutableStateFlow<Filtering> = MutableStateFlow(DEFAULT_FILTERING)
    val filtering: StateFlow<Filtering> = _filtering
    
    private val _serviceName: MutableStateFlow<ServiceName> = MutableStateFlow(DEFAULT_SERVICE)
    val serviceName: StateFlow<ServiceName> = _serviceName.asStateFlow()
    
    private val _redditInstance: MutableStateFlow<RedditService.Instance> =
        MutableStateFlow(DEFAULT_REDDIT_INSTANCE)
    val redditInstance: StateFlow<RedditService.Instance> = _redditInstance.asStateFlow()

    var query: String = ""
    var instance: String? = null

    var tedditInstances: List<String> = emptyList()
        private set

    var lemmyInstances: List<String> = listOf("lemmy.world")
        private set

    init {
        viewModelScope.launch { loadTedditInstances() }
    }

    private suspend fun loadTedditInstances() {
        assetsRepository.getServiceInstances()
            .onSuccess { services ->
                withContext(defaultDispatcher) {
                    services
                        .firstOrNull { it.service == "reddit" }
                        ?.redirect
                        ?.firstOrNull { it.name == "teddit" }
                        ?.instances
                        ?.let { tedditInstances = it }
                }
            }
    }

    fun setFiltering(filtering: Filtering) {
        _filtering.updateValue(filtering)
    }

    fun setServiceName(serviceName: ServiceName) {
        _serviceName.updateValue(serviceName)
    }

    fun setRedditInstance(instance: RedditService.Instance) {
        _redditInstance.updateValue(instance)
    }

    fun shouldClean(): Boolean {
        return _filtering.value != DEFAULT_FILTERING ||
                _serviceName.value != DEFAULT_SERVICE ||
                _redditInstance.value != DEFAULT_REDDIT_INSTANCE ||
                query.isNotEmpty() ||
                !instance.isNullOrEmpty()
    }

    fun clean() {
        _filtering.value = DEFAULT_FILTERING
        _serviceName.value = DEFAULT_SERVICE
        _redditInstance.value = DEFAULT_REDDIT_INSTANCE
        query = ""
        instance = null
    }

    companion object {
        private val DEFAULT_FILTERING = Filtering(Sort.relevance, time = Time.all)
        private val DEFAULT_SERVICE = ServiceName.reddit
        private val DEFAULT_REDDIT_INSTANCE = RedditService.Instance.REGULAR
    }
}
