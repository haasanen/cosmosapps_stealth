package com.cosmos.unreddit.data.model

import android.os.Parcelable
import androidx.annotation.Keep
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Keep
@Parcelize
@Serializable
@JsonClass(generateAdapter = true)
data class Media(
    val mime: String,

    val source: MediaSource,

    val type: Type,

    val id: String? = null,

    val resolutions: List<MediaSource>? = null,

    val alternatives: List<Media>? = null,

    val caption: String? = null
) : Parcelable {

    fun singletonList(): List<Media> = listOf(this)

    enum class Type(val value: Int) {
        IMAGE(0), VIDEO(1), AUDIO(2), ANY(-1);

        companion object {
            fun fromMime(mime: String): Type {
                return when {
                    mime.startsWith("image") -> IMAGE
                    mime.startsWith("video") -> VIDEO
                    mime.startsWith("audio") -> AUDIO
                    else -> ANY
                }
            }

            fun fromValue(value: Int): Type? = entries.find { it.value == value }
        }
    }
}
