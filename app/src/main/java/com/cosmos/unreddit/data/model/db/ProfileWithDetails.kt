package com.cosmos.unreddit.data.model.db

import androidx.room.Embedded
import androidx.room.Relation

data class ProfileWithDetails(
    @Embedded
    val profile: Profile,

    @Relation(
        parentColumn = "id",
        entityColumn = "profile_id"
    )
    val subscription: List<Subscription>,

    @Relation(
        parentColumn = "id",
        entityColumn = "profile_id"
    )
    val savedPosts: List<PostItem>,

    @Relation(
        parentColumn = "id",
        entityColumn = "profile_id"
    )
    val savedComments: List<CommentItem>
)
