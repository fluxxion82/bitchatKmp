package com.bitchat.design.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.util.DebugLogger
import coil3.video.VideoFrameDecoder
import okio.FileSystem

actual fun newImageLoader(
    context: Any,
    debug: Boolean,
): Any? {
    val platformContext = context as PlatformContext
    return ImageLoader.Builder(platformContext)
        .networkCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            MemoryCache.Builder()
                .strongReferencesEnabled(true)
                .maxSizePercent(platformContext, percent = 0.55)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                .maxSizeBytes(512L * 1024 * 1024) // 512MB
                .build()
        }
        .components {
            getPlatformComponents().filterIsInstance<Decoder.Factory>().forEach {
                add(it)
            }
        }
        .crossfade(true)
        .apply {
            if (debug) {
                logger(DebugLogger())
            }
        }
        .build()
}

actual fun getPlatformComponents(): List<Any> = listOf(
    VideoFrameDecoder.Factory()
)
