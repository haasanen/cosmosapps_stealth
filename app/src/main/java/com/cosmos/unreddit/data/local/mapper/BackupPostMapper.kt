package com.cosmos.unreddit.data.local.mapper

import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaSource
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.backup.Post
import com.cosmos.unreddit.data.model.backup.Post2
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.di.DispatchersModule
import com.cosmos.unreddit.util.extension.empty
import com.cosmos.unreddit.util.extension.mimeType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupPostMapper @Inject constructor(
    @DispatchersModule.DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<PostItem, Post2>(defaultDispatcher) {

    override suspend fun toEntity(from: PostItem): Post2 {
        return with(from) {
            Post2(
                service,
                id,
                postType,
                community,
                title,
                author,
                score,
                commentCount,
                url,
                refLink,
                created,
                body,
                ratio,
                domain,
                edited,
                oc,
                self,
                nsfw,
                spoiler,
                archived,
                locked,
                pinned,
                mediaType,
                preview,
                media,
                posterType,
                time
            )
        }
    }

    suspend fun dataFromLegacyEntities(
        from: List<Post>
    ): List<PostItem> = withContext(defaultDispatcher) {
        supervisorScope {
            from.map { async { fromEntity(it) } }.awaitAll()
        }
    }

    private fun fromEntity(from: Post): PostItem {
        return with(from) {
            val mediaPreview = preview?.run {
                val mime = mimeType
                if (mime.startsWith("image")) {
                    Media(mime, MediaSource(this), Media.Type.IMAGE)
                } else {
                    null
                }
            }

            val media = mediaUrl.run {
                val mime = mimeType
                if (mime.startsWith("image") || mime.startsWith("video")) {
                    Media(mime, MediaSource(this), Media.Type.fromMime(mime))
                } else {
                    null
                }
            }

            PostItem(
                Service(ServiceName.reddit, String.empty),
                id,
                type,
                subreddit.removePrefix("r/"),
                title,
                author,
                score.toIntOrNull() ?: 0,
                commentsNumber.toIntOrNull() ?: 0,
                url,
                permalink.run { "https://www.reddit.com$this" },
                created,
                selfTextHtml,
                RedditText(),
                null,
                (ratio.toDouble() / 100).takeIf { it >= 0.0 },
                domain,
                null,
                isOC,
                isSelf,
                isOver18,
                isSpoiler,
                isArchived,
                isLocked,
                isStickied,
                null,
                mediaType,
                mediaPreview,
                media?.run { listOf(this) },
                null,
                null,
                posterType,
                seen = true,
                saved = true,
                time
            )
        }
    }

    override suspend fun fromEntity(from: Post2): PostItem {
        return with(from) {
            PostItem(
                service,
                id,
                postType,
                community,
                title,
                author,
                score,
                commentCount,
                url,
                refLink,
                created,
                body,
                RedditText(),
                null,
                ratio,
                domain,
                edited,
                oc,
                self,
                nsfw,
                spoiler,
                archived,
                locked,
                pinned,
                null,
                mediaType,
                preview,
                media,
                null,
                null,
                posterType,
                seen = true,
                saved = true,
                time
            )
        }
    }
}
