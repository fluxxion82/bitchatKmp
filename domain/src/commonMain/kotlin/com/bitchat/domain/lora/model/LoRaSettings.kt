package com.bitchat.domain.lora.model


enum class LoRaRegion(val frequency: Long) {
    /** US ISM band (915 MHz) */
    US_915(915_000_000L),

    /** EU ISM band (868 MHz) */
    EU_868(868_000_000L),

    /** AU ISM band (915 MHz) */
    AU_915(915_000_000L),

    /** AS ISM band (923 MHz) */
    AS_923(923_000_000L)
}

enum class LoRaTxPower(val dBm: Int) {
    /** Low power (10 dBm) */
    LOW(10),

    /** Medium power (17 dBm) */
    MEDIUM(17),

    /** High power (20 dBm) */
    HIGH(20)
}

enum class LoRaProtocolType(val displayName: String) {
    BITCHAT("BitChat"),
    MESHTASTIC("Meshtastic"),
    MESHCORE("MeshCore")
}

data class LoRaSettings(
    val enabled: Boolean = true,
    val region: LoRaRegion = LoRaRegion.US_915,
    val txPower: LoRaTxPower = LoRaTxPower.MEDIUM,
    val showPeers: Boolean = true,
    val protocol: LoRaProtocolType = LoRaProtocolType.BITCHAT
)
