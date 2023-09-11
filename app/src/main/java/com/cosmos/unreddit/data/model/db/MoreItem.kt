package com.cosmos.unreddit.data.model.db

import com.cosmos.stealth.sdk.data.model.api.Appendable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

@Parcelize
data class MoreItem(
    val appendable: @RawValue Appendable,

    override val id: String,

    val depth: Int,

    val commentIndicator: Int? = null,

    override var seen: Boolean = true,

    override var saved: Boolean = true
) : FeedItem {

    @IgnoredOnParcel
    var isLoading: Boolean = false

    @IgnoredOnParcel
    var isError: Boolean = false
}
