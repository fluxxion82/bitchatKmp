package com.bitchat.lora.bitchat.protocol

import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Splits large messages into LoRa-sized frames for transmission.
 *
 * Messages larger than [LoRaFrame.MAX_PAYLOAD] (232 bytes) are split into
 * multiple frames, each with a shared message ID and sequential fragment indices.
 */
class LoRaFragmenter {
    private val mutex = Mutex()
    private var messageIdCounter: UShort = 0u

    /**
     * Fragment a message into one or more LoRa frames.
     *
     * @param data The raw message bytes (e.g., serialized BitchatPacket)
     * @return List of LoRaFrames, each ready for transmission
     */
    suspend fun fragment(data: ByteArray): List<LoRaFrame> {
        return fragment(data, LoRaFrame.FLAG_NONE)
    }

    /**
     * Fragment a message into one or more LoRa frames with specific flags.
     *
     * @param data The raw message bytes (e.g., serialized BitchatPacket)
     * @param flags Frame flags to apply to all fragments (e.g., FLAG_HEARTBEAT)
     * @return List of LoRaFrames, each ready for transmission
     */
    suspend fun fragment(data: ByteArray, flags: UByte): List<LoRaFrame> {
        if (data.isEmpty()) {
            LoRaLogger.w(LoRaTags.FRAGMENT, "Attempted to fragment empty data")
            return emptyList()
        }

        val messageId = nextMessageId()
        val totalFragments = calculateFragmentCount(data.size)

        LoRaLogger.d(
            LoRaTags.FRAGMENT,
            "Fragmenting ${data.size} bytes into $totalFragments frame(s), msgId=$messageId, flags=$flags"
        )

        val frames = mutableListOf<LoRaFrame>()

        for (i in 0 until totalFragments) {
            val startOffset = i * LoRaFrame.MAX_PAYLOAD
            val endOffset = minOf(startOffset + LoRaFrame.MAX_PAYLOAD, data.size)
            val fragmentPayload = data.copyOfRange(startOffset, endOffset)

            val frame = LoRaFrame(
                messageId = messageId,
                fragmentIndex = i.toUByte(),
                totalFragments = totalFragments.toUByte(),
                flags = flags,
                payload = fragmentPayload
            )

            frames.add(frame)

            LoRaLogger.v(
                LoRaTags.FRAGMENT,
                "Created frame $i/${totalFragments - 1}: ${fragmentPayload.size} bytes"
            )
        }

        return frames
    }

    /**
     * Calculate how many fragments are needed for a given data size.
     */
    fun calculateFragmentCount(dataSize: Int): Int {
        if (dataSize <= 0) return 0
        return (dataSize + LoRaFrame.MAX_PAYLOAD - 1) / LoRaFrame.MAX_PAYLOAD
    }

    /**
     * Get the next message ID (thread-safe increment with wrap).
     */
    private suspend fun nextMessageId(): UShort {
        return mutex.withLock {
            val id = messageIdCounter
            messageIdCounter = ((messageIdCounter.toInt() + 1) and 0xFFFF).toUShort()
            id
        }
    }

    /**
     * Reset the message ID counter (for testing).
     */
    internal fun resetMessageIdCounter() {
        messageIdCounter = 0u
    }
}
