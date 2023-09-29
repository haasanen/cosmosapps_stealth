package com.cosmos.unreddit.data.model.backup

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class Backup(
    val version: Int = VERSION,

    val profiles: List<Profile> = emptyList()
) {
    constructor(profiles: List<Profile> = emptyList()) : this(VERSION, profiles)

    companion object {
        private const val VERSION = 2
    }
}
