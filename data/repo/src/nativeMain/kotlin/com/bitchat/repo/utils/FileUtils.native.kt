package com.bitchat.repo.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.getBytes

actual inline fun getFileBytes(path: String): ByteArray? {
    val mediaPathUrl = NSURL.fileURLWithPath(path)
    val fileNSData = NSData.dataWithContentsOfURL(mediaPathUrl)
    return fileNSData?.toByteArray()
}

actual inline fun saveFile(path: String, fileName: String, fileBytes: ByteArray): String {
    TODO("Not yet implemented")
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val byteArray = ByteArray(this.length.toInt())
    byteArray.usePinned {
        this.getBytes(it.addressOf(0), this.length)
    }
    return byteArray
}
