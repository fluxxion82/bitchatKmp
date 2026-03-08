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

    /**
     * Meshtastic network channel.
     *
     * Used for messages sent over Meshtastic protocol to the mesh network.
     * When nodeNum is null, messages are broadcast. When nodeNum is set,
     * messages are sent to a specific node (DM).
     *
     * @property nodeNum Target node number for DM, or null for broadcast
     * @property displayName User-friendly name of the node (from NodeInfo)
     */
    @Serializable
    @SerialName("meshtastic")
    data class Meshtastic(
        val nodeNum: Int? = null,
        val displayName: String? = null
    ) : Channel
}
