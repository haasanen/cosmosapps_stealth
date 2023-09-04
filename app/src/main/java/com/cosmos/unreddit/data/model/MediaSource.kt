package com.cosmos.unreddit.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class MediaSource(
    val url: String,

    val width: Int? = null,

    val height: Int? = null
) : Parcelable
