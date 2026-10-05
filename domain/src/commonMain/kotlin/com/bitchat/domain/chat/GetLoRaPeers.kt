package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson

/**
 * The people reachable over LoRa whom the mesh does not already know: not connected over Bluetooth
 * and not the other side of a private chat. Such a device appears once, as a mesh peer.
 *
 * LoRa peers are discovered via heartbeat broadcasts over LoRa radio.
 * Peers are automatically removed from the list if they haven't been
 * seen within the timeout period (typically 3 minutes).
 */
class GetLoRaPeers(
    private val chatRepository: ChatRepository,
) : Usecase<Unit, List<GeoPerson>> {
    override suspend fun invoke(param: Unit): List<GeoPerson> {
        return chatRepository.getLoRaPeers()
    }
}
