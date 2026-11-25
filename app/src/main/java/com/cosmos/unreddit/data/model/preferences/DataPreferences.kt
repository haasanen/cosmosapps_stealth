package com.cosmos.unreddit.data.model.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

data class DataPreferences(
    val enablePrivacyEnhancer: Boolean
) {
    object PreferencesKeys {
        val PRIVACY_ENHANCER = booleanPreferencesKey("privacy_enhancer")
        val CACHE_TTL_HOURS = intPreferencesKey("cache_ttl_hours")
    }
}
