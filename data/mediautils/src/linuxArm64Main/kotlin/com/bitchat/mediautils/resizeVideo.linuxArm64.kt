package com.bitchat.mediautils

/**
 * Video resize is not supported on embedded platform.
 * Throws UnsupportedOperationException.
 */
actual suspend fun resizeVideo(inputVideoPath: String): ByteArray {
    throw UnsupportedOperationException("Video processing is not supported on embedded platform")
}
