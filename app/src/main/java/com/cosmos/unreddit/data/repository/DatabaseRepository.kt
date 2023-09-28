package com.cosmos.unreddit.data.repository

import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.History
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.db.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseRepository @Inject constructor(
    private val redditDatabase: RedditDatabase
) {

    //region Subscriptions

    fun getSubscriptions(profileId: Int): Flow<List<Subscription>> = redditDatabase
        .subscriptionDao().getSubscriptionsFromProfile(profileId).distinctUntilChanged()

    fun getSubscriptionsNames(profileId: Int): Flow<List<String>> {
        return redditDatabase.subscriptionDao().getSubscriptionsNamesFromProfile(profileId)
    }

    suspend fun subscribe(name: String, profileId: Int, service: Service, icon: String? = null) {
        // Instance for Reddit/Teddit is irrelevant (it's managed locally anyway), so it is set to
        // an empty string to avoid duplicate subscriptions (as instance is part of the primary
        // keys)
        val instance = when (service.name) {
            ServiceName.reddit, ServiceName.teddit -> ""
            ServiceName.lemmy -> service.instance.orEmpty()
        }

        redditDatabase.subscriptionDao().upsert(
            Subscription(name, System.currentTimeMillis(), icon, service.name, instance, profileId)
        )
    }

    suspend fun unsubscribe(name: String, profileId: Int) {
        redditDatabase.subscriptionDao().deleteFromNameAndProfile(name, profileId)
    }

    //endregion

    //region Save

    fun getSavedPosts(profileId: Int): Flow<List<PostItem>> {
        return redditDatabase.postDao().getSavedPostsFromProfile(profileId)
    }

    fun getSavedPostIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.postDao().getSavedPostIdsFromProfile(profileId)
    }

    suspend fun savePost(post: PostItem, profileId: Int) {
        post.run {
            this.profileId = profileId
            this.time = System.currentTimeMillis()
            redditDatabase.postDao().upsert(this)
        }
    }

    suspend fun unsavePost(post: PostItem, profileId: Int) {
        redditDatabase.postDao().deleteFromIdAndProfile(post.id, profileId)
    }

    fun getSavedComments(profileId: Int): Flow<List<CommentItem>> {
        return redditDatabase.commentDao().getSavedCommentsFromProfile(profileId)
    }

    fun getSavedCommentIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.commentDao().getSavedCommentIdsFromProfile(profileId)
    }

    suspend fun saveComment(comment: CommentItem, profileId: Int) {
        comment.run {
            this.profileId = profileId
            this.time = System.currentTimeMillis()
            redditDatabase.commentDao().upsert(comment)
        }
    }

    suspend fun unsaveComment(comment: CommentItem, profileId: Int) {
        redditDatabase.commentDao().deleteFromIdAndProfile(comment.id, profileId)
    }

    //endregion

    //region History

    fun getHistoryIds(profileId: Int): Flow<List<String>> {
        return redditDatabase.historyDao().getHistoryIdsFromProfile(profileId)
    }

    suspend fun insertPostInHistory(postId: String, profileId: Int) {
        redditDatabase.historyDao().upsert(History(postId, System.currentTimeMillis(), profileId))
    }

    //endregion

    //region Profile

    suspend fun addProfile(name: String) {
        redditDatabase.profileDao().insert(Profile(name = name))
    }

    suspend fun getProfile(id: Int): Profile {
        return redditDatabase.profileDao().getProfileFromId(id)
            ?: redditDatabase.profileDao().getFirstProfile()
    }

    fun getAllProfiles(): Flow<List<Profile>> {
        return redditDatabase.profileDao().getAllProfiles()
    }

    suspend fun deleteProfile(profileId: Int) {
        redditDatabase.profileDao().deleteFromId(profileId)
    }

    suspend fun updateProfile(profile: Profile) {
        redditDatabase.profileDao().update(profile)
    }

    //endregion
}
