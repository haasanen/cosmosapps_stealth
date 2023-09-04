package com.cosmos.unreddit.data.model

import android.os.Parcelable
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class Service(
    val name: ServiceName,

    val instance: String? = null
) : Parcelable
