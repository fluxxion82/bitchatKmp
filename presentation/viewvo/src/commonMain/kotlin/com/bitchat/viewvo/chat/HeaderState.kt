package com.bitchat.viewvo.chat

import com.bitchat.domain.chat.model.MeshChannelPerson
import com.bitchat.domain.chat.model.MeshChannelTransport
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.location.model.Channel as LocationChannel
import kotlin.time.Instant

data class HeaderState(
    val selectedPrivatePeer: String? = null,
    val currentChannel: String? = null,
    val selectedChannel: LocationChannel? = null,

    val nickname: String = "anon",
    val favoritePeers: Set<String> = emptySet(),
    val favoriteRelationships: Map<String, FavoriteRelationship> = emptyMap(),

    val nicknameDirectory: Map<String, String> = emptyMap(),
    val peerFingerprints: Map<String, String> = emptyMap(),
    val peerSessionStates: Map<String, String> = emptyMap(),

    val joinedChannels: Set<String> = emptySet(),
    val unreadChannelMessages: Map<String, Int> = emptyMap(),
    val hasUnreadPrivateMessages: Boolean = false,

    val selectedLocationChannel: LocationChannel = LocationChannel.Mesh,
    val geohashPeople: List<GeoPerson> = emptyList(),
    val meshPeople: List<MeshChannelPerson> = emptyList(),
    val permissionState: PermissionState = PermissionState.DENIED,
    val locationServicesEnabled: Boolean = false,
    val isCurrentChannelBookmarked: Boolean = false,
    val hasNotes: Boolean = false,
    val teleported: Boolean = false,

    val powEnabled: Boolean = false,
    val powDifficulty: Int = 0,
    val isMining: Boolean = false,

    val torEnabled: Boolean = false,
    val torRunning: Boolean = false,
    val torBootstrapPercent: Int = 0,

    val showSidebar: Boolean = false,
) {
    /** Whether the people of the selected channel are the mesh people rather than a geohash's. */
    val isMeshChannel: Boolean
        get() = selectedLocationChannel is LocationChannel.Mesh || selectedLocationChannel is LocationChannel.MeshDM

    // The views below have no storage of their own: they are views of [meshPeople] (on a mesh
    // channel) or [geohashPeople], so they cannot disagree with each other or with the list.

    /**
     * What a peer id or conversation key is called. On a mesh channel a private chat's own name wins
     * over everything announced later and over [nicknameDirectory]. The directory still wins over an
     * offline name that is not fixed, and a connected peer's current announcement wins after that.
     */
    val peerNicknames: Map<String, String>
        get() = if (isMeshChannel) {
            val listed = meshPeople.filterNot { it.isLoRaOnly }
            val (fixed, notFixed) = listed.partition { it.nameIsFixed }
            val (connected, savedOnly) = notFixed.partition { MeshChannelTransport.MESH in it.transports }
            savedOnly.associate { it.id to it.displayName } +
                nicknameDirectory +
                connected.associate { it.id to it.displayName } +
                fixed.associate { it.id to it.displayName }
        } else {
            nicknameDirectory
        }

    /**
     * The listed people whose name is a mesh private chat's own, fixed when the chat was opened. Such a
     * name carries the start of the peer's id (`alice#1a2b`), and a list shows it in full.
     */
    val fixedNamePeers: Set<String>
        get() = if (isMeshChannel) meshPeople.filter { it.nameIsFixed }.mapTo(mutableSetOf()) { it.id } else emptySet()

    /** What a peer with a private chat announces now, when that is another name than the chat's. */
    val claimedNames: Map<String, String>
        get() = if (isMeshChannel) {
            meshPeople.mapNotNull { person -> person.claimedName?.let { person.id to it } }.toMap()
        } else {
            emptyMap()
        }

    /** The listed people's ids: on a mesh channel every mesh person that is not LoRa-only. */
    val connectedPeers: List<String>
        get() = if (isMeshChannel) {
            meshPeople.filterNot { it.isLoRaOnly }.map { it.id }
        } else {
            geohashPeople.map { it.displayName }
        }

    val peerDirect: Map<String, Boolean>
        get() = if (isMeshChannel) {
            meshPeople.filter { MeshChannelTransport.MESH in it.transports }.associate { it.id to true }
        } else {
            geohashPeople.associate { it.id to false }
        }

    /** The people known only from the LoRa radio; with [connectedPeers] they are all of [meshPeople]. */
    val loraPeers: List<GeoPerson>
        get() = meshPeople.filter { it.isLoRaOnly }
            .map { GeoPerson(it.id, it.displayName, it.lastSeen ?: Instant.DISTANT_PAST) }
}
