package com.bitchat.local.prefs

interface BookmarkPreferences {
    suspend fun getBookmarks(): List<String>
    suspend fun addBookmark(geohash: String)
    suspend fun removeBookmark(geohash: String)
    suspend fun isBookmarked(geohash: String): Boolean
    suspend fun getBookmarkNames(): Map<String, String>
    suspend fun getBookmarkName(geohash: String): String?
    suspend fun setBookmarkName(geohash: String, name: String)
    suspend fun clearAllBookmarks()
}
