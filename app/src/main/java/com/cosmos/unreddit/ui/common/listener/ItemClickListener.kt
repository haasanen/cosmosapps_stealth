package com.cosmos.unreddit.ui.common.listener

import com.cosmos.unreddit.data.model.db.FeedItem

interface ItemClickListener {
    fun onClick(item: FeedItem)

    fun onLongClick(item: FeedItem)

    fun onMenuClick(item: FeedItem)

    fun onMediaClick(item: FeedItem)

    fun onLinkClick(item: FeedItem)

    fun onSaveClick(item: FeedItem)
}
