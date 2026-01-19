package com.bitchat.bluetooth.protocol

object MessagePadding {
    private val blockSizes = listOf(256, 512, 1024, 2048)

    fun optimalBlockSize(dataSize: Int): Int {
        val totalSize = dataSize + 16
        for (blockSize in blockSizes) {
            if (totalSize <= blockSize) {
                return blockSize
            }
        }

        return dataSize
    }

    fun pad(data: ByteArray, targetSize: Int): ByteArray {
        if (data.size >= targetSize) return data

        val paddingNeeded = targetSize - data.size

        if (paddingNeeded <= 0 || paddingNeeded > 255) return data

        val result = ByteArray(targetSize)

        data.copyInto(result, 0, 0, data.size)

        for (i in data.size until targetSize) {
            result[i] = paddingNeeded.toByte()
        }

        return result
    }

    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data

        val last = data[data.size - 1]
        val paddingLength = last.toInt() and 0xFF

        if (paddingLength <= 0 || paddingLength > data.size) return data

        val start = data.size - paddingLength
        for (i in start until data.size) {
            if (data[i] != last) {
                return data
            }
        }

        return data.copyOfRange(0, start)
    }
}
