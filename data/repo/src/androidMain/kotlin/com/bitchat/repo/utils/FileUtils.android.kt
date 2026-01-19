package com.bitchat.repo.utils

import java.io.File

actual inline fun getFileBytes(path: String): ByteArray? {
    return File(path).readBytes()
}

actual inline fun saveFile(path: String, fileName: String, fileBytes: ByteArray): String {
    val file = File(path, fileName)
    file.writeBytes(fileBytes)
    return file.absolutePath
}
