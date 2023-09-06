package com.cosmos.unreddit.data.model.db

import android.os.Parcelable
import com.cosmos.unreddit.R

sealed interface FeedItem : Parcelable {
    val id: String

    var seen: Boolean

    var saved: Boolean

    val textColor: Int
        get() = if (seen) R.color.text_color_post_seen else R.color.text_color
}
