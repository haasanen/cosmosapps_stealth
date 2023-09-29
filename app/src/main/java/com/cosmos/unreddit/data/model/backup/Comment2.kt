package com.cosmos.unreddit.data.model.backup

import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Service
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Comment2(
    val service: Service,

    val id: String,

    @Json(name = "post_id")
    val postId: String,

    val community: String,

    val body: String,

    val author: String,

    val score: Int,

    @Json(name = "ref_link")
    val refLink: String,

    val created: Long,

    val depth: Int?,

    val edited: Long?,

    val pinned: Boolean?,

    val controversial: Boolean?,

    val submitter: Boolean,

    @Json(name = "post_author")
    val postAuthor: String?,

    @Json(name = "post_title")
    val postTitle: String?,

    @Json(name = "post_ref_link")
    val postRefLink: String?,

    @Json(name = "poster_type")
    val posterType: PosterType,

    var time: Long = -1
)
