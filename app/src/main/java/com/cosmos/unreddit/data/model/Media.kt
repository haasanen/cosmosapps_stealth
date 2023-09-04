package com.cosmos.unreddit.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class Media(
    val mime: String,

    val source: MediaSource,

    val id: String? = null,

    val resolutions: List<MediaSource>? = null,

    val alternatives: List<Media>? = null,

    val caption: String? = null
) : Parcelable
