package com.bitchat.bluetooth.util

expect suspend fun loadFileBytes(path: String): ByteArray
expect fun getFileName(path: String): String
expect fun getMimeType(path: String): String
expect suspend fun getFileSize(path: String): Long
