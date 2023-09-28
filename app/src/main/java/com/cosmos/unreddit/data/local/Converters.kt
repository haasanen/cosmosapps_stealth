package com.cosmos.unreddit.data.local

import androidx.room.TypeConverter
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Service
import com.cosmos.unreddit.data.model.db.Redirect
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromPostTypeInt(type: Int?): PostType? {
        return type?.let { PostType.toType(it) }
    }

    @TypeConverter
    fun toPostTypeInt(postType: PostType?): Int? {
        return postType?.value
    }

    @TypeConverter
    fun fromPosterTypeInt(type: Int?): PosterType? {
        return type?.let { PosterType.toType(it) }
    }

    @TypeConverter
    fun toPosterTypeInt(posterType: PosterType?): Int? {
        return posterType?.value
    }

    @TypeConverter
    fun fromRedirectModeInt(mode: Int?): Redirect.RedirectMode {
        return mode?.let { Redirect.RedirectMode.toMode(it) } ?: Redirect.RedirectMode.OFF
    }

    @TypeConverter
    fun toRedirectModeInt(redirectMode: Redirect.RedirectMode?): Int {
        return redirectMode?.mode ?: Redirect.RedirectMode.OFF.mode
    }

    @TypeConverter
    fun fromSerializedService(serializedService: String?): Service? {
        return serializedService?.run { Json.decodeFromString(serializedService) }
    }

    @TypeConverter
    fun toSerializedService(service: Service?): String? {
        return try {
            Json.encodeToString(service)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    @TypeConverter
    fun fromSerializedMedia(serializedMedia: String?): Media? {
        return serializedMedia?.run { Json.decodeFromString(serializedMedia) }
    }

    @TypeConverter
    fun toSerializedMedia(media: Media?): String? {
        return try {
            Json.encodeToString(media)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    @TypeConverter
    fun fromSerializedMediaList(serializedMedia: String?): List<Media>? {
        return serializedMedia?.run { Json.decodeFromString(serializedMedia) }
    }

    @TypeConverter
    fun toSerializedMediaList(media: List<Media>?): String? {
        return try {
            Json.encodeToString(media)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
    }
}
