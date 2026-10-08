package com.bitchat.bluetooth.protocol

/** Compact delivery acknowledgement numbers carried inside an authenticated Noise payload. */
object DeliveredNumbers {
    const val MAX = 8

    fun encode(numbers: List<Long>): ByteArray? {
        if (numbers.size !in 1..MAX || numbers.any { it !in 0..0xffffffffL }) return null
        return ByteArray(1 + numbers.size * 4).also { data ->
            data[0] = numbers.size.toByte()
            numbers.forEachIndexed { index, number ->
                val offset = 1 + index * 4
                data[offset] = (number ushr 24).toByte()
                data[offset + 1] = (number ushr 16).toByte()
                data[offset + 2] = (number ushr 8).toByte()
                data[offset + 3] = number.toByte()
            }
        }
    }

    fun decode(data: ByteArray): List<Long>? {
        if (data.isEmpty()) return null
        // As a signed byte: anything over 127 is negative and refused with everything else that is not 1 to 8.
        val count = data[0].toInt()
        if (count !in 1..MAX || data.size != 1 + count * 4) return null
        return List(count) { index ->
            val offset = 1 + index * 4
            ((data[offset].toLong() and 0xff) shl 24) or
                ((data[offset + 1].toLong() and 0xff) shl 16) or
                ((data[offset + 2].toLong() and 0xff) shl 8) or
                (data[offset + 3].toLong() and 0xff)
        }
    }

    fun packetNumber(noisePayload: ByteArray): Long? =
        noisePayload.takeIf { it.size >= 4 }?.let {
            ((it[0].toLong() and 0xff) shl 24) or ((it[1].toLong() and 0xff) shl 16) or
                ((it[2].toLong() and 0xff) shl 8) or (it[3].toLong() and 0xff)
        }
}
