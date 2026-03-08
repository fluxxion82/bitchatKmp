package com.bitchat.lora.bitchat.protocol

/**
 * Deterministic ASCII beacon contract used for RangePi bring-up:
 * RPIB1|seq=<uint>|mode=<BEACON|BRIDGE>|chan=<int>|hz=<int>|crc=<hex4>
 */
object RangePiBeacon {

    private const val PREFIX = "RPIB1"
    private val BEACON_REGEX =
        Regex("""RPIB1\|seq=(\d+)\|mode=(BEACON|BRIDGE)\|chan=(-?\d+)\|hz=(\d+)\|crc=([0-9A-Fa-f]{4})""")

    enum class Mode {
        BEACON,
        BRIDGE
    }

    data class Beacon(
        val sequence: UInt,
        val mode: Mode,
        val channel: Int,
        val frequencyHz: Int
    )

    enum class InvalidReason {
        MALFORMED,
        CRC_MISMATCH
    }

    sealed class ParseResult {
        data class Valid(val beacon: Beacon) : ParseResult()
        data class Invalid(val reason: InvalidReason, val detail: String) : ParseResult()
        data object NotBeacon : ParseResult()
    }

    fun format(
        sequence: UInt,
        mode: Mode,
        channel: Int,
        frequencyHz: Int
    ): String {
        val base = "$PREFIX|seq=$sequence|mode=${mode.name}|chan=$channel|hz=$frequencyHz"
        val crc = crc16Ccitt(base.encodeToByteArray())
        return "$base|crc=${crc.toString(16).uppercase().padStart(4, '0')}"
    }

    fun parse(bytes: ByteArray): ParseResult {
        val ascii = bytes.toAsciiSanitized()
        val prefixIndex = ascii.indexOf("$PREFIX|")
        if (prefixIndex < 0) {
            return ParseResult.NotBeacon
        }
        val text = ascii.substring(prefixIndex)
        val match = BEACON_REGEX.find(text)
            ?: return ParseResult.Invalid(InvalidReason.MALFORMED, "Beacon prefix found but contract was incomplete")

        val seqField = match.groupValues[1]
        val modeField = match.groupValues[2]
        val channelField = match.groupValues[3]
        val hzField = match.groupValues[4]
        val crcField = match.groupValues[5]
        val base = "$PREFIX|seq=$seqField|mode=$modeField|chan=$channelField|hz=$hzField"

        val seq = seqField.toUIntOrNull()
            ?: return ParseResult.Invalid(InvalidReason.MALFORMED, "Missing/invalid seq")
        val mode = runCatching { Mode.valueOf(modeField) }.getOrNull()
            ?: return ParseResult.Invalid(InvalidReason.MALFORMED, "Missing/invalid mode")
        val channel = channelField.toIntOrNull()
            ?: return ParseResult.Invalid(InvalidReason.MALFORMED, "Missing/invalid chan")
        val frequencyHz = hzField.toIntOrNull()
            ?: return ParseResult.Invalid(InvalidReason.MALFORMED, "Missing/invalid hz")

        if (crcField.length != 4 || !crcField.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
            return ParseResult.Invalid(InvalidReason.MALFORMED, "Invalid crc format")
        }
        val expectedCrc = crcField.toInt(16)

        val actualCrc = crc16Ccitt(base.encodeToByteArray())
        if (actualCrc != expectedCrc) {
            return ParseResult.Invalid(
                InvalidReason.CRC_MISMATCH,
                "CRC mismatch expected=${expectedCrc.toString(16)} actual=${actualCrc.toString(16)}"
            )
        }

        return ParseResult.Valid(
            Beacon(
                sequence = seq,
                mode = mode,
                channel = channel,
                frequencyHz = frequencyHz
            )
        )
    }

    internal fun crc16Ccitt(data: ByteArray): Int {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun ByteArray.toAsciiSanitized(): String {
        if (isEmpty()) return ""
        val chars = CharArray(size)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i] = if (v in 32..126) v.toChar() else ' '
        }
        return chars.concatToString()
    }
}
