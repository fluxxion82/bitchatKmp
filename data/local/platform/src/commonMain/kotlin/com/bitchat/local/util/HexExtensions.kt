package com.bitchat.local.util

fun ByteArray.toHexString(): String =
    joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
