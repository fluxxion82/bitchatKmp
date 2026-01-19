package com.bitchat.mediautils

import android.graphics.Bitmap
import android.util.LruCache
import java.io.ByteArrayOutputStream

internal object BitmapCache {
    internal val instance: LruCache<String, Bitmap> by lazy {
        LruCache<String, Bitmap>(calculateMemoryCacheSize())
    }

    private fun calculateMemoryCacheSize(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val cacheSize = (maxMemory * 0.25).toInt()
        return cacheSize.coerceAtLeast(1024 * 1024)
    }

    internal fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            return stream.toByteArray()
        }
    }
}
