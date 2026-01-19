package com.bitchat.domain.chat.model

data class BitchatFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as BitchatFilePacket
        return fileName == other.fileName &&
                fileSize == other.fileSize &&
                mimeType == other.mimeType &&
                content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + fileSize.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
