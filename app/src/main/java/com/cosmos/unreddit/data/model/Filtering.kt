package com.cosmos.unreddit.data.model

import android.os.Parcelable
import com.cosmos.stealth.sdk.data.model.api.Order
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time
import kotlinx.parcelize.Parcelize

@Parcelize
data class Filtering(
    val sort: Sort? = null,

    val order: Order? = null,

    val time: Time? = null
) : Parcelable
