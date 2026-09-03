package com.cosmos.unreddit.data.remote.api.reddit.adapter

import com.cosmos.unreddit.data.remote.api.reddit.model.GalleryItem
import com.cosmos.unreddit.data.remote.api.reddit.model.MediaMetadata
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

class MediaMetadataAdapter(
    moshi: Moshi
) : JsonAdapter<MediaMetadata>() {

    private val galleryItemAdapter: JsonAdapter<GalleryItem> =
        moshi.adapter(GalleryItem::class.java, emptySet())

    override fun fromJson(reader: JsonReader): MediaMetadata? {
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.skipValue()
            return null
        }

        val items = mutableListOf<GalleryItem>()

        reader.beginObject()

        while (reader.hasNext()) {
            reader.skipName()
            items.add(galleryItemAdapter.fromJson(reader)!!)
        }

        reader.endObject()

        return MediaMetadata(items)
    }

    override fun toJson(writer: JsonWriter, value: MediaMetadata?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        // Write the same shape fromJson reads: an id-keyed object whose values are
        // GalleryItems. The keys are ignored on read (skipName), so index keys keep
        // the JSON stable across round-trips. This method used to be empty, which
        // made Moshi emit {} for every post with media metadata — every cache row
        // then deserialized back to null (2026-09-03: 1123/1123 rows postJson blank).
        writer.beginObject()
        value.items.forEachIndexed { i, item ->
            writer.name(item.id ?: i.toString())
            galleryItemAdapter.toJson(writer, item)
        }
        writer.endObject()
    }

    object Factory : JsonAdapter.Factory {
        override fun create(
            type: Type,
            annotations: MutableSet<out Annotation>,
            moshi: Moshi
        ): JsonAdapter<*>? {
            if (annotations.isNotEmpty()) return null
            if (Types.getRawType(type) == MediaMetadata::class.java) {
                return MediaMetadataAdapter(moshi)
            }
            return null
        }
    }
}
