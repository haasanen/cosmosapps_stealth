package com.cosmos.unreddit.data.model.backup

import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Subscription(
    @Json(name = "name")
    val name: String,

    @Json(name = "time")
    val time: Long,

    @Json(name = "icon")
    val icon: String?,

    @Json(name = "service")
    var service: ServiceName?,

    @Json(name = "instance")
    val instance: String?
)
