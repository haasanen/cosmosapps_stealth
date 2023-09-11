package com.cosmos.unreddit.data.model.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.Badge
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Reactions
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.util.DateUtil
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "comment",
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
data class CommentItem @JvmOverloads constructor(
    val service: Service,

    override val id: String,

    @ColumnInfo(name = "post_id")
    val postId: String,

    val community: String,

    val body: String,

    @Ignore
    var bodyText: RedditText = RedditText(),

    val author: String,

    val score: Int,

    @ColumnInfo(name = "ref_link")
    val refLink: String,

    val created: Long,

    val depth: Int?,

    @Ignore
    val replies: MutableList<FeedItem> = mutableListOf(),

    val edited: Long?,

    val pinned: Boolean?,

    val controversial: Boolean?,

    @Ignore
    val reactions: Reactions? = null,

    @Ignore
    val authorBadge: Badge? = null,

    val submitter: Boolean,

    @ColumnInfo(name = "post_author")
    val postAuthor: String?,

    @ColumnInfo(name = "post_title")
    val postTitle: String?,

    @ColumnInfo(name = "post_ref_link")
    val postRefLink: String?,

    @ColumnInfo(name = "poster_type")
    val posterType: PosterType,

    @Ignore
    val commentIndicator: Int? = null,

    @Ignore
    override var seen: Boolean = true,

    @Ignore
    override var saved: Boolean = true,

    var time: Long = -1,

    @ColumnInfo(name = "profile_id", index = true)
    var profileId: Int = -1
) : FeedItem {

    @Ignore
    @IgnoredOnParcel
    var isExpanded: Boolean = false

    @Ignore
    @IgnoredOnParcel
    var visibleReplyCount: Int = replies.size

    val hasReplies: Boolean
        get() = replies.isNotEmpty()

    fun getTimeDifference(context: Context): String {
        val timeDifference = DateUtil.getTimeDifference(context, created)
        return if (edited != null) {
            val editedTimeDifference = DateUtil.getTimeDifference(context, edited, false)
            context.getString(R.string.comment_date_edited, timeDifference, editedTimeDifference)
        } else {
            timeDifference
        }
    }
}
