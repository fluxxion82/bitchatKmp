package com.bitchat.design.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.Decoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.util.DebugLogger

fun newImageLoader(
    context: PlatformContext,
    debug: Boolean,
): ImageLoader {
    return ImageLoader.Builder(context)
        .networkCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            MemoryCache.Builder()
                .strongReferencesEnabled(true)
                .maxSizePercent(context, percent = 0.55)
                .build()
        }
        .diskCache {
            newDiskCache()
        }
        .components {
            getPlatformComponents().forEach {
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

internal expect fun newDiskCache(): DiskCache?
expect fun getPlatformComponents(): List<Decoder.Factory>
