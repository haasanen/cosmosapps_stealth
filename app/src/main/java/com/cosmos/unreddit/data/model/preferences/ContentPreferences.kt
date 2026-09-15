package com.cosmos.unreddit.data.model.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey

data class ContentPreferences(
    val showNsfw: Boolean,

    val showNsfwPreview: Boolean,

    val showSpoilerPreview: Boolean,

    /** Play visible video previews in the feed (always muted, looping). */
    val autoplayPreviews: Boolean
) {
    object PreferencesKeys {
        val SHOW_NSFW = booleanPreferencesKey("show_nsfw")
        val SHOW_NSFW_PREVIEW = booleanPreferencesKey("show_nsfw_preview")
        val SHOW_SPOILER_PREVIEW = booleanPreferencesKey("show_spoiler_preview")
        val AUTOPLAY_PREVIEWS = booleanPreferencesKey("autoplay_previews")
    }
}
