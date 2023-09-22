package com.cosmos.unreddit.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PostListRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.util.extension.latest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

// TODO: Migration V3
//  Remove PostListRepository
//  Remove nullability for DatabaseRepository
open class BaseViewModel(
    preferencesRepository: PreferencesRepository,
    private val postListRepository: PostListRepository,
    private val databaseRepository: DatabaseRepository? = null
) : ViewModel() {

    val currentProfile: SharedFlow<Profile> = preferencesRepository.getCurrentProfile().map {
        postListRepository.getProfile(it)
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(), 1)

    protected val historyIds: Flow<List<String>> = currentProfile.flatMapLatest {
        postListRepository.getHistoryIds(it.id)
    }

    protected val subscriptions: Flow<List<Subscription>> = currentProfile.flatMapLatest {
        postListRepository.getSubscriptions(it.id)
    }

    protected val subscriptionsNames: Flow<List<String>> = currentProfile.flatMapLatest {
        postListRepository.getSubscriptionsNames(it.id)
    }

    protected val savedPostIds: Flow<List<String>> = currentProfile.flatMapLatest {
        postListRepository.getSavedPostIds(it.id)
    }

    fun insertPostInHistory(postId: String) {
        viewModelScope.launch {
            currentProfile.latest?.let {
                postListRepository.insertPostInHistory(postId, it.id)
            }
        }
    }

    fun toggleSavePost(post: PostItem) {
        viewModelScope.launch {
            currentProfile.latest?.let {
                if (post.saved) {
                    databaseRepository?.unsavePost(post, it.id)
                } else {
                    databaseRepository?.savePost(post, it.id)
                }
            }
        }
    }

    fun toggleSaveComment(comment: CommentItem) {
        viewModelScope.launch {
            currentProfile.latest?.let {
                if (comment.saved) {
                    databaseRepository?.unsaveComment(comment, it.id)
                } else {
                    databaseRepository?.saveComment(comment, it.id)
                }
            }
        }
    }
}
