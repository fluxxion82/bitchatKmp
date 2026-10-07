package com.bitchat.lora.bitchat.protocol

/**
 * Encodes the one-frame envelope used for mesh packets carried by the radio.
 *
 * Fragment index and count are both zero on purpose: that is not a valid [LoRaFrame], so a build
 * that does not know this kind of frame drops it instead of showing its bytes as a public message.
 */
object MeshPacketFrame {
    /** Encodes [packet] as one complete mesh packet frame, or null when it cannot fit in one frame. */
    fun encode(messageId: UShort, packet: ByteArray): ByteArray? {
        if (packet.isEmpty() || packet.size > LoRaFrame.MAX_PAYLOAD) return null

        return ByteArray(LoRaFrame.HEADER_SIZE + packet.size).also { frame ->
            frame[0] = (messageId.toInt() shr 8).toByte()
            frame[1] = messageId.toByte()
            frame[2] = 0
            frame[3] = 0
            frame[4] = LoRaFrame.FLAG_PACKET.toByte()
            packet.copyInto(frame, LoRaFrame.HEADER_SIZE)
        }
    }

    /** Decodes a whole mesh packet only when [frame] has the exact packet-frame shape. */
    fun decode(frame: ByteArray): ByteArray? {
        if (frame.size !in (LoRaFrame.HEADER_SIZE + 1)..LoRaFrame.MAX_FRAME_SIZE) return null
        if (frame[2] != 0.toByte() || frame[3] != 0.toByte()) return null
        if (frame[4].toUByte() != LoRaFrame.FLAG_PACKET) return null
        return frame.copyOfRange(LoRaFrame.HEADER_SIZE, frame.size)
    }
}
