package com.bitchat.lora.bitchat.protocol

import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Reassembles fragmented LoRa frames back into complete messages.
 *
 * Features:
 * - Handles out-of-order fragment arrival
 * - Detects and ignores duplicate fragments
 * - Times out incomplete assemblies
 * - Thread-safe for concurrent frame processing
 */
@OptIn(ExperimentalTime::class)
class LoRaAssembler(
    /** Timeout for incomplete assemblies in milliseconds */
    private val assemblyTimeoutMs: Long = DEFAULT_ASSEMBLY_TIMEOUT_MS,
    /** Maximum number of concurrent assembly operations */
    private val maxConcurrentAssemblies: Int = DEFAULT_MAX_ASSEMBLIES,
    /** Time source for testing (returns current time in ms) */
    private val timeProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    private val mutex = Mutex()
    private val assemblies = mutableMapOf<UShort, AssemblyState>()
    private val recentlyCompletedIds = ArrayDeque<UShort>(COMPLETED_ID_CACHE_SIZE)

    /**
     * Process a received frame and return the complete message if assembly is done.
     *
     * @param frame The received LoRa frame
     * @return Complete reassembled message bytes, or null if more fragments needed
     */
    suspend fun processFrame(frame: LoRaFrame): ByteArray? {
        mutex.withLock {
            val messageId = frame.messageId

            // Check if this message was recently completed (duplicate detection)
            if (messageId in recentlyCompletedIds) {
                LoRaLogger.v(LoRaTags.ASSEMBLE, "Ignoring duplicate for completed msgId=$messageId")
                return null
            }

            // Clean up expired assemblies
            cleanupExpired()

            // Single-frame message (no fragmentation)
            if (frame.totalFragments == 1.toUByte()) {
                LoRaLogger.d(LoRaTags.ASSEMBLE, "Single-frame message received, msgId=$messageId")
                markCompleted(messageId)
                return frame.payload
            }

            // Get or create assembly state
            val state = assemblies.getOrPut(messageId) {
                if (assemblies.size >= maxConcurrentAssemblies) {
                    // Evict oldest assembly to make room
                    val oldest = assemblies.minByOrNull { it.value.startedAt }
                    if (oldest != null) {
                        LoRaLogger.w(LoRaTags.ASSEMBLE, "Evicting old assembly msgId=${oldest.key}")
                        assemblies.remove(oldest.key)
                    }
                }
                AssemblyState(
                    totalFragments = frame.totalFragments.toInt(),
                    startedAt = timeProvider()
                )
            }

            // Check for duplicate fragment
            if (state.receivedFragments.containsKey(frame.fragmentIndex.toInt())) {
                LoRaLogger.v(
                    LoRaTags.ASSEMBLE,
                    "Duplicate fragment ${frame.fragmentIndex}/${frame.totalFragments} for msgId=$messageId"
                )
                return null
            }

            // Store the fragment
            state.receivedFragments[frame.fragmentIndex.toInt()] = frame.payload
            state.lastActivityAt = timeProvider()

            LoRaLogger.d(
                LoRaTags.ASSEMBLE,
                "Received fragment ${frame.fragmentIndex + 1u}/${frame.totalFragments} for msgId=$messageId " +
                    "(${state.receivedFragments.size}/${state.totalFragments} total)"
            )

            // Check if assembly is complete
            if (state.receivedFragments.size == state.totalFragments) {
                val completeMessage = reassemble(state)
                assemblies.remove(messageId)
                markCompleted(messageId)

                LoRaLogger.i(
                    LoRaTags.ASSEMBLE,
                    "Assembly complete for msgId=$messageId: ${completeMessage.size} bytes"
                )

                return completeMessage
            }

            return null
        }
    }

    /**
     * Reassemble fragments in order.
     */
    private fun reassemble(state: AssemblyState): ByteArray {
        val totalSize = state.receivedFragments.values.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0

        for (i in 0 until state.totalFragments) {
            val fragment = state.receivedFragments[i]
                ?: throw IllegalStateException("Missing fragment $i during reassembly")
            fragment.copyInto(result, offset)
            offset += fragment.size
        }

        return result
    }

    /**
     * Remove assemblies that have timed out.
     */
    private fun cleanupExpired() {
        val now = timeProvider()
        val expired = assemblies.filter { (_, state) ->
            now - state.lastActivityAt > assemblyTimeoutMs
        }

        for ((messageId, state) in expired) {
            LoRaLogger.w(
                LoRaTags.ASSEMBLE,
                "Assembly timed out for msgId=$messageId " +
                    "(received ${state.receivedFragments.size}/${state.totalFragments} fragments)"
            )
            assemblies.remove(messageId)
        }
    }

    /**
     * Mark a message ID as recently completed to detect late duplicates.
     */
    private fun markCompleted(messageId: UShort) {
        if (recentlyCompletedIds.size >= COMPLETED_ID_CACHE_SIZE) {
            recentlyCompletedIds.removeFirst()
        }
        recentlyCompletedIds.addLast(messageId)
    }

    /**
     * Get current assembly statistics (for debugging/monitoring).
     */
    suspend fun getStats(): AssemblerStats {
        mutex.withLock {
            return AssemblerStats(
                activeAssemblies = assemblies.size,
                recentlyCompletedCount = recentlyCompletedIds.size
            )
        }
    }

    /**
     * Clear all pending assemblies (for shutdown/reset).
     */
    suspend fun clear() {
        mutex.withLock {
            val count = assemblies.size
            assemblies.clear()
            recentlyCompletedIds.clear()
            if (count > 0) {
                LoRaLogger.i(LoRaTags.ASSEMBLE, "Cleared $count pending assemblies")
            }
        }
    }

    /**
     * Internal state for a message being assembled.
     */
    private data class AssemblyState(
        val totalFragments: Int,
        val startedAt: Long,
        var lastActivityAt: Long = startedAt,
        val receivedFragments: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    /**
     * Statistics about assembler state.
     */
    data class AssemblerStats(
        val activeAssemblies: Int,
        val recentlyCompletedCount: Int
    )

    companion object {
        /** Default assembly timeout (30 seconds) */
        const val DEFAULT_ASSEMBLY_TIMEOUT_MS = 30_000L

        /** Default max concurrent assemblies */
        const val DEFAULT_MAX_ASSEMBLIES = 50

        /** Size of recently-completed ID cache for duplicate detection */
        private const val COMPLETED_ID_CACHE_SIZE = 100
    }
}
