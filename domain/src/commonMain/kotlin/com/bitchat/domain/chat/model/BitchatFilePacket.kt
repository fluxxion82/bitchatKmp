package com.bitchat.domain.chat.model

data class BitchatFilePacket(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val content: ByteArray
) {
    companion object {
        /**
         * The largest file content sent or accepted. It is 16 KiB under the frame limit so the
         * packet around it (headers, the name and type, a signature, Noise framing) always fits.
         */
        const val MAX_CONTENT_BYTES: Int = 1024 * 1024 - 16 * 1024
    }

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
