package com.cosmos.unreddit.data.model.db

import com.cosmos.unreddit.data.model.Service
import kotlinx.parcelize.Parcelize

@Parcelize
data class MoreItem(
    val service: Service,

    val id: String,

    val count: Int,

    val content: List<String>,

    val parentId: String,

    val depth: Int,

    val commentIndicator: Int? = null
) : FeedItem
