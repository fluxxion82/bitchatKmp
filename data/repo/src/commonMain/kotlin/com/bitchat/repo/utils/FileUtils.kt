package com.bitchat.repo.utils

expect inline fun getFileBytes(path: String): ByteArray?

expect inline fun saveFile(path: String, fileName: String, fileBytes: ByteArray): String
