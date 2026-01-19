package com.bitchat.local.prefs.impl

import com.bitchat.local.prefs.BookmarkPreferences
import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

class LocalBookmarkPreferences(
    private val settingsFactory: Settings.Factory,
) : BookmarkPreferences {
    private val settings = settingsFactory.create(PREFS_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getBookmarks(): List<String> {
        val jsonString = settings.getStringOrNull(KEY_BOOKMARKS) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(jsonString)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun addBookmark(geohash: String) {
        val current = getCurrentBookmarks().toMutableSet()
        current.add(geohash.lowercase())
        saveBookmarks(current.toList())
    }

    override suspend fun removeBookmark(geohash: String) {
        val current = getCurrentBookmarks().toMutableSet()
        current.remove(geohash.lowercase())
        saveBookmarks(current.toList())

        val names = getCurrentNames().toMutableMap()
        names.remove(geohash.lowercase())
        saveNames(names)
    }

    override suspend fun isBookmarked(geohash: String): Boolean {
        return getCurrentBookmarks().contains(geohash.lowercase())
    }

    override suspend fun getBookmarkNames(): Map<String, String> {
        return getCurrentNames()
    }

    override suspend fun getBookmarkName(geohash: String): String? {
        return getCurrentNames()[geohash.lowercase()]
    }

    override suspend fun setBookmarkName(geohash: String, name: String) {
        val names = getCurrentNames().toMutableMap()
        names[geohash.lowercase()] = name
        saveNames(names)
    }

    override suspend fun clearAllBookmarks() {
        settings.remove(KEY_BOOKMARKS)
        settings.remove(KEY_BOOKMARK_NAMES)
    }

    private fun getCurrentBookmarks(): List<String> {
        val json = settings.getStringOrNull(KEY_BOOKMARKS) ?: return emptyList()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveBookmarks(bookmarks: List<String>) {
        settings.putString(KEY_BOOKMARKS, Json.encodeToString(bookmarks))
    }

    private fun getCurrentNames(): Map<String, String> {
        val json = settings.getStringOrNull(KEY_BOOKMARK_NAMES) ?: return emptyMap()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveNames(names: Map<String, String>) {
        settings.putString(KEY_BOOKMARK_NAMES, Json.encodeToString(names))
    }

    companion object {
        private const val PREFS_NAME = "bookmark_prefs"
        private const val KEY_BOOKMARKS = "geohash_bookmarks"
        private const val KEY_BOOKMARK_NAMES = "geohash_bookmark_names"
    }
}
