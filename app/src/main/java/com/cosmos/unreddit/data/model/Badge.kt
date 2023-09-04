package com.cosmos.unreddit.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Badge(
    val badgeDataList: List<BadgeData>,

    val background: String? = null
) : Parcelable
