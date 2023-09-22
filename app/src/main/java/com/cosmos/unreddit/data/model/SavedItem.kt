package com.cosmos.unreddit.data.model

import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.PostItem

sealed class SavedItem(val timestamp: Long) {

    data class Post(val post: PostItem) : SavedItem(post.time)

    data class Comment(
        val comment: CommentItem
    ) : SavedItem(comment.time)
}
