package com.cosmos.unreddit.data.local.adapter

import com.cosmos.unreddit.data.model.backup.Backup
import com.cosmos.unreddit.data.model.backup.Profile
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.internal.Util
import com.squareup.moshi.rawType
import java.lang.reflect.Type

class BackupAdapter(moshi: Moshi) : JsonAdapter<Backup>() {

    private val options: JsonReader.Options = JsonReader.Options.of("version", "profiles")

    private val intAdapter: JsonAdapter<Int> = moshi.adapter(Int::class.java, emptySet(), "version")

    private val listOfProfileAdapter: JsonAdapter<List<Profile>> =
        moshi.adapter(
            Types.newParameterizedType(List::class.java, Profile::class.java), emptySet(),
            "profiles")

    override fun fromJson(reader: JsonReader): Backup? {
        return when (reader.peek()) {
            JsonReader.Token.BEGIN_ARRAY -> fromOldFormat(reader)
            JsonReader.Token.BEGIN_OBJECT -> fromNewFormat(reader)
            else -> {
                reader.skipValue()
                return null
            }
        }
    }

    private fun fromOldFormat(reader: JsonReader): Backup {
        val profiles = listOfProfileAdapter.fromJson(reader) ?: emptyList()

        return Backup(profiles)
    }

    private fun fromNewFormat(reader: JsonReader): Backup {
        var version = 0
        var profiles: List<Profile> = emptyList()

        reader.beginObject()

        while (reader.hasNext()) {
            when (reader.selectName(options)) {
                0 -> {
                    version = intAdapter.fromJson(reader)
                        ?: throw Util.unexpectedNull("version", "version", reader)
                }
                1 -> {
                    profiles = listOfProfileAdapter.fromJson(reader)
                        ?: throw Util.unexpectedNull("profiles", "profiles", reader)
                }
                -1 -> {
                    // Unknown name, skip it.
                    reader.skipName()
                    reader.skipValue()
                }
            }
        }

        reader.endObject()

        return Backup(version, profiles)
    }

    override fun toJson(writer: JsonWriter, value: Backup?) {
        if (value == null) {
            throw NullPointerException("value was null! Wrap in .nullSafe() to write nullable values.")
        }
        writer.beginObject()
        writer.name("version")
        intAdapter.toJson(writer, value.version)
        writer.name("profiles")
        listOfProfileAdapter.toJson(writer, value.profiles)
        writer.endObject()
    }

    object Factory : JsonAdapter.Factory {
        override fun create(
            type: Type,
            annotations: MutableSet<out Annotation>,
            moshi: Moshi
        ): JsonAdapter<*>? {
            return when {
                annotations.isNotEmpty() -> null
                type.rawType == Backup::class.java -> BackupAdapter(moshi)
                else -> null
            }
        }
    }
}
