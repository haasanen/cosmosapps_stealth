package com.cosmos.unreddit.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Deprecated("Legacy entity")
@Parcelize
data class Award(
    val count: Int,

    val icon: String
) : Parcelable
