package com.cosmos.unreddit.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.cosmos.unreddit.data.model.db.CommentItem
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CommentDao : BaseDao<CommentItem> {

    @Query("DELETE FROM comment WHERE id = :id AND profile_id = :profileId")
    abstract suspend fun deleteFromIdAndProfile(id: String, profileId: Int)

    @Query("DELETE FROM comment WHERE profile_id = :profileId")
    abstract suspend fun deleteFromProfile(profileId: Int)

    @Query("SELECT id FROM comment WHERE profile_id = :profileId")
    abstract fun getSavedCommentIdsFromProfile(profileId: Int): Flow<List<String>>

    @Query("SELECT * FROM comment WHERE profile_id = :profileId")
    abstract fun getSavedCommentsFromProfile(profileId: Int): Flow<List<CommentItem>>
}
