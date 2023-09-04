package com.cosmos.unreddit.data.local.mapper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

abstract class Mapper<From, To>(protected val defaultDispatcher: CoroutineDispatcher) {

    protected abstract suspend fun toEntity(from: From): To

    protected open suspend fun toEntities(from: List<From>): List<To> = supervisorScope {
        from.map { async { toEntity(it) } }.awaitAll()
    }

    protected open suspend fun fromEntity(from: To): From {
        throw UnsupportedOperationException()
    }

    protected open suspend fun fromEntities(from: List<To>): List<From> = supervisorScope {
        from.map { async { fromEntity(it) } }.awaitAll()
    }

    suspend fun dataToEntity(from: From): To = withContext(defaultDispatcher) {
        toEntity(from)
    }

    suspend fun dataToEntities(from: List<From>): List<To> = withContext(defaultDispatcher) {
        toEntities(from)
    }

    suspend fun dataFromEntity(from: To): From = withContext(defaultDispatcher) {
        fromEntity(from)
    }

    suspend fun dataFromEntities(from: List<To>): List<From> = withContext(defaultDispatcher) {
        fromEntities(from)
    }
}
