package com.bitchat.domain.chat.repository

import com.bitchat.domain.chat.model.BitchatMessage
import com.bitchat.domain.chat.model.BitchatMessageType
import com.bitchat.domain.chat.model.LoRaPerson
import com.bitchat.domain.location.model.Channel
import com.bitchat.domain.location.model.GeoPerson
import com.bitchat.domain.lora.model.LoRaRegion
import com.bitchat.domain.lora.model.LoRaTxPower
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun getGeohashMessages(geohash: String): List<BitchatMessage>
    suspend fun getMeshMessages(): List<BitchatMessage>
    suspend fun getMeshPeers(): List<GeoPerson>

    /**
     * The people the active LoRa transport hears right now, under their own device ids and without
     * any merging. A peer whose mesh identity is blocked is left out, judged at the time of the call.
     */
    suspend fun getLoRaPeers(): List<LoRaPerson>

    /** Emits when the LoRa transport's peers change, and once at the start. Read [getLoRaPeers] for the list. */
    fun observeLoRaPeerChanges(): Flow<Unit>

    /**
     * Every private chat's conversation key with what its other side is called: the sender of the
     * latest message that came from it, or null when none has. Unlike [getPrivateChats] this copies no
     * history. Like it, it reads what other coroutines may be writing, so it can fail when a read
     * collides with a write; the write's own change event is the moment to read again.
     */
    suspend fun getPrivateChatNames(): Map<String, String?>

    /**
     * Switch the active LoRa protocol at runtime.
     *
     * @param protocol Protocol name: "BITCHAT" or "MESHTASTIC"
     * @return true if the protocol was switched successfully
     */
    suspend fun switchLoRaProtocol(protocol: String): Boolean

    /**
     * Reconfigure active LoRa transport at runtime (frequency + tx power).
     *
     * @return true if the transport was restarted/reconfigured successfully
     */
    suspend fun reconfigureLoRa(region: LoRaRegion, txPower: LoRaTxPower): Boolean

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

    suspend fun getJoinedChannelsList(): List<String>
    suspend fun setChannelPassword(channel: String, password: String)
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
    suspend fun isChannelOwner(channelName: String): Boolean

    suspend fun verifyPasswordOwnership(channelName: String, password: String): Boolean
    suspend fun getChannelKeyCommitment(channelName: String): String?
    suspend fun deriveChannelKey(channelName: String, password: String): ByteArray
    suspend fun calculateKeyCommitment(key: ByteArray): String
    suspend fun getAvailableChannels(): List<com.bitchat.domain.chat.model.ChannelInfo>
    suspend fun discoverNamedChannel(channelName: String): com.bitchat.domain.chat.model.ChannelInfo?
    suspend fun ensureNamedChannelMetadata(channelInfo: com.bitchat.domain.chat.model.ChannelInfo)
    suspend fun createNamedChannel(channelName: String, password: String?): com.bitchat.domain.chat.model.ChannelInfo

    suspend fun clearData()
}
