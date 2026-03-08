package com.bitchat.lora.radio

/**
 * Events emitted by the LoRa radio.
 */
sealed class LoRaEvent {
    /**
     * Radio is ready and configured.
     */
    data class RadioReady(
        val config: LoRaConfig
    ) : LoRaEvent()

    /**
     * Packet received from the radio.
     *
     * @param data Raw bytes received (includes LoRaFrame header)
     * @param rssi Received Signal Strength Indicator in dBm
     * @param snr Signal-to-Noise Ratio in dB
     */
    data class PacketReceived(
        val data: ByteArray,
        val rssi: Int,
        val snr: Float
    ) : LoRaEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false

            other as PacketReceived

            if (!data.contentEquals(other.data)) return false
            if (rssi != other.rssi) return false
            if (snr != other.snr) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + rssi
            result = 31 * result + snr.hashCode()
            return result
        }
    }

    /**
     * Packet was successfully transmitted.
     *
     * @param messageId The message ID of the sent packet
     */
    data class SendComplete(
        val messageId: UShort
    ) : LoRaEvent()

    /**
     * Error occurred during radio operation.
     *
     * @param message Description of the error
     * @param cause Optional underlying exception
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : LoRaEvent()

    /**
     * Radio disconnected (USB unplugged, SPI error, etc.)
     */
    data object Disconnected : LoRaEvent()

    /**
     * Channel activity detected (for carrier sense).
     *
     * @param busy True if channel is currently busy
     */
    data class ChannelActivity(
        val busy: Boolean
    ) : LoRaEvent()
}
