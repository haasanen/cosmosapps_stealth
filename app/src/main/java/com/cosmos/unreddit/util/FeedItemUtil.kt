package com.cosmos.unreddit.util

import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.map
import com.cosmos.stealth.sdk.data.model.api.Feedable
import com.cosmos.unreddit.data.local.mapper.FeedableMapper
import com.cosmos.unreddit.data.model.Data
import com.cosmos.unreddit.data.model.db.FeedItem
import com.cosmos.unreddit.data.model.db.PostItem
import com.cosmos.unreddit.util.extension.orFalse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

suspend fun PagingData<out Feedable>.mapFilter(
    user: Data.User,
    feedableMapper: FeedableMapper,
    defaultDispatcher: CoroutineDispatcher
): PagingData<FeedItem> = withContext(defaultDispatcher) {
    this@mapFilter
        .map { feedableMapper.dataToEntity(it) }
        .filter { item ->
            user.contentPreferences.showNsfw || !(item as? PostItem)?.nsfw.orFalse()
        }
        .map { post ->
            post.apply {
                seen = user.history.contains(post.id)
                saved = user.saved.contains(post.id)
            }
        }
}
