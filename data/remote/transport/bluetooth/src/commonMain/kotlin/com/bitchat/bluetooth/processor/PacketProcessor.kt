package com.bitchat.bluetooth.processor

import com.bitchat.bluetooth.handler.MessageHandler
import com.bitchat.bluetooth.manager.SecurityManager
import com.bitchat.bluetooth.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class PacketProcessor(
    private val myPeerID: String,
    private val securityManager: SecurityManager,
    private val messageHandler: MessageHandler
) {
    private val peerActors = mutableMapOf<String, Channel<BitchatPacket>>()
    private val processorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var delegate: PacketProcessorDelegate? = null

    fun processPacket(packet: BitchatPacket, peerID: String) {
        val actor = peerActors.getOrPut(peerID) {
            createPeerActor(peerID)
        }

        processorScope.launch {
            actor.send(packet)
        }
    }

    private fun createPeerActor(peerID: String): Channel<BitchatPacket> {
        val channel = Channel<BitchatPacket>(Channel.UNLIMITED)

        processorScope.launch {
            for (packet in channel) {
                handleReceivedPacket(packet, peerID)
            }
        }

        return channel
    }

    private suspend fun handleReceivedPacket(packet: BitchatPacket, peerID: String) {
        if (!securityManager.validatePacket(packet, peerID)) {
            return
        }

        if (shouldRelayPacket(packet)) {
            delegate?.onPacketShouldRelay(packet)
        }

        messageHandler.handlePacket(packet, peerID)
    }

    private fun shouldRelayPacket(packet: BitchatPacket): Boolean {
        return packet.ttl > 0u
    }

    fun shutdown() {
        peerActors.values.forEach { it.close() }
        peerActors.clear()
        processorScope.cancel()
    }
}

interface PacketProcessorDelegate {
    fun onPacketShouldRelay(packet: BitchatPacket)
}
