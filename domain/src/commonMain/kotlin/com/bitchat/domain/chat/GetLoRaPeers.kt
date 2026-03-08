package com.bitchat.domain.chat

import com.bitchat.domain.base.Usecase
import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson

/**
 * Use case to retrieve currently discovered LoRa peers.
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
