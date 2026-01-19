package com.bitchat.transport

interface TransportIdentityProvider {
    fun loadKey(key: String): String?
    fun saveKey(key: String, value: String)
    fun hasKey(key: String): Boolean
    fun removeKeys(vararg keys: String)

    fun clearAll()
}
