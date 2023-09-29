package com.cosmos.unreddit.data.local.mapper

import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.backup.Post
import com.cosmos.unreddit.data.model.backup.Post2
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.di.DispatchersModule
import com.cosmos.unreddit.util.addInstancePrefix
import com.cosmos.unreddit.util.asMedia
import com.cosmos.unreddit.util.asPreviewMedia
import com.cosmos.unreddit.util.extension.empty
import com.cosmos.unreddit.util.removeSubredditPrefix
import com.cosmos.unreddit.util.toRatio
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
            PostItem(
                Service(ServiceName.reddit, String.empty),
                id,
                type,
                subreddit.removeSubredditPrefix(),
                title,
                author,
                score.toIntOrNull() ?: 0,
                commentsNumber.toIntOrNull() ?: 0,
                url,
                permalink.addInstancePrefix(),
                created,
                selfTextHtml,
                RedditText(),
                null,
                ratio.toRatio(),
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
                preview?.asPreviewMedia(),
                mediaUrl.asMedia()?.run { listOf(this) },
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
