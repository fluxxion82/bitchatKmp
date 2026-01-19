package com.bitchat.viewvo.chat

import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.PermissionState
import com.bitchat.domain.user.model.FavoriteRelationship
import com.bitchat.domain.location.model.Channel as LocationChannel

data class HeaderState(
    val selectedPrivatePeer: String? = null,
    val currentChannel: String? = null,
    val selectedChannel: LocationChannel? = null,

    val nickname: String = "anon",
    val favoritePeers: Set<String> = emptySet(),
    val favoriteRelationships: Map<String, FavoriteRelationship> = emptyMap(),

    val connectedPeers: List<String> = emptyList(),
    val peerNicknames: Map<String, String> = emptyMap(),
    val peerFingerprints: Map<String, String> = emptyMap(),
    val peerSessionStates: Map<String, String> = emptyMap(),
    val peerDirect: Map<String, Boolean> = emptyMap(),

    val joinedChannels: Set<String> = emptySet(),
    val unreadChannelMessages: Map<String, Int> = emptyMap(),
    val hasUnreadPrivateMessages: Boolean = false,

    val selectedLocationChannel: LocationChannel = LocationChannel.Mesh,
    val geohashPeople: List<GeoPerson> = emptyList(),
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

    val isConnected: Boolean = false,
    val showSidebar: Boolean = false
)
