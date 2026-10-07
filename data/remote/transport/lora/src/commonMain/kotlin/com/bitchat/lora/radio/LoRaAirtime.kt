package com.bitchat.lora.radio

/**
 * The time on air, in microseconds, of one LoRa frame of [frameBytes] bytes (everything handed to the
 * radio) under this configuration, with an explicit header: Semtech's formula (AN1200.13).
 *
 * The frame's CRC is always counted, whatever [LoRaConfig.enableCrc] says: the radio driver switches it
 * on unconditionally, and a budget charged from this must never come out below what is transmitted.
 */
fun LoRaConfig.airtimeMicros(frameBytes: Int): Long {
    require(frameBytes >= 0) { "Frame bytes must not be negative" }

    val lowDataRateOptimize = spreadingFactor >= 11 && bandwidth <= 125_000L
    val numerator = 8L * frameBytes - 4L * spreadingFactor + 28 + 16
    val denominator = 4L * (spreadingFactor - if (lowDataRateOptimize) 2 else 0)
    val payloadSymbols = 8L + if (numerator > 0) {
        ((numerator + denominator - 1) / denominator) * codingRate
    } else {
        0
    }
    val totalQuarterSymbols = (preambleLength + payloadSymbols) * 4 + 17

    return totalQuarterSymbols * symbolDurationMicros() / 4
}

/** [airtimeMicros] in whole milliseconds, rounded up: what a budget is charged. */
fun LoRaConfig.airtimeMs(frameBytes: Int): Long = (airtimeMicros(frameBytes) + 999) / 1_000

/**
 * How long a symbol lasts under this configuration relative to SF9 at 125 kHz (1.0 there, 0.25 at SF9 and
 * 500 kHz, 8.0 at SF12 and 125 kHz). Limits on time on air that were worked out for SF9 at 125 kHz are
 * multiplied by this.
 */
fun LoRaConfig.symbolTimeScale(): Double = symbolDurationMicros().toDouble() / 4_096

private fun LoRaConfig.symbolDurationMicros(): Long =
    (1L shl spreadingFactor) * 1_000_000L / bandwidth
