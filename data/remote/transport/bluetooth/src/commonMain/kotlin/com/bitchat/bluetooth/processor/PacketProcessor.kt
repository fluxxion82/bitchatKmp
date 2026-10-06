package com.bitchat.bluetooth.processor

import com.bitchat.bluetooth.handler.MessageHandler
import com.bitchat.bluetooth.manager.SecurityManager
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MAX_QUEUED_MESH_BYTES_PER_LANE
import com.bitchat.bluetooth.protocol.MESH_LANE_CAPACITY
import com.bitchat.bluetooth.protocol.MESH_PACKET_LANES
import com.bitchat.bluetooth.protocol.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.withLock

class PacketProcessor(
    private val myPeerID: String,
    private val securityManager: SecurityManager,
    private val messageHandler: MessageHandler,
    laneCount: Int = MESH_PACKET_LANES,
    laneCapacity: Int = MESH_LANE_CAPACITY,
    private val maxQueuedBytesPerLane: Int = MAX_QUEUED_MESH_BYTES_PER_LANE,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private data class QueuedPacket(val packet: BitchatPacket, val peerID: String, val reservedBytes: Int)

    private val processorScope = CoroutineScope(dispatcher + SupervisorJob())
    private val countersLock = ReentrantLock()
    private val reservedBytes = IntArray(laneCount)
    private val lanes = List(laneCount) { lane ->
        Channel<QueuedPacket>(laneCapacity, onUndeliveredElement = { queued ->
            releaseReservation(lane, queued.reservedBytes)
        })
    }
    private var droppedPackets = 0L

    internal val droppedPacketCount: Long get() = countersLock.withLock { droppedPackets }
    internal val queuedBytes: Int get() = countersLock.withLock { reservedBytes.sum() }

    var delegate: PacketProcessorDelegate? = null

    init {
        lanes.forEachIndexed { laneIndex, lane ->
            processorScope.launch {
                for (queued in lane) {
                    try {
                        handleReceivedPacket(queued.packet, queued.peerID)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logError("PacketProcessor", "Packet handler failed peer=${queued.peerID} error=${e::class.simpleName}")
                    } finally {
                        releaseReservation(laneIndex, queued.reservedBytes)
                    }
                }
            }
        }
    }

    /** Stable hashing lets tests choose separate lanes without retaining a sender-keyed table. */
    internal fun laneOf(peerID: String): Int = (peerID.hashCode() and Int.MAX_VALUE) % lanes.size

    fun processPacket(packet: BitchatPacket, peerID: String): Boolean {
        val bytes = packet.payload.size + PACKET_OVERHEAD_BYTES
        val lane = laneOf(peerID)
        val accepted = countersLock.withLock {
            if (reservedBytes[lane] + bytes > maxQueuedBytesPerLane) false else {
                reservedBytes[lane] += bytes
                true
            }
        }
        if (!accepted) return dropped()
        if (lanes[lane].trySend(QueuedPacket(packet, peerID, bytes)).isSuccess) return true
        releaseReservation(lane, bytes)
        return dropped()
    }

    private fun releaseReservation(lane: Int, bytes: Int) {
        countersLock.withLock { reservedBytes[lane] -= bytes }
    }

    private fun dropped(): Boolean {
        val drops = countersLock.withLock { ++droppedPackets }
        if ((drops and 0xffL) == 0L) logError("PacketProcessor", "Dropped $drops mesh packets due to bounded intake")
        return false
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
        lanes.forEach { it.cancel() }
        processorScope.cancel()
    }

    private companion object {
        const val PACKET_OVERHEAD_BYTES = 64
    }
}

interface PacketProcessorDelegate {
    fun onPacketShouldRelay(packet: BitchatPacket)
}
