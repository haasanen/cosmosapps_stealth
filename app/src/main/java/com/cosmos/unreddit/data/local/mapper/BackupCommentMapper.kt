package com.cosmos.unreddit.data.local.mapper

import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.unreddit.data.model.RedditText
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.backup.Comment
import com.cosmos.unreddit.data.model.backup.Comment2
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.di.DispatchersModule
import com.cosmos.unreddit.util.extension.empty
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupCommentMapper @Inject constructor(
    @DispatchersModule.DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<CommentItem, Comment2>(defaultDispatcher) {

    override suspend fun toEntity(from: CommentItem): Comment2 {
        return with(from) {
            Comment2(
                service,
                id,
                postId,
                community,
                body,
                author,
                score,
                refLink,
                created,
                depth,
                edited,
                pinned,
                controversial,
                submitter,
                postAuthor,
                postTitle,
                postRefLink,
                posterType,
                time
            )
        }
    }

    suspend fun dataFromLegacyEntities(
        from: List<Comment>
    ): List<CommentItem> = withContext(defaultDispatcher) {
        supervisorScope {
            from.map { async { fromEntity(it) } }.awaitAll()
        }
    }

    private fun fromEntity(from: Comment): CommentItem {
        return with(from) {
            CommentItem(
                Service(ServiceName.reddit, String.empty),
                id,
                linkId,
                subreddit.removePrefix("r/"),
                bodyHtml,
                RedditText(),
                author,
                score.toIntOrNull() ?: 0,
                permalink.run { "https://www.reddit.com$this" },
                created,
                null,
                mutableListOf(),
                edited.takeIf { it > -1 },
                stickied,
                controversiality > 0,
                null,
                null,
                isSubmitter,
                linkAuthor,
                linkTitle,
                linkPermalink?.run { "https://www.reddit.com$this" },
                posterType,
                null,
                seen = true,
                saved = true,
                time
            )
        }
    }

    override suspend fun fromEntity(from: Comment2): CommentItem {
        return with(from) {
            CommentItem(
                service,
                id,
                postId,
                community,
                body,
                RedditText(),
                author,
                score,
                refLink,
                created,
                depth,
                mutableListOf(),
                edited,
                pinned,
                controversial,
                null,
                null,
                submitter,
                postAuthor,
                postTitle,
                postRefLink,
                posterType,
                null,
                seen = true,
                saved = true,
                time
            )
        }
    }
}
