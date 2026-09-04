package com.cosmos.unreddit.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cosmos.unreddit.data.model.db.FeedCache

/**
 * Pure-cache DAO: every row can be deleted without breaking a reference.
 * All operations are profile-scoped so a profile deletion (FK CASCADE) or a
 * per-profile purge can never touch another profile's data.
 *
 * Subreddit matching is case-insensitive (LOWER) throughout: a profile stores
 * subreddit names in the user's casing while the SSR parser stores the display
 * name (e.g. "DataHoarder"), and reddit treats subreddit names case-insensitively.
 */
@Dao
abstract class FeedCacheDao {

    @Query("SELECT * FROM feed_cache WHERE profile_id = :profileId")
    abstract suspend fun allFromProfile(profileId: Int): List<FeedCache>

    @Query("SELECT * FROM feed_cache WHERE profile_id = :profileId AND LOWER(subreddit) = LOWER(:subreddit)")
    abstract suspend fun bySubreddit(profileId: Int, subreddit: String): List<FeedCache>

    /**
     * Delete every cached row for one subreddit of one profile.
     * Case-insensitive so a casing drift between the profile name and the
     * stored display name cannot strand stale rows.
     */
    @Query("DELETE FROM feed_cache WHERE profile_id = :profileId AND LOWER(subreddit) = LOWER(:subreddit)")
    abstract suspend fun deleteBySubreddit(profileId: Int, subreddit: String): Int

    /**
     * Per-subreddit ATOMIC replace: delete the subreddit's existing rows and write
     * [newRows] in a single transaction, so a crash mid-write can never leave the
     * sub half-replaced (a mix of its old and new posts). An empty [newRows] list
     * clears the sub's cache (a subreddit that came back genuinely empty).
     */
    @Transaction
    open suspend fun replaceSubreddit(profileId: Int, subreddit: String, newRows: List<FeedCache>) {
        deleteBySubreddit(profileId, subreddit)
        if (newRows.isNotEmpty()) upsertAll(newRows)
    }

    @Query("SELECT * FROM feed_cache WHERE profile_id = :profileId AND post_id = :postId LIMIT 1")
    abstract suspend fun byPostId(profileId: Int, postId: String): FeedCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(rows: List<FeedCache>)

    @Query("DELETE FROM feed_cache WHERE profile_id = :profileId AND fetched_at < :cutoff")
    abstract suspend fun purgeOlderThan(profileId: Int, cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM feed_cache WHERE profile_id = :profileId")
    abstract suspend fun count(profileId: Int): Int

    @Query("SELECT post_id FROM feed_cache WHERE profile_id = :profileId " +
        "ORDER BY fetched_at ASC, post_id ASC LIMIT :limit")
    abstract suspend fun oldestIds(profileId: Int, limit: Int): List<String>

    @Query("DELETE FROM feed_cache WHERE profile_id = :profileId AND post_id IN (:postIds)")
    abstract suspend fun deleteByIds(profileId: Int, postIds: List<String>): Int

    @Query("DELETE FROM feed_cache WHERE profile_id = :profileId")
    abstract suspend fun wipeProfile(profileId: Int): Int

    /**
     * Total on-disk size of the database file in bytes (page_count * page_size).
     * Used by the feed-cache size tripwire.
     */
    @Query("SELECT (page_count * page_size) AS size FROM pragma_page_count(), pragma_page_size()")
    abstract suspend fun databaseSizeBytes(): Long
}
