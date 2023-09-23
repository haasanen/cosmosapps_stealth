package com.cosmos.unreddit.data.local.mapper

import com.cosmos.stealth.sdk.data.model.api.UserInfo
import com.cosmos.unreddit.data.model.User2
import com.cosmos.unreddit.di.DispatchersModule.DefaultDispatcher
import com.cosmos.unreddit.util.extension.toMedia
import com.cosmos.unreddit.util.extension.toService
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Inject

class UserMapper @Inject constructor(
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
) : Mapper<UserInfo, User2>(defaultDispatcher) {

    override suspend fun toEntity(from: UserInfo): User2 {
        return with(from) {
            User2(
                service.toService(),
                id,
                name,
                created,
                icon?.toMedia(),
                header?.toMedia(),
                description,
                subscribers,
                subscribees,
                nsfw,
                postCount,
                commentCount,
                score,
                refLink
            )
        }
    }
}
