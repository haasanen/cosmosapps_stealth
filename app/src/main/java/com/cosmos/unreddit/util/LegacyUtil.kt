package com.cosmos.unreddit.util

import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaSource
import com.cosmos.unreddit.util.extension.mimeType

fun String.asPreviewMedia(): Media? {
    val mime = mimeType
    return if (mime.startsWith("image")) {
        Media(mime, MediaSource(this), Media.Type.IMAGE)
    } else {
        null
    }
}

fun String.asMedia(): Media? {
    val mime = mimeType
    return if (mime.startsWith("image") || mime.startsWith("video")) {
        Media(mime, MediaSource(this), Media.Type.fromMime(mime))
    } else {
        null
    }
}

fun String.removeSubredditPrefix(): String = removePrefix("r/")

fun String.addInstancePrefix(instance: String = "https://www.reddit.com"): String = "$instance$this"

fun Int.toRatio(): Double? = (toDouble() / 100).takeIf { it >= 0.0 }
