package com.cosmos.unreddit.data.local.mapper

import com.cosmos.unreddit.data.model.Block
import com.cosmos.unreddit.data.model.SavedItem
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.util.HtmlParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedMapper2 @Inject constructor(
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<Any, SavedItem>(defaultDispatcher) {

    private val htmlParser: HtmlParser = HtmlParser(defaultDispatcher)

    override suspend fun toEntity(from: Any): SavedItem {
        throw UnsupportedOperationException()
    }

    override suspend fun toEntities(from: List<Any>): List<SavedItem> {
        throw UnsupportedOperationException()
    }

    suspend fun dataToEntity(data: PostItem): SavedItem = withContext(defaultDispatcher) {
        val redditText = htmlParser.separateHtmlBlocks(data.body)
        SavedItem.Post(
            data.apply {
                bodyText = redditText
                previewText = (redditText.blocks.firstOrNull()?.block as? Block.TextBlock)?.text
            }
        )
    }

    suspend fun dataToEntity(data: CommentItem): SavedItem = withContext(defaultDispatcher) {
        SavedItem.Comment(
            data.apply {
                bodyText = htmlParser.separateHtmlBlocks(body)
            }
        )
    }

    suspend fun postsToEntities(data: List<PostItem>): List<SavedItem> = withContext(
        defaultDispatcher
    ) {
        data.map { dataToEntity(it) }
    }

    suspend fun commentsToEntities(data: List<CommentItem>): List<SavedItem> =
        withContext(defaultDispatcher) {
            data.map { dataToEntity(it) }
        }
}
