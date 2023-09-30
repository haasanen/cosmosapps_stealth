package com.cosmos.unreddit.data.model

import android.os.Parcelable
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.service.LemmyService
import com.cosmos.stealth.sdk.data.model.service.RedditService
import com.cosmos.stealth.sdk.data.model.service.RedditService.Instance.OLD
import com.cosmos.stealth.sdk.data.model.service.SupportedService
import com.cosmos.stealth.sdk.data.model.service.TedditService
import com.squareup.moshi.JsonClass
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
@JsonClass(generateAdapter = true)
data class Service(
    val name: ServiceName,

    val instance: String? = null
) : Parcelable {

    /**
     * Map Reddit/Teddit to the preferred source/instance; otherwise use service as is
     */
    fun mapService(redditSource: Service): Service {
        return when (name) {
            ServiceName.reddit, ServiceName.teddit -> redditSource
            ServiceName.lemmy -> this
        }
    }

    fun asSupportedService(): SupportedService = when (name) {
        ServiceName.reddit -> if (instance == OLD.url) RedditService(OLD) else RedditService()
        ServiceName.teddit -> instance?.run { TedditService(this) } ?: TedditService()
        ServiceName.lemmy -> instance?.run { LemmyService(this) } ?: error("Instance is null")
    }
}
