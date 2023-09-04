package com.cosmos.unreddit.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Reactions(
    val total: Int,

    val reactions: List<Reaction>
) : Parcelable
