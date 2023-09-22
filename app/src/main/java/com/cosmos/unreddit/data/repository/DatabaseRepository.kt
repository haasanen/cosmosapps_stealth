package com.cosmos.unreddit.data.repository

import com.cosmos.unreddit.data.local.RedditDatabase
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.PostItem
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseRepository @Inject constructor(
    private val redditDatabase: RedditDatabase
) {

    fun getSavedPosts(profileId: Int): Flow<List<PostItem>> {
        return redditDatabase.postDao().getSavedPostsFromProfile(profileId)
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
}
