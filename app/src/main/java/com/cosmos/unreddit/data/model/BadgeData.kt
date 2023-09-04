package com.cosmos.unreddit.data.model

import android.os.Parcelable
import com.cosmos.stealth.sdk.data.model.api.BadgeData
import kotlinx.parcelize.Parcelize

@Parcelize
data class BadgeData(
    val type: BadgeData.Type,

    val text: String? = null,

    val url: String? = null
) : Parcelable
