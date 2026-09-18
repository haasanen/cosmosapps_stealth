package com.cosmos.unreddit.data.repository

import com.cosmos.unreddit.data.remote.api.redgifs.RedgifsApi
import com.cosmos.unreddit.data.remote.api.redgifs.RedgifsToken
import com.cosmos.unreddit.data.remote.api.redgifs.model.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RedgifsRepository @Inject constructor(
    private val redgifsApi: RedgifsApi,
    private val redgifsToken: RedgifsToken,
) {

    fun getRedgifsGif(id: String): Flow<Item> = flow {
        val authorization = redgifsToken.getAuthorization()
        try {
            emit(redgifsApi.getGif(authorization, id))
        } catch (t: Exception) {
            if (t is CancellationException) throw t // propagate flow cancellation
            // The cached temporary token expired server-side (401/403): refresh it
            // once and retry. Without this, an expired token would keep failing
            // every call forever (the sharp-poster swap swallows the failure, so a
            // redgifs post would stay CDN-frosted even with the preview ON).
            val unauthorized = when (t) {
                is retrofit2.HttpException -> t.code() == 401 || t.code() == 403
                else -> false
            }
            if (unauthorized) {
                redgifsToken.invalidate()
                emit(redgifsApi.getGif(redgifsToken.getAuthorization(), id))
            } else {
                throw t
            }
        }
    }
}
