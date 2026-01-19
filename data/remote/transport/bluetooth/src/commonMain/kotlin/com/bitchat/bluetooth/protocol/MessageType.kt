package com.bitchat.bluetooth.protocol

enum class MessageType(val value: UByte) {
    ANNOUNCE(0x01u),
    MESSAGE(0x02u),  // All user messages (private and broadcast)
    LEAVE(0x03u),
    NOISE_HANDSHAKE(0x10u),  // Noise handshake
    NOISE_ENCRYPTED(0x11u),  // Noise encrypted transport message
    FRAGMENT(0x20u), // Fragmentation for large packets
    REQUEST_SYNC(0x21u), // GCS-based sync request
    FILE_TRANSFER(0x22u); // File transfer packet (BLE voice notes, etc.)

    companion object {
        fun fromValue(value: UByte): MessageType? {
            return entries.find { it.value == value }
        }
    }
}
