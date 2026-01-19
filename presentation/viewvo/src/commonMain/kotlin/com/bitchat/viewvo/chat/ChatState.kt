package com.bitchat.viewvo.chat

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.ChannelInfo
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.location.model.PermissionState

data class ChatState(
    val messages: List<BitchatMessage> = emptyList(),
    val currentGeohash: String? = null,
    val currentChannel: String? = null,
    val isConnected: Boolean = false,
    val isSending: Boolean = false,
    val messageInput: String = "",
    val nickname: String = "anon",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,

    val showSidebar: Boolean = false,

    val selectedLocationChannel: Channel = Channel.Mesh,
    val permissionState: PermissionState = PermissionState.AUTHORIZED,
    val locationServicesEnabled: Boolean = true,
    val notes: List<String> = listOf(),
    val bookmarks: List<String> = emptyList(),

    val connectedPeers: List<String> = listOf(),
    val joinedChannels: Set<String> = setOf(),
    val joinedNamedChannels: List<ChannelInfo> = emptyList(),
    val peerNicknames: Map<String, String> = mapOf(),
    val peerDirect: Map<String, Boolean> = mapOf(),
    val favoritePeers: Set<String> = setOf(),
    val hasUnreadPrivateMessages: Set<String> = setOf(),
    val unreadChannelMessages: Map<String, Int> = mapOf(),
    val peerFingerprints: Map<String, String> = emptyMap(),
    val peerSessionStates: Map<String, String> = emptyMap(),
    val geohashPeople: List<GeoPerson> = emptyList(),
    val pendingCommandFailure: com.bitchat.domain.chat.model.failure.CommandFailure? = null
)
