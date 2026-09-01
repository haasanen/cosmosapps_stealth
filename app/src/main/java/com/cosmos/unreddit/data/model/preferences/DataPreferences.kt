package com.cosmos.unreddit.data.model.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

data class DataPreferences(
    val redditSource: Int,
    val redditSourceInstance: String,
    val enablePrivacyEnhancer: Boolean
) {
    object PreferencesKeys {
        val REDDIT_SOURCE = intPreferencesKey("reddit_source")
        val REDDIT_SOURCE_INSTANCE = stringPreferencesKey("reddit_source_instance")
        val PRIVACY_ENHANCER = booleanPreferencesKey("privacy_enhancer")
        val CACHE_TTL_HOURS = intPreferencesKey("cache_ttl_hours")
    }

    enum class RedditSource(val value: Int) {
        REDDIT(0), TEDDIT(1), REDDIT_SCRAP(2), ARCTIC(3), REDDIT_OFFICIAL(4), REDDIT_ATOM(5);

        companion object {
            fun fromValue(value: Int): RedditSource = values().find { it.value == value } ?: REDDIT
        }
    }
}
