package com.bitchat.mediautils.model

private const val DEFAULT_RESIZE_IMAGE_WIDTH = 1080
private const val DEFAULT_RESIZE_IMAGE_HEIGHT = 1350
private const val DEFAULT_RESIZE_THRESHOLD_BYTES = 1048576L // 1MB

data class ResizeOptions(
    val width: Int = DEFAULT_RESIZE_IMAGE_WIDTH,
    val height: Int = DEFAULT_RESIZE_IMAGE_HEIGHT,
    val resizeThresholdBytes: Long = DEFAULT_RESIZE_THRESHOLD_BYTES,
    // @FloatRange(from = 0.0, to = 1.0)
    val compressionQuality: Double = 1.0,
)
