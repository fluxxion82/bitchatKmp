package com.bitchat.nostr.util

/**
 * Extension functions for hex encoding/decoding
 */
fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}

/**
 * Convert full hex string to byte array
 */
fun hexStringToByteArray(hexString: String): ByteArray {
    val clean = if (hexString.length % 2 == 0) hexString else "0$hexString"
    return clean.chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()
}

fun UInt.toLittleEndianBytes(): ByteArray {
    val bytes = ByteArray(4)
    bytes[0] = (this and 0xFFu).toByte()
    bytes[1] = ((this shr 8) and 0xFFu).toByte()
    bytes[2] = ((this shr 16) and 0xFFu).toByte()
    bytes[3] = ((this shr 24) and 0xFFu).toByte()
    return bytes
}

/**
 * Convert hex string to Noise public key ByteArray
 */
fun String.fromNoiseKeyHex(): ByteArray {
    return this.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Convert ByteArray to hex string representation
 */
fun ByteArray.toHexString(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
