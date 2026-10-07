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

/** The radio bandwidth of the bitchat LoRa stack. Both ends must use the same one to hear each other. */
enum class LoRaBandwidth(val hz: Long) {
    /** The longest range, with a full frame taking about 1.2 seconds on air at SF9. */
    KHZ_125(125_000L),

    /** A balance between shorter airtime and longer range. */
    KHZ_250(250_000L),

    /** About a quarter of the airtime, with roughly half the range. */
    KHZ_500(500_000L)
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
