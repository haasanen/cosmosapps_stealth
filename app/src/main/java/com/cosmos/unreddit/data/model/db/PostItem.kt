package com.cosmos.unreddit.data.model.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import com.cosmos.unreddit.data.model.Badge
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Reactions
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.Service
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "post",
    primaryKeys = ["id", "profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PostItem @JvmOverloads constructor(
    val service: Service,

    val id: String,

    @ColumnInfo(name = "post_type")
    val postType: PostType,

    val community: String,

    val title: String,

    val author: String,

    val score: Int,

    @ColumnInfo(name = "comment_count")
    val commentCount: Int,

    val url: String,

    @ColumnInfo(name = "ref_link")
    val refLink: String,

    val created: Long,

    val body: String?,

    @Ignore
    var bodyText: RedditText = RedditText(),

    @Ignore
    var previewText: CharSequence? = null,

    val ratio: Double?,

    val domain: String?,

    val edited: Long?,

    val oc: Boolean?,

    val self: Boolean?,

    val nsfw: Boolean?,

    val spoiler: Boolean?,

    val archived: Boolean?,

    val locked: Boolean?,

    val pinned: Boolean?,

    @Ignore
    val reactions: Reactions? = null,

    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    val preview: Media?,

    val media: List<Media>?,

    @Ignore
    val postBadge: Badge? = null,

    @Ignore
    val authorBadge: Badge? = null,

    @ColumnInfo(name = "poster_type")
    val posterType: PosterType,

    @Ignore
    var seen: Boolean = true,

    @Ignore
    var saved: Boolean = true,

    var time: Long = -1,

    @ColumnInfo(name = "profile_id", index = true)
    var profileId: Int = -1
) : FeedItem
