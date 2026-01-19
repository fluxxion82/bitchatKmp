package com.bitchat.domain.chat.model

sealed class ChatEvent {
    data object ChannelChanged : ChatEvent()
    data object ChannelJoined : ChatEvent()
    data object ChannelLeft : ChatEvent()
    data object MessageReceived : ChatEvent()
    data object PrivateChatsUpdated : ChatEvent()
    data object UnreadPrivatePeersUpdated : ChatEvent()
    data object SelectedPrivatePeerChanged : ChatEvent()
    data object LatestUnreadPrivatePeerChanged : ChatEvent()
    data object MeshPeersUpdated : ChatEvent()
    data object MeshMessagesUpdated : ChatEvent()
    data class GeohashMessagesUpdated(val geohash: String) : ChatEvent()
    data class GeohashParticipantsChanged(val geohash: String) : ChatEvent()

    data class NamedChannelMessagesUpdated(val channelName: String) : ChatEvent()
    data class ChannelMembersUpdated(val channelName: String) : ChatEvent()
    data class ChannelOwnershipVerified(val channelName: String) : ChatEvent()
    data object ChannelListUpdated : ChatEvent()
}
