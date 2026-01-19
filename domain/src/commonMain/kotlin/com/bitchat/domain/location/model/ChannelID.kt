package com.bitchat.domain.location.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Channel {
    @Serializable
    @SerialName("mesh")
    data object Mesh : Channel

    @Serializable
    @SerialName("location")
    data class Location(
        val level: GeohashChannelLevel,
        val geohash: String
    ) : Channel

    @Serializable
    @SerialName("meshDM")
    data class MeshDM(
        val peerID: String,
        val displayName: String? = null,
    ) : Channel

    @Serializable
    @SerialName("nostrDM")
    data class NostrDM(
        val peerID: String,
        val fullPubkey: String,
        val sourceGeohash: String?,
        val displayName: String? = null,
    ) : Channel

    @Serializable
    @SerialName("namedChannel")
    data class NamedChannel(val channelName: String) : Channel
}
