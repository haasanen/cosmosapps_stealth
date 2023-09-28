package com.cosmos.unreddit.util

object SearchUtil {
    private const val QUERY_MIN_LENGTH = 3

    fun isQueryValid(query: String): Boolean {
        return query.length >= QUERY_MIN_LENGTH
    }
}
