package com.cosmos.unreddit.data.local.mapper

import com.cosmos.stealth.sdk.data.model.api.Appendable
import com.cosmos.stealth.sdk.data.model.api.Commentable
import com.cosmos.stealth.sdk.data.model.api.Feedable
import com.cosmos.stealth.sdk.data.model.api.FeedableType
import com.cosmos.stealth.sdk.data.model.api.PostType
import com.cosmos.stealth.sdk.data.model.api.Postable
import com.cosmos.unreddit.data.model.Block
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.db.MoreItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.util.CommentUtil
import com.cosmos.unreddit.util.HtmlParser
import com.cosmos.unreddit.util.LinkUtil
import com.cosmos.unreddit.util.extension.extension
import com.cosmos.unreddit.util.extension.toBadge
import com.cosmos.unreddit.util.extension.toMedia
import com.cosmos.unreddit.util.extension.toPostType
import com.cosmos.unreddit.util.extension.toPosterType
import com.cosmos.unreddit.util.extension.toReactions
import com.cosmos.unreddit.util.extension.toService
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedableMapper @Inject constructor(
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<Feedable, FeedItem>(defaultDispatcher) {

    private val htmlParser: HtmlParser = HtmlParser(defaultDispatcher)

    override suspend fun toEntity(from: Feedable): FeedItem {
        return when (from.type) {
            FeedableType.post -> toPostItem(from as Postable)
            FeedableType.comment -> toCommentItem(from as Commentable)
            FeedableType.more -> toMoreItem(from as Appendable)
        }
    }

    private suspend fun toPostItem(from: Postable): PostItem {
        return with(from) {
            val bodyText = htmlParser.separateHtmlBlocks(body)

            PostItem(
                service.toService(),
                id,
                postType.toPostType(),
                community,
                title,
                author,
                score,
                commentCount,
                url,
                refLink,
                created,
                body,
                bodyText,
                (bodyText.blocks.getOrNull(0)?.block as? Block.TextBlock)?.text,
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
                reactions?.toReactions(),
                getMediaType(),
                preview?.toMedia(),
                media?.map { it.toMedia() },
                postBadge?.toBadge(),
                authorBadge?.toBadge(),
                posterType.toPosterType(),
                seen = false,
                saved = false
            )
        }
    }

    private suspend fun toCommentItem(from: Commentable): CommentItem {
        return with(from) {
            CommentItem(
                service.toService(),
                id,
                postId,
                community,
                body,
                htmlParser.separateHtmlBlocks(body),
                author,
                score,
                refLink,
                created,
                depth,
                replies?.run { toEntities(this) }?.toMutableList() ?: mutableListOf(),
                edited,
                pinned,
                controversial,
                reactions?.toReactions(),
                authorBadge?.toBadge(),
                submitter,
                postAuthor,
                postTitle,
                postRefLink,
                posterType.toPosterType(),
                CommentUtil.getCommentIndicator(depth),
                seen = false,
                saved = false
            )
        }
    }

    private fun toMoreItem(from: Appendable): MoreItem {
        return with(from) {
            MoreItem(
                service.toService(),
                id,
                count,
                content,
                parentId,
                depth,
                CommentUtil.getCommentIndicator(depth),
                seen = false,
                saved = false
            )
        }
    }

    private fun Postable.getMediaType(): MediaType {
        val media = media ?: listOf()
        val domain = domain ?: ""
        val mime by lazy { media.firstOrNull()?.mime ?: "" }
        val extension by lazy { url.extension }

        return when {
            postType == PostType.text -> MediaType.NO_MEDIA
            postType == PostType.link -> MediaType.LINK

            // TODO: Media > 1 -> Generic gallery
            media.size > 1 -> MediaType.REDDIT_GALLERY

            mime.startsWith("image") -> MediaType.IMAGE
            // TODO: startsWith video -> VIDEO (no audio check)
            mime.startsWith("video") -> {
                val alternativeMime = media.getOrNull(1)?.mime ?: ""

                if (alternativeMime.startsWith("audio")) {
                    MediaType.REDDIT_VIDEO
                } else {
                    MediaType.VIDEO
                }
            }

            domain.matches(LinkUtil.IMGUR_LINK) -> {
                when {
                    url.contains("/a/") -> MediaType.IMGUR_ALBUM
                    url.contains("/gallery/") -> MediaType.IMGUR_GALLERY
                    extension.contains("gif") -> MediaType.IMGUR_GIF
                    mime.startsWith("video") -> MediaType.IMGUR_VIDEO
                    mime.startsWith("image") -> MediaType.IMGUR_IMAGE
                    else -> MediaType.IMGUR_LINK
                }
            }

            domain.matches(LinkUtil.GFYCAT_LINK) -> MediaType.GFYCAT

            domain.matches(LinkUtil.REDGIFS_LINK) -> MediaType.REDGIFS

            domain.matches(LinkUtil.STREAMABLE_LINK) -> MediaType.STREAMABLE

            else -> MediaType.LINK
        }
    }
}
