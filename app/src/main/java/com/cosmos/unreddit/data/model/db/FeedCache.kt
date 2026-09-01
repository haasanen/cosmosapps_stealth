package com.cosmos.unreddit.data.model.db

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.parcelize.Parcelize

/**
 * A transient snapshot of a post as last fetched for the home feed / a subreddit view.
 *
 * This is PURE CACHE: nothing else in the database references these rows, so they can
 * always be deleted without stranding a reference (no storage leak by construction).
 * The user's curated "saved posts" live in the separate [com.cosmos.unreddit.data.model.db.PostEntity]
 * table and are NOT touched by cache purging.
 *
 * [postJson] stores the full post payload as JSON so the cache schema does not have to
 * migrate whenever the remote post model changes.
 */
@Parcelize
@Entity(
    tableName = "feed_cache",
    primaryKeys = ["post_id", "profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("fetched_at"),
        Index("subreddit", "profile_id"),
        Index(value = ["permalink", "profile_id"], unique = true)
    ]
)
data class FeedCache @JvmOverloads constructor(
    @ColumnInfo(name = "post_id")
    val postId: String,

    val subreddit: String,

    /** The post's canonical permalink (unique per post) — used to look a post up for the detail screen. */
    val permalink: String,

    val postJson: String,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,

    @ColumnInfo(name = "profile_id")
    val profileId: Int
) : Parcelable
