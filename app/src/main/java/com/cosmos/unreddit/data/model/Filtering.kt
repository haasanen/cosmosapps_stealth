package com.cosmos.unreddit.data.model

import com.cosmos.stealth.sdk.data.model.api.Order
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time

data class Filtering(
    val sort: Sort? = null,

    val order: Order? = null,

    val time: Time? = null
)
