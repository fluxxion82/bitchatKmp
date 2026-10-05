package com.bitchat.domain.chat

import com.bitchat.domain.chat.repository.ChatRepository
import com.bitchat.domain.location.model.GeoPerson
import kotlinx.coroutines.flow.Flow

/**
 * The people reachable over LoRa who are not connected over the Bluetooth mesh right now. A device
 * reachable both ways appears once, as a mesh peer, for as long as the mesh connection lasts.
 *
 * LoRa peers are discovered via heartbeat broadcasts over LoRa radio.
 * Peers are automatically removed from the list if they haven't been
 * seen within the timeout period (typically 3 minutes).
 *
 * The flow emits whenever the peer list changes (new peer discovered,
 * peer info updated, or stale peer removed).
 */
class ObserveLoRaPeers(
    private val chatRepository: ChatRepository,
) {
    operator fun invoke(): Flow<List<GeoPerson>> {
        return chatRepository.observeLoRaPeers()
    }
}
