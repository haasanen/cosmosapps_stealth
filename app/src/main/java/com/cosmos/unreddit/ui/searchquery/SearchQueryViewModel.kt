package com.cosmos.unreddit.ui.searchquery

import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SearchQueryViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    databaseRepository: DatabaseRepository,
) : BaseViewModel(preferencesRepository, databaseRepository) {

    private val _filtering: MutableStateFlow<Filtering> = MutableStateFlow(DEFAULT_FILTERING)
    val filtering: StateFlow<Filtering> = _filtering
    
    private val _serviceName: MutableStateFlow<ServiceName> = MutableStateFlow(DEFAULT_SERVICE)
    val serviceName: StateFlow<ServiceName> = _serviceName.asStateFlow()

    var query: String = ""
    var instance: String? = null

    var lemmyInstances: List<String> = listOf("lemmy.world")
        private set

    fun setFiltering(filtering: Filtering) {
        _filtering.updateValue(filtering)
    }

    fun setServiceName(serviceName: ServiceName) {
        _serviceName.updateValue(serviceName)
    }

    fun shouldClean(): Boolean {
        return _filtering.value != DEFAULT_FILTERING ||
                _serviceName.value != DEFAULT_SERVICE ||
                query.isNotEmpty() ||
                !instance.isNullOrEmpty()
    }

    fun clean() {
        _filtering.value = DEFAULT_FILTERING
        _serviceName.value = DEFAULT_SERVICE
        query = ""
        instance = null
    }

    companion object {
        private val DEFAULT_FILTERING = Filtering(Sort.relevance, time = Time.all)
        private val DEFAULT_SERVICE = ServiceName.reddit
    }
}
