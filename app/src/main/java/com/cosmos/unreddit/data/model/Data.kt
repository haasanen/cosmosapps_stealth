package com.cosmos.unreddit.data.model

import com.cosmos.unreddit.data.model.preferences.ContentPreferences

sealed class Data {

    data class Fetch(val query: String, val sorting: Sorting) : Data()

    data class FetchSingle(val query: Query, val filtering: Filtering) : Data()

    data class FetchMultiple(val query: List<ServiceQuery>, val filtering: Filtering) : Data()

    data class User(
        val history: List<String>,
        val saved: List<String>,
        val contentPreferences: ContentPreferences,
        val savedComments: List<String>? = null
    ) : Data()
}
