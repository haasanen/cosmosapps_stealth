package com.cosmos.unreddit.data.model

data class User2(
    val service: Service,

    val id: String,

    val name: String,

    val created: Long,

    val icon: Media? = null,

    val header: Media? = null,

    val description: String? = null,

    val subscribers: Int? = null,

    val subscribees: Int? = null,

    val nsfw: Boolean? = null,

    val postCount: Int? = null,

    val commentCount: Int? = null,

    val score: Int? = null,

    val refLink: String? = null
)
