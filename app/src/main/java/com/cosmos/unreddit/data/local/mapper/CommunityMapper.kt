package com.cosmos.unreddit.data.local.mapper

import android.graphics.Color
import com.cosmos.stealth.sdk.data.model.api.CommunityInfo
import com.cosmos.unreddit.data.model.Community
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.util.HtmlParser
import com.cosmos.unreddit.util.extension.toMedia
import com.cosmos.unreddit.util.extension.toService
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

class CommunityMapper @Inject constructor(
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<CommunityInfo, Community>(defaultDispatcher) {

    private val htmlParser: HtmlParser = HtmlParser(defaultDispatcher)

    override suspend fun toEntity(from: CommunityInfo): Community {
        return with (from) {
            val colorInt = color?.run {
                try {
                    Color.parseColor(this)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

            Community(
                service.toService(),
                id,
                name,
                created,
                title,
                htmlParser.separateHtmlBlocks(shortDescription),
                htmlParser.separateHtmlBlocks(description),
                icon?.toMedia(),
                header?.toMedia(),
                members,
                active,
                refLink,
                nsfw,
                colorInt
            )
        }
    }
}
