package com.bitchat.domain.chat.repository

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun getGeohashMessages(geohash: String): List<BitchatMessage>
    suspend fun getMeshMessages(): List<BitchatMessage>
    suspend fun getMeshPeers(): List<GeoPerson>

    suspend fun getGeohashParticipants(geohash: String): Map<String, String>
    suspend fun getPrivateChats(): Map<String, List<BitchatMessage>>
    fun observeMiningStatus(): Flow<String?>
    suspend fun getUnreadPrivatePeers(): Set<String>
    suspend fun getPeerSessionStates(): Map<String, String>
    suspend fun getLatestUnreadPrivatePeer(): String?
    suspend fun getSelectedPrivatePeer(): String?
    suspend fun setSelectedPrivatePeer(peerID: String?)
    suspend fun markPrivateChatRead(peerID: String)
    suspend fun sendGeohashMessage(content: String, geohash: String, nickname: String)
    suspend fun sendMeshMessage(content: String, nickname: String)

    suspend fun sendPrivate(content: String, toPeerID: String, recipientNickname: String)
    suspend fun sendReadReceipt(originalMessageID: String, readerPeerID: String?, toPeerID: String)
    suspend fun sendDeliveryAck(messageID: String, toPeerID: String)
    suspend fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean)
    suspend fun onPeersUpdated(peers: List<String>)
    suspend fun onSessionEstablished(peerID: String)

    suspend fun joinChannel(channel: String, password: String? = null): Boolean
    suspend fun leaveChannel(channel: String)
    suspend fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    suspend fun addChannelMessage(channel: String, message: BitchatMessage, senderPeerID: String?)
    suspend fun removeChannelMember(channel: String, peerID: String)
    suspend fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String)

    suspend fun hasChannelKey(channel: String): Boolean
    suspend fun isChannelCreator(channel: String, peerID: String): Boolean
    suspend fun getJoinedChannelsList(): List<String>
    suspend fun loadChannelData(): Pair<Set<String>, Set<String>>
    suspend fun setChannelPassword(channel: String, password: String)
    suspend fun clearAllChannels()
    suspend fun clearMessages(channel: Channel)

    suspend fun setSelectedChannel(channel: Channel)
    suspend fun storePersonDataForDM(peerID: String, fullPubkey: String, sourceGeohash: String?, displayName: String? = null)
    suspend fun getFullPubkey(peerID: String): String?
    suspend fun getSourceGeohash(peerID: String): String?
    suspend fun getDisplayName(peerID: String): String?
    suspend fun sendMessage(
        content: String,
        channel: Channel,
        sender: String,
        messageType: BitchatMessageType = BitchatMessageType.Message
    )

    suspend fun getNamedChannelMessages(channelName: String): List<BitchatMessage>
    suspend fun addNamedChannelMessage(channelName: String, message: BitchatMessage)
    suspend fun getChannelMembers(channelName: String): List<com.bitchat.domain.chat.model.ChannelMember>
    suspend fun addChannelMember(channelName: String, member: com.bitchat.domain.chat.model.ChannelMember)
    suspend fun isChannelOwner(channelName: String): Boolean

    suspend fun verifyPasswordOwnership(channelName: String, password: String): Boolean
    suspend fun getChannelCreatorNpub(channelName: String): String?
    suspend fun getChannelKeyCommitment(channelName: String): String?
    suspend fun encryptChannelMessage(plaintext: String, channelName: String): ByteArray?
    suspend fun deriveChannelKey(channelName: String, password: String): ByteArray
    suspend fun calculateKeyCommitment(key: ByteArray): String
    suspend fun getAvailableChannels(): List<com.bitchat.domain.chat.model.ChannelInfo>
    fun observeJoinedNamedChannels(): Flow<List<com.bitchat.domain.chat.model.ChannelInfo>>
    suspend fun discoverNamedChannel(channelName: String): com.bitchat.domain.chat.model.ChannelInfo?
    suspend fun ensureNamedChannelMetadata(channelInfo: com.bitchat.domain.chat.model.ChannelInfo)
    suspend fun createNamedChannel(channelName: String, password: String?): com.bitchat.domain.chat.model.ChannelInfo

    suspend fun clearData()
}
