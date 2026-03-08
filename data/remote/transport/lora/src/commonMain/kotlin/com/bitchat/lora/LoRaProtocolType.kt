package com.bitchat.lora

/**
 * Enum representing available LoRa protocol implementations.
 */
enum class LoRaProtocolType {
    /**
     * BitChat native protocol.
     *
     * Uses custom framing with heartbeat-based peer discovery.
     * Works with Teensy/RFM95W and RangePi hardware.
     */
    BITCHAT,

    /**
     * Meshtastic protocol.
     *
     * Uses Meshtastic protobuf encoding and NodeDB for peer discovery.
     * Works with devices running Meshtastic firmware.
     */
    MESHTASTIC,

    /**
     * MeshCore protocol.
     *
     * Uses MeshCore companion protocol over TCP to meshcore-pi daemon.
     * Supports ED25519 cryptography and flood/direct mesh routing.
     * Works with SX1276/RFM95W radios via meshcore-pi on Orange Pi.
     */
    MESHCORE
}
