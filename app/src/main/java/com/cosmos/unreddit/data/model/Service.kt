package com.cosmos.unreddit.data.model

import android.os.Parcelable
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.service.LemmyService
import com.cosmos.stealth.sdk.data.model.service.RedditService
import com.cosmos.stealth.sdk.data.model.service.SupportedService
import com.cosmos.stealth.sdk.data.model.service.TedditService
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class Service(
    val name: ServiceName,

    val instance: String? = null
) : Parcelable {

    fun asSupportedService(): SupportedService = when (name) {
        ServiceName.reddit -> RedditService()
        ServiceName.teddit -> instance?.let { TedditService(it) } ?: TedditService()
        ServiceName.lemmy -> instance?.let { LemmyService(it) } ?: error("Instance is null")
    }
}
