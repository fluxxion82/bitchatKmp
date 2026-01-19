package com.bitchat.nostr

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.getBytes
import platform.Foundation.writeToURL

class NativeResourceReader : ResourceReader {
    override fun readResourceFile(filename: String): ByteArray? {
        val bundle = NSBundle.mainBundle
        val path = bundle.pathForResource(filename.substringBeforeLast("."), ofType = filename.substringAfterLast("."))
        val data = path?.let { NSData.dataWithContentsOfFile(it) }
        return data?.toByteArray()
    }

    override fun readFile(filepath: String): ByteArray? {
        val fileManager = NSFileManager.defaultManager
        val documentsPath = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
        val fileUrl = documentsPath.URLByAppendingPathComponent(filepath) ?: return null
        val data = NSData.dataWithContentsOfURL(fileUrl)
        return data?.toByteArray()
    }

    override fun writeFile(data: ByteArray, filepath: String): Boolean {
        val fileManager = NSFileManager.defaultManager
        val documentsPath = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
        val fileUrl = documentsPath.URLByAppendingPathComponent(filepath) ?: return false
        val nsData = data.toNSData()
        return nsData.writeToURL(fileUrl, atomically = true)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun NSData.toByteArray(): ByteArray {
        val byteArray = ByteArray(this.length.toInt())
        byteArray.usePinned {
            this.getBytes(it.addressOf(0), this.length)
        }
        return byteArray
    }

    @OptIn(ExperimentalForeignApi::class)
    fun ByteArray.toNSData(): NSData {
        return this.usePinned { pinnedByteArray ->
            NSData.create(bytes = pinnedByteArray.addressOf(0), length = this.size.toULong())
        }
    }
}
