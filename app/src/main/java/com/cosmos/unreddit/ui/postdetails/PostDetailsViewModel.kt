package com.cosmos.unreddit.ui.postdetails

import androidx.lifecycle.viewModelScope
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.util.Resource.Error
import com.cosmos.stealth.sdk.util.Resource.Exception
import com.cosmos.stealth.sdk.util.Resource.Success
import com.cosmos.unreddit.data.local.mapper.FeedableMapper
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.data.repository.StealthRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class PostDetailsViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    databaseRepository: DatabaseRepository,
    private val repository: PostListRepository,
    private val stealthRepository: StealthRepository,
    private val feedableMapper: FeedableMapper,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, repository, databaseRepository) {

    private val _filtering: MutableStateFlow<Filtering> = MutableStateFlow(DEFAULT_FILTERING)
    val filtering: StateFlow<Filtering> = _filtering.asStateFlow()

    private val _postId: MutableStateFlow<String?> = MutableStateFlow(null)
    val postId: StateFlow<String?> = _postId.asStateFlow()

    private val _service: MutableStateFlow<Service?> = MutableStateFlow(null)
    val service: StateFlow<Service?> = _service.asStateFlow()

    private val _singleThread: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val singleThread: StateFlow<Boolean> = _singleThread.asStateFlow()

    val savedCommentIds: Flow<List<String>> = currentProfile.flatMapLatest {
        repository.getSavedCommentIds(it.id)
    }

    private val _post: MutableStateFlow<Resource<FeedItem>> =
        MutableStateFlow(Resource.Loading())

    private val _replies: MutableStateFlow<Resource<List<FeedItem>>> =
        MutableStateFlow(Resource.Loading())

    val post: Flow<Resource<FeedItem>> = combine(_post, savedPostIds) { post, savedIds ->
        post.apply {
            if (this is Resource.Success) {
                data.saved = savedIds.contains(data.id)
            }
        }
    }.flowOn(defaultDispatcher)

    val replies: Flow<Resource<List<FeedItem>>> = combine(
        _replies,
        savedCommentIds
    ) { comments, savedIds ->
        comments.apply {
            if (this is Resource.Success) {
                data.forEach { comment ->
                    (comment as? CommentItem)?.saved = savedIds.contains(comment.id)
                }
            }
        }
    }.distinctUntilChanged().flowOn(defaultDispatcher)

    private suspend fun getReplies(list: List<FeedItem>, depthLimit: Int): List<FeedItem> =
        withContext(defaultDispatcher) {
            val replies = mutableListOf<FeedItem>()
            for (item in list) {
                replies.add(item)
                if (item is CommentItem && (item.depth ?: 0) < depthLimit) {
                    item.isExpanded = true
                    replies.addAll(getReplies(item.replies, depthLimit))
                }
            }
            return@withContext replies
        }

    private var currentPostId: String? = null
    private var currentFiltering: Filtering? = null

    fun loadPost(forceUpdate: Boolean) {
        val postId = _postId.value
        val service = _service.value

        if (postId != null && service != null) {
            if (
                _postId.value != currentPostId ||
                _filtering.value != currentFiltering ||
                forceUpdate
            ) {
                currentPostId = _postId.value
                currentFiltering = _filtering.value
                loadPost(postId, service, _filtering.value)
            }
        } else {
            _post.value = Resource.Error()
            _replies.value = Resource.Error()
        }
    }

    private fun loadPost(post: String, service: Service, filtering: Filtering) {
        viewModelScope.launch {
            stealthRepository.getPost(post, service, filtering)
                .onStart {
                    _post.value = Resource.Loading()
                    _replies.value = Resource.Loading()
                }
                .collect { response ->
                    when (response) {
                        is Success -> {
                            val item = async { feedableMapper.dataToEntity(response.data.post) }
                            val replies = async {
                                val list =
                                    feedableMapper.dataToEntities(response.data.replies.items)
                                getReplies(list, DEPTH_LIMIT)
                            }
                            _post.value = Resource.Success(item.await())
                            _replies.value = Resource.Success(replies.await())
                        }

                        is Error -> {
                            _post.value = Resource.Error(response.code, response.message)
                            _replies.value = Resource.Error(response.code, response.message)
                        }

                        is Exception -> {
                            _post.value = Resource.Error(throwable = response.throwable)
                            _replies.value = Resource.Error(throwable = response.throwable)
                        }
                    }
                }
        }
    }

    fun setFiltering(filtering: Filtering) {
        _filtering.updateValue(filtering)
    }

    fun setPostId(postId: String) {
        _postId.updateValue(postId)
    }

    fun setService(service: Service) {
        _service.updateValue(service)
    }

    fun setSingleThread(singleThread: Boolean) {
        _singleThread.updateValue(singleThread)
    }

    fun setReplies(replies: List<FeedItem>) {
        _replies.updateValue(Resource.Success(replies))
    }

    companion object {
        private const val DEPTH_LIMIT = 3
        private val DEFAULT_FILTERING = Filtering(Sort.trending)
    }
}
