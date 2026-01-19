package com.bitchat.viewvo.location

import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeohashChannel
import com.bitchat.domain.location.model.GeohashChannelLevel

data class LocationChannelsState(
    val availableChannels: List<GeohashChannel> = emptyList(),
    val bookmarkedGeohashes: List<String> = emptyList(),
    val selectedChannel: Channel = Channel.Mesh,
    val isTeleported: Boolean = false,
    val participantCounts: Map<String, Int> = emptyMap(),
    val meshParticipantCount: Int = 0,
    val locationNames: Map<GeohashChannelLevel, String> = emptyMap(),
    val bookmarkNames: Map<String, String> = emptyMap(),
    val customGeohash: String = "",
    val customGeohashError: String? = null,
    val locationServicesEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false
)

sealed class LocationChannelsEffect {
    data class OpenMap(val initialGeohash: String?) : LocationChannelsEffect()
    data class ApplyMapResult(val geohash: String) : LocationChannelsEffect()
}
