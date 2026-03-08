package com.bitchat.lora.bitchat.logging

/**
 * Logging interface for LoRa transport operations.
 *
 * Provides debug, info, warning, and error logging with tag support.
 * Platform-specific implementations handle actual log output.
 */
expect object LoRaLogger {
    /** Debug level logging */
    fun d(tag: String, message: String)

    /** Info level logging */
    fun i(tag: String, message: String)

    /** Warning level logging */
    fun w(tag: String, message: String)

    /** Error level logging */
    fun e(tag: String, message: String, throwable: Throwable? = null)

    /** Verbose level logging (for detailed protocol tracing) */
    fun v(tag: String, message: String)
}

/**
 * Common log tags for LoRa module components.
 */
object LoRaTags {
    const val RADIO = "LoRa.Radio"
    const val TRANSPORT = "LoRa.Transport"
    const val FRAME = "LoRa.Frame"
    const val FRAGMENT = "LoRa.Fragment"
    const val ASSEMBLE = "LoRa.Assemble"
    const val USB = "LoRa.USB"
    const val SPI = "LoRa.SPI"
    const val AT_CMD = "LoRa.AT"
}
