package com.bitchat.lora.radio

/**
 * Configuration for LoRa radio parameters.
 *
 * Default configuration is optimized for US 915MHz band with
 * balanced range/speed tradeoff (SF9, BW125kHz).
 */
data class LoRaConfig(
    /** Frequency in Hz (default: 915 MHz for US) */
    val frequency: Long = 915_000_000L,

    /** Spreading factor (7-12). Higher = longer range, slower speed */
    val spreadingFactor: Int = 9,

    /** Bandwidth in Hz (125000, 250000, or 500000) */
    val bandwidth: Long = 125_000L,

    /** Coding rate denominator (5-8 for 4/5 to 4/8) */
    val codingRate: Int = 5,

    /** Transmit power in dBm (2-20 depending on module) */
    val txPower: Int = 17,

    /** Sync word for network identification (0xBC for BitChat) */
    val syncWord: Int = 0xBC,

    /** Preamble length (6-65535, default 8) */
    val preambleLength: Int = 8,

    /** Enable CRC checking */
    val enableCrc: Boolean = true
) {
    init {
        require(spreadingFactor in 7..12) {
            "Spreading factor must be between 7 and 12"
        }
        require(bandwidth in listOf(125_000L, 250_000L, 500_000L)) {
            "Bandwidth must be 125000, 250000, or 500000 Hz"
        }
        require(codingRate in 5..8) {
            "Coding rate must be between 5 (4/5) and 8 (4/8)"
        }
        require(txPower in 2..20) {
            "TX power must be between 2 and 20 dBm"
        }
        require(syncWord in 0x00..0xFF) {
            "Sync word must be a single byte (0x00-0xFF)"
        }
        require(preambleLength in 6..65535) {
            "Preamble length must be between 6 and 65535"
        }
    }

    companion object {
        /** US ISM band (915 MHz) */
        val US_915 = LoRaConfig(frequency = 915_000_000L)

        /** EU ISM band (868 MHz) */
        val EU_868 = LoRaConfig(frequency = 868_000_000L)

        /** Long range configuration (SF12, lower data rate) */
        val LONG_RANGE = LoRaConfig(
            spreadingFactor = 12,
            bandwidth = 125_000L,
            txPower = 20
        )

        /** Fast configuration (SF7, higher data rate, shorter range) */
        val FAST = LoRaConfig(
            spreadingFactor = 7,
            bandwidth = 250_000L,
            txPower = 14
        )
    }
}
