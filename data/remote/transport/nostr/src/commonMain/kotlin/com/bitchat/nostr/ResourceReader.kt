package com.bitchat.nostr

interface ResourceReader {
    fun readResourceFile(filename: String): ByteArray?
    fun readFile(filepath: String): ByteArray?
    fun writeFile(data: ByteArray, filepath: String): Boolean
}
