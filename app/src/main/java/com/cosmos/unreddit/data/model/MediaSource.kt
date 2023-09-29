package com.cosmos.unreddit.data.model

import android.os.Parcelable
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
@JsonClass(generateAdapter = true)
data class MediaSource(
    val url: String,

    val width: Int? = null,

    val height: Int? = null
) : Parcelable
