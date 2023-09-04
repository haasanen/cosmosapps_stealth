package com.cosmos.unreddit.util.extension

import com.cosmos.unreddit.data.model.Badge
import com.cosmos.unreddit.data.model.BadgeData
import com.cosmos.unreddit.data.model.Media
import com.cosmos.unreddit.data.model.MediaSource
import com.cosmos.unreddit.data.model.PostType
import com.cosmos.unreddit.data.model.PosterType
import com.cosmos.unreddit.data.model.Reaction
import com.cosmos.unreddit.data.model.Reactions
import com.cosmos.unreddit.data.model.Service
import com.cosmos.stealth.sdk.data.model.api.Badge as RemoteBadge
import com.cosmos.stealth.sdk.data.model.api.BadgeData as RemoteBadgeData
import com.cosmos.stealth.sdk.data.model.api.Media as RemoteMedia
import com.cosmos.stealth.sdk.data.model.api.MediaSource as RemoteMediaSource
import com.cosmos.stealth.sdk.data.model.api.PostType as RemotePostType
import com.cosmos.stealth.sdk.data.model.api.PosterType as RemotePosterType
import com.cosmos.stealth.sdk.data.model.api.Reaction as RemoteReaction
import com.cosmos.stealth.sdk.data.model.api.Reactions as RemoteReactions
import com.cosmos.stealth.sdk.data.model.api.Service as RemoteService

fun RemoteService.toService(): Service {
    return Service(name, instance)
}

fun RemoteReactions.toReactions(): Reactions {
    return Reactions(total, reactions.map { it.toReaction() })
}

fun RemoteReaction.toReaction(): Reaction {
    return Reaction(count, media?.toMedia(), name, description)
}

fun RemoteMedia.toMedia(): Media {
    return Media(
        mime,
        source.toMediaSource(),
        id,
        resolutions?.map { it.toMediaSource() },
        alternatives?.map { it.toMedia() },
        caption
    )
}

fun RemoteMediaSource.toMediaSource(): MediaSource {
    return MediaSource(url, width, height)
}

fun RemoteBadge.toBadge(): Badge {
    return Badge(badgeDataList.map { it.toBadgeData() }, background)
}

fun RemoteBadgeData.toBadgeData(): BadgeData {
    return BadgeData(type, text, url)
}

fun RemotePostType.toPostType(): PostType {
    return when (this) {
        RemotePostType.text -> PostType.TEXT
        RemotePostType.image -> PostType.IMAGE
        RemotePostType.video -> PostType.VIDEO
        RemotePostType.link -> PostType.LINK
    }
}

fun RemotePosterType.toPosterType(): PosterType {
    return when (this) {
        RemotePosterType.regular -> PosterType.REGULAR
        RemotePosterType.moderator -> PosterType.MODERATOR
        RemotePosterType.admin -> PosterType.ADMIN
        RemotePosterType.bot -> TODO()
    }
}
