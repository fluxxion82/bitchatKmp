package com.bitchat.bluetooth.manager

import com.bitchat.bluetooth.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Manages message fragmentation and reassembly for large packets
 */
class FragmentManager {
    // Fragment reassembly state: fragmentID -> (fragments, timestamp)
    private val fragmentState = mutableMapOf<String, Pair<MutableList<ByteArray>, Long>>()

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Check if packet needs fragmentation
     */
    fun needsFragmentation(packet: BitchatPacket): Boolean {
        val binaryData = packet.toBinaryData() ?: return false
        return binaryData.size > FRAGMENT_THRESHOLD
    }

    /**
     * Create fragments from a large packet
     * MVP: Returns list of fragments (simplified - actual implementation would create proper fragment packets)
     */
    fun createFragments(packet: BitchatPacket): List<ByteArray> {
        val binaryData = packet.toBinaryData() ?: return emptyList()
        if (binaryData.size <= FRAGMENT_THRESHOLD) return listOf(binaryData)

        val fragments = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < binaryData.size) {
            val remaining = binaryData.size - offset
            val fragmentSize = minOf(remaining, MAX_FRAGMENT_SIZE)
            val fragment = binaryData.copyOfRange(offset, offset + fragmentSize)
            fragments.add(fragment)
            offset += fragmentSize
        }

        return fragments
    }

    /**
     * Handle incoming fragment
     * MVP: Simplified implementation - returns reassembled packet when complete
     */
    fun handleFragment(fragmentID: String, fragmentData: ByteArray, isLastFragment: Boolean): BitchatPacket? {
        val now = Clock.System.now().toEpochMilliseconds()

        // Get or create fragment state
        val (fragments, timestamp) = fragmentState.getOrPut(fragmentID) {
            Pair(mutableListOf(), now)
        }

        // Add fragment
        fragments.add(fragmentData)

        // If last fragment, reassemble
        if (isLastFragment) {
            fragmentState.remove(fragmentID)
            val reassembled = reassembleFragments(fragments)
            return BitchatPacket.fromBinaryData(reassembled)
        }

        return null
    }

    /**
     * Reassemble fragments into complete packet data
     */
    private fun reassembleFragments(fragments: List<ByteArray>): ByteArray {
        val totalSize = fragments.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0

        for (fragment in fragments) {
            fragment.copyInto(result, offset)
            offset += fragment.size
        }

        return result
    }

    /**
     * Clean up expired fragments
     */
    fun cleanupExpiredFragments() {
        val now = Clock.System.now().toEpochMilliseconds()
        val timeoutMs = FRAGMENT_TIMEOUT.inWholeMilliseconds

        val expired = fragmentState.filter { (_, pair) ->
            (now - pair.second) > timeoutMs
        }.keys.toList()

        expired.forEach { fragmentID ->
            fragmentState.remove(fragmentID)
        }
    }

    /**
     * Shutdown manager
     */
    fun shutdown() {
        managerScope.cancel()
        fragmentState.clear()
    }

    companion object {
        private const val FRAGMENT_THRESHOLD = 512 // bytes
        private const val MAX_FRAGMENT_SIZE = 469 // bytes
        private val FRAGMENT_TIMEOUT = 30.seconds
    }
}
