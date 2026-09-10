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
    /**
     * Why there are no nearby channels, when the reason is permanent rather than "not yet".
     *
     * Null means the list is simply still being built. Non-null means it never will be, and the UI
     * must stop showing a progress spinner and say this instead.
     */
    val locationUnavailableReason: String? = null,
    /** The fix came from an IP lookup, so it is city-level at best. */
    val locationApproximate: Boolean = false,
    /** The fix is old enough that the user may have moved since. */
    val locationStale: Boolean = false,
    val isRefreshing: Boolean = false
)

sealed class LocationChannelsEffect {
    data class OpenMap(val initialGeohash: String?) : LocationChannelsEffect()
    data class ApplyMapResult(val geohash: String) : LocationChannelsEffect()
}
