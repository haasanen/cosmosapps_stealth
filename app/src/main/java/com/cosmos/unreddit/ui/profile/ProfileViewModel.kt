package com.cosmos.unreddit.ui.profile

import com.cosmos.unreddit.data.local.mapper.SavedMapper2
import com.cosmos.unreddit.data.model.SavedItem
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.preferences.ContentPreferences
import com.cosmos.unreddit.data.repository.DatabaseRepository
import com.cosmos.unreddit.data.repository.PreferencesRepository
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.ui.base.BaseViewModel
import com.cosmos.unreddit.util.extension.orFalse
import com.cosmos.unreddit.util.extension.updateValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    preferencesRepository: PreferencesRepository,
    databaseRepository: DatabaseRepository,
    private val savedMapper: SavedMapper2,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : BaseViewModel(preferencesRepository, databaseRepository) {

    val contentPreferences: Flow<ContentPreferences> = preferencesRepository.getContentPreferences()

    private val _page: MutableStateFlow<Int> = MutableStateFlow(0)
    val page: StateFlow<Int> get() = _page

    var layoutState: Int? = null

    private val _savedPosts: Flow<List<PostItem>> = currentProfile.flatMapLatest {
        databaseRepository.getSavedPosts(it.id)
    }

    private val _savedComments: Flow<List<CommentItem>> = currentProfile.flatMapLatest {
        databaseRepository.getSavedComments(it.id)
    }

    val selectedProfile: Flow<Profile> = combine(
        currentProfile,
        databaseRepository.getAllProfiles()
    ) { currentProfile, profiles ->
        // Update current profile when any profile is updated
        profiles.find { it.id == currentProfile.id } ?: currentProfile
    }

    val savedItems: Flow<List<SavedItem>> = combineTransform(
        _savedPosts,
        _savedComments,
        contentPreferences
    ) { _posts, _comments, preferences ->
        coroutineScope {
            val posts = async {
                savedMapper.postsToEntities(_posts).filter {
                    preferences.showNsfw || !(it as SavedItem.Post).post.nsfw.orFalse()
                }
            }

            val comments = async {
                savedMapper.commentsToEntities(_comments)
            }

            val items = mutableListOf<SavedItem>().apply {
                addAll(posts.await())
                addAll(comments.await())
            }

            emit(items)
        }
    }.map { items ->
        items.sortedByDescending { it.timestamp }
    }.flowOn(defaultDispatcher)

    fun setPage(position: Int) {
        _page.updateValue(position)
    }
}
