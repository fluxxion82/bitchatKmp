package com.bitchat.domain.location.repository

import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel
import com.bitchat.domain.location.model.Note
import com.bitchat.domain.location.model.PermissionState

interface LocationRepository {
    suspend fun getNotes(geoHash: String): List<Note>
    suspend fun sendNote(content: String, nickname: String, geohash: String)

    suspend fun getAvailableGeohashChannels(): List<GeohashChannel>
    suspend fun getLocationGeohash(level: GeohashChannelLevel): String
    suspend fun getBookmarkedChannel(): List<GeohashChannel>

    suspend fun sendGeohashMessage(content: String, channel: GeohashChannel, myPeerId: String)

    suspend fun getParticipantCounts(): Map<String, Int>
    suspend fun getCurrentGeohashPeople(): List<GeoPerson>
    suspend fun beginGeohashSampling(geohashes: List<String>)
    suspend fun endGeohashSampling()

    suspend fun getBookmarkedGeohashes(): List<String>
    suspend fun getBookmarkNames(): Map<String, String>
    suspend fun toggleBookmark(geohash: String)
    suspend fun isBookmarked(geohash: String): Boolean

    suspend fun registerSelfAsParticipant(geohash: String, nickname: String, isTeleported: Boolean)
    suspend fun unregisterSelfFromCurrentGeohash()
    suspend fun isTeleported(): Boolean

    suspend fun toggleLocationServices()
    suspend fun isLocationServicesEnabled(): Boolean
    suspend fun getPermissionState(): PermissionState
    suspend fun hasNotes(geohash: String): Boolean
    suspend fun resolveLocationName(geohash: String, level: GeohashChannelLevel): String?
    suspend fun getLocationNames(): Map<GeohashChannelLevel, String>

    suspend fun requestLocationPermission()

    suspend fun clearData()
}
