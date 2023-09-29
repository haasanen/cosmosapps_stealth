package com.cosmos.unreddit.data.model.backup

import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaType
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Service
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Post2(
    val service: Service,

    val id: String,

    @Json(name = "post_type")
    val postType: PostType,

    val community: String,

    val title: String,

    val author: String,

    val score: Int,

    @Json(name = "comment_count")
    val commentCount: Int,

    val url: String,

    @Json(name = "ref_link")
    val refLink: String,

    val created: Long,

    val body: String?,

    val ratio: Double?,

    val domain: String?,

    val edited: Long?,

    val oc: Boolean?,

    val self: Boolean?,

    val nsfw: Boolean?,

    val spoiler: Boolean?,

    val archived: Boolean?,

    val locked: Boolean?,

    val pinned: Boolean?,

    @Json(name = "media_type")
    val mediaType: MediaType,

    val preview: Media?,

    val media: List<Media>?,

    @Json(name = "poster_type")
    val posterType: PosterType,

    var time: Long = -1
)
