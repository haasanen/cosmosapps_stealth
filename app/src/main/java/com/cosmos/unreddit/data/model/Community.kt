package com.cosmos.unreddit.data.model

import androidx.annotation.ColorInt

data class Community(
    val service: Service,

    val id: String,

    val name: String,

    val created: Long,

    val title: String?,

    val shortDescription: RedditText?,

    val description: RedditText?,

    val icon: Media?,

    val header: Media?,

    val members: Int?,

    val active: Int?,

    val refLink: String?,

    val nsfw: Boolean?,

    @ColorInt
    val color: Int?
)
