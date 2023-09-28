package com.cosmos.unreddit.data.local

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.getStringOrNull
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.unreddit.data.local.dao.CommentDao
import com.cosmos.unreddit.data.local.dao.HistoryDao
import com.cosmos.unreddit.data.local.dao.PostDao
import com.cosmos.unreddit.data.local.dao.ProfileDao
import com.cosmos.unreddit.data.local.dao.RedirectDao
import com.cosmos.unreddit.data.local.dao.SubscriptionDao
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaSource
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.History
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.data.model.db.Profile
import com.cosmos.unreddit.data.model.db.Redirect
import com.cosmos.unreddit.data.model.db.Subscription
import com.cosmos.unreddit.util.extension.mimeType

@Suppress("MagicNumber")
@Database(
    entities = [
        Subscription::class,
        History::class,
        Profile::class,
        PostItem::class,
        CommentItem::class,
        Redirect::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RedditDatabase : RoomDatabase() {

    abstract fun subscriptionDao(): SubscriptionDao

    abstract fun historyDao(): HistoryDao

    abstract fun profileDao(): ProfileDao

    abstract fun postDao(): PostDao

    abstract fun commentDao(): CommentDao

    abstract fun redirectDao(): RedirectDao

    class Callback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            insertDefaultProfile(db)
        }
    }

    companion object {
        private const val DEFAULT_PROFILE_ID = 1
        private const val DEFAULT_PROFILE_NAME = "Stealth"
        private val DEFAULT_SERVICE_NAME = ServiceName.reddit.value

        private fun insertDefaultProfile(database: SupportSQLiteDatabase) {
            database.execSQL("INSERT INTO profile (id, name) VALUES($DEFAULT_PROFILE_ID, '$DEFAULT_PROFILE_NAME')")
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            @Suppress("LongMethod")
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `profile` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                insertDefaultProfile(db)

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `new_subscription` (
                        `name` TEXT NOT NULL COLLATE NOCASE, 
                        `time` INTEGER NOT NULL, 
                        `icon` TEXT, 
                        `profile_id` INTEGER DEFAULT $DEFAULT_PROFILE_ID NOT NULL, 
                    PRIMARY KEY(`name`, `profile_id`), 
                    FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO new_subscription (name, time, icon) 
                    SELECT name, time, icon FROM subscription
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE subscription")
                db.execSQL("ALTER TABLE new_subscription RENAME TO subscription")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `new_history` (
                        `post_id` TEXT NOT NULL, 
                        `time` INTEGER NOT NULL, 
                        `profile_id` INTEGER DEFAULT $DEFAULT_PROFILE_ID NOT NULL, 
                    PRIMARY KEY(`post_id`, `profile_id`), 
                    FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                    """.trimIndent()
                )
                db.execSQL("INSERT INTO new_history (post_id, time) SELECT post_id, time FROM history")
                db.execSQL("DROP TABLE history")
                db.execSQL("ALTER TABLE new_history RENAME TO history")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `post` (
                        `id` TEXT NOT NULL, 
                        `subreddit` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `ratio` INTEGER NOT NULL, 
                        `total_awards` INTEGER NOT NULL, 
                        `oc` INTEGER NOT NULL, 
                        `score` TEXT NOT NULL, 
                        `type` INTEGER NOT NULL, 
                        `domain` TEXT NOT NULL, 
                        `self` INTEGER NOT NULL, 
                        `self_text_html` TEXT, 
                        `suggested_sorting` TEXT NOT NULL, 
                        `nsfw` INTEGER NOT NULL, 
                        `preview` TEXT, 
                        `spoiler` INTEGER NOT NULL, 
                        `archived` INTEGER NOT NULL, 
                        `locked` INTEGER NOT NULL, 
                        `poster_type` INTEGER NOT NULL, 
                        `author` TEXT NOT NULL, 
                        `comments_number` TEXT NOT NULL, 
                        `permalink` TEXT NOT NULL, 
                        `stickied` INTEGER NOT NULL, 
                        `url` TEXT NOT NULL, 
                        `created` INTEGER NOT NULL, 
                        `media_type` TEXT NOT NULL, 
                        `media_url` TEXT NOT NULL, 
                        `time` INTEGER NOT NULL, 
                        `profile_id` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`, `profile_id`), 
                    FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `comment` (
                        `total_awards` INTEGER NOT NULL, 
                        `link_id` TEXT NOT NULL, 
                        `author` TEXT NOT NULL, 
                        `score` TEXT NOT NULL, 
                        `body_html` TEXT NOT NULL, 
                        `edited` INTEGER NOT NULL, 
                        `submitter` INTEGER NOT NULL, 
                        `stickied` INTEGER NOT NULL, 
                        `score_hidden` INTEGER NOT NULL, 
                        `permalink` TEXT NOT NULL, 
                        `id` TEXT NOT NULL, 
                        `created` INTEGER NOT NULL, 
                        `controversiality` INTEGER NOT NULL, 
                        `poster_type` INTEGER NOT NULL, 
                        `link_title` TEXT, 
                        `link_permalink` TEXT, 
                        `link_author` TEXT, 
                        `subreddit` TEXT NOT NULL, 
                        `name` TEXT NOT NULL, 
                        `time` INTEGER NOT NULL, 
                        `profile_id` INTEGER NOT NULL, 
                    PRIMARY KEY(`name`, `profile_id`), 
                    FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_profile_id` ON `history` (`profile_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_profile_id` ON `post` (`profile_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_profile_id` ON `subscription` (`profile_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comment_profile_id` ON `comment` (`profile_id`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `redirect` (
                        `pattern` TEXT NOT NULL, 
                        `redirect` TEXT NOT NULL, 
                        `service` TEXT NOT NULL, 
                        `mode` INTEGER NOT NULL, 
                        PRIMARY KEY(`service`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            private val converters = Converters()

            @Suppress("LongMethod")
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `new_subscription` (
                        `name` TEXT NOT NULL COLLATE NOCASE, 
                        `time` INTEGER NOT NULL, 
                        `icon` TEXT, 
                        `service` TEXT DEFAULT $DEFAULT_SERVICE_NAME NOT NULL, 
                        `instance` TEXT NOT NULL COLLATE NOCASE, 
                        `profile_id` INTEGER NOT NULL, 
                        PRIMARY KEY(`name`, `service`, `instance`, `profile_id`), 
                        FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO new_subscription (name, time, icon, profile_id) 
                    SELECT name, time, icon, profile_id FROM subscription
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE subscription")
                db.execSQL("ALTER TABLE new_subscription RENAME TO subscription")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subscription_profile_id` ON `subscription` (`profile_id`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `new_post` (
                        `service` TEXT NOT NULL, 
                        `id` TEXT NOT NULL, 
                        `post_type` INTEGER NOT NULL, 
                        `community` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `author` TEXT NOT NULL, 
                        `score` INTEGER NOT NULL, 
                        `comment_count` INTEGER NOT NULL, 
                        `url` TEXT NOT NULL, 
                        `ref_link` TEXT NOT NULL, 
                        `created` INTEGER NOT NULL, 
                        `body` TEXT, 
                        `ratio` REAL, 
                        `domain` TEXT, 
                        `edited` INTEGER, 
                        `oc` INTEGER, 
                        `self` INTEGER, 
                        `nsfw` INTEGER, 
                        `spoiler` INTEGER, 
                        `archived` INTEGER, 
                        `locked` INTEGER, 
                        `pinned` INTEGER, 
                        `media_type` TEXT NOT NULL, 
                        `preview` TEXT, 
                        `media` TEXT, 
                        `poster_type` INTEGER NOT NULL, 
                        `time` INTEGER NOT NULL, 
                        `profile_id` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`, `profile_id`), 
                        FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """.trimIndent())
                val postList = db.query("SELECT * FROM post").use { toPostItemList(it) }
                postList.forEach { it.insert(db) }
                db.execSQL("DROP TABLE post")
                db.execSQL("ALTER TABLE new_post RENAME TO post")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_post_profile_id` ON `post` (`profile_id`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `new_comment` (
                        `service` TEXT NOT NULL, 
                        `id` TEXT NOT NULL, 
                        `post_id` TEXT NOT NULL, 
                        `community` TEXT NOT NULL, 
                        `body` TEXT NOT NULL, 
                        `author` TEXT NOT NULL, 
                        `score` INTEGER NOT NULL, 
                        `ref_link` TEXT NOT NULL, 
                        `created` INTEGER NOT NULL, 
                        `depth` INTEGER, 
                        `edited` INTEGER, 
                        `pinned` INTEGER, 
                        `controversial` INTEGER, 
                        `submitter` INTEGER NOT NULL, 
                        `post_author` TEXT, 
                        `post_title` TEXT, 
                        `post_ref_link` TEXT, 
                        `poster_type` INTEGER NOT NULL, 
                        `time` INTEGER NOT NULL, 
                        `profile_id` INTEGER NOT NULL, 
                        PRIMARY KEY(`id`, `profile_id`), 
                        FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE 
                    )
                """.trimIndent())
                val commentList = db.query("SELECT * FROM comment").use { toCommentItemList(it) }
                commentList.forEach { it.insert(db) }
                db.execSQL("DROP TABLE comment")
                db.execSQL("ALTER TABLE new_comment RENAME TO comment")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comment_profile_id` ON `comment` (`profile_id`)")
            }

            @Suppress("Range", "LongMethod", "NestedBlockDepth")
            private fun toPostItemList(cursor: Cursor): List<PostItem> {
                val postList = mutableListOf<PostItem>()

                if (cursor.moveToFirst()) {
                    do {
                        val preview = cursor.getStringOrNull(cursor.getColumnIndex("preview"))
                        val mediaPreview = preview?.run {
                            val mime = mimeType
                            if (mime.startsWith("image")) {
                                Media(mime, MediaSource(this), Media.Type.IMAGE)
                            } else {
                                null
                            }
                        }

                        val mediaUrl = cursor.getString(cursor.getColumnIndex("media_url"))
                        val media = mediaUrl.run {
                            val mime = mimeType
                            if (mime.startsWith("image") || mime.startsWith("video")) {
                                Media(mime, MediaSource(this), Media.Type.fromMime(mime))
                            } else {
                                null
                            }
                        }

                        val post = PostItem(
                            Service(ServiceName.reddit, "www.reddit.com"),
                            cursor.getString(cursor.getColumnIndex("id")),
                            PostType.toType(cursor.getInt(cursor.getColumnIndex("type"))),
                            cursor.getString(cursor.getColumnIndex("subreddit")).removePrefix("r/"),
                            cursor.getString(cursor.getColumnIndex("title")),
                            cursor.getString(cursor.getColumnIndex("author")),
                            cursor.getString(cursor.getColumnIndex("score")).toIntOrNull() ?: 0,
                            cursor.getString(cursor.getColumnIndex("comments_number")).toIntOrNull()
                                ?: 0,
                            cursor.getString(cursor.getColumnIndex("url")),
                            cursor.getString(cursor.getColumnIndex("permalink"))
                                .run { "https://www.reddit.com$this" },
                            cursor.getLong(cursor.getColumnIndex("created")),
                            cursor.getStringOrNull(cursor.getColumnIndex("selfTextHtml")),
                            RedditText(),
                            null,
                            (cursor.getInt(cursor.getColumnIndex("ratio"))
                                .toDouble() / 100).takeIf { it >= 0.0 },
                            cursor.getString(cursor.getColumnIndex("domain")),
                            null,
                            cursor.getInt(cursor.getColumnIndex("oc")) > 0,
                            cursor.getInt(cursor.getColumnIndex("self")) > 0,
                            cursor.getInt(cursor.getColumnIndex("nsfw")) > 0,
                            cursor.getInt(cursor.getColumnIndex("spoiler")) > 0,
                            cursor.getInt(cursor.getColumnIndex("archived")) > 0,
                            cursor.getInt(cursor.getColumnIndex("locked")) > 0,
                            cursor.getInt(cursor.getColumnIndex("stickied")) > 0,
                            null,
                            MediaType.toMediaType(cursor.getString(cursor.getColumnIndex("media_type"))),
                            mediaPreview,
                            media?.run { listOf(this) },
                            null,
                            null,
                            PosterType.toType(cursor.getInt(cursor.getColumnIndex("poster_type"))),
                            seen = true,
                            saved = true,
                            cursor.getLong(cursor.getColumnIndex("time")),
                            profileId = cursor.getInt(cursor.getColumnIndex("profile_id"))
                        )

                        postList.add(post)
                    } while (cursor.moveToNext())
                }

                return postList.toList()
            }

            private fun PostItem.insert(db: SupportSQLiteDatabase) {
                val values = ContentValues().apply {
                    put("service", converters.toSerializedService(service))
                    put("id", id)
                    put("post_type", converters.toPostTypeInt(postType))
                    put("community", community)
                    put("title", title)
                    put("author", author)
                    put("score", score)
                    put("comment_count", commentCount)
                    put("url", url)
                    put("ref_link", refLink)
                    put("created", created)
                    put("body", body)
                    put("ratio", ratio)
                    put("domain", domain)
                    put("edited", edited)
                    put("oc", oc)
                    put("self", self)
                    put("nsfw", nsfw)
                    put("spoiler", spoiler)
                    put("archived", archived)
                    put("locked", locked)
                    put("pinned", pinned)
                    put("media_type", mediaType.name)
                    put("preview", converters.toSerializedMedia(preview))
                    put("media", converters.toSerializedMediaList(media))
                    put("poster_type", converters.toPosterTypeInt(posterType))
                    put("time", time)
                    put("profile_id", profileId)
                }

                db.insert("new_post", SQLiteDatabase.CONFLICT_IGNORE, values)
            }

            @SuppressLint("Range")
            private fun toCommentItemList(cursor: Cursor): List<CommentItem> {
                val commentList = mutableListOf<CommentItem>()

                if (cursor.moveToFirst()) {
                    do {
                        val comment = CommentItem(
                            Service(ServiceName.reddit, "www.reddit.com"),
                            cursor.getString(cursor.getColumnIndex("name")),
                            cursor.getString(cursor.getColumnIndex("link_id")),
                            cursor.getString(cursor.getColumnIndex("subreddit")).removePrefix("r/"),
                            cursor.getString(cursor.getColumnIndex("body_html")),
                            RedditText(),
                            cursor.getString(cursor.getColumnIndex("author")),
                            cursor.getString(cursor.getColumnIndex("score")).toIntOrNull() ?: 0,
                            cursor.getString(cursor.getColumnIndex("permalink"))
                                .run { "https://www.reddit.com$this" },
                            cursor.getLong(cursor.getColumnIndex("created")),
                            null,
                            mutableListOf(),
                            cursor.getLong(cursor.getColumnIndex("edited")).takeIf { it > -1 },
                            cursor.getInt(cursor.getColumnIndex("stickied")) > 0,
                            cursor.getInt(cursor.getColumnIndex("controversiality")) > 0,
                            null,
                            null,
                            cursor.getInt(cursor.getColumnIndex("submitter")) > 0,
                            cursor.getStringOrNull(cursor.getColumnIndex("link_author")),
                            cursor.getStringOrNull(cursor.getColumnIndex("link_title")),
                            cursor.getStringOrNull(cursor.getColumnIndex("link_permalink"))
                                ?.run { "https://www.reddit.com$this" },
                            PosterType.toType(cursor.getInt(cursor.getColumnIndex("poster_type"))),
                            null,
                            seen = true,
                            saved = true,
                            cursor.getLong(cursor.getColumnIndex("time")),
                            profileId = cursor.getInt(cursor.getColumnIndex("profile_id"))
                        )

                        commentList.add(comment)
                    } while (cursor.moveToNext())
                }

                return commentList.toList()
            }

            private fun CommentItem.insert(db: SupportSQLiteDatabase) {
                val values = ContentValues().apply {
                    put("service", converters.toSerializedService(service))
                    put("id", id)
                    put("post_id", postId)
                    put("community", community)
                    put("body", body)
                    put("author", author)
                    put("score", score)
                    put("ref_link", refLink)
                    put("created", created)
                    put("depth", depth)
                    put("edited", edited)
                    put("pinned", pinned)
                    put("controversial", controversial)
                    put("submitter", submitter)
                    put("post_author", postAuthor)
                    put("post_title", postTitle)
                    put("post_ref_link", postRefLink)
                    put("poster_type", converters.toPosterTypeInt(posterType))
                    put("time", time)
                    put("profile_id", profileId)
                }

                db.insert("new_comment", SQLiteDatabase.CONFLICT_IGNORE, values)
            }
        }
    }
}
