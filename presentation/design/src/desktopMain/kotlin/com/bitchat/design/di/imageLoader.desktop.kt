package com.bitchat.design.di

import coil3.decode.Decoder
import coil3.disk.DiskCache
import okio.FileSystem

internal actual fun newDiskCache(): DiskCache? {
    return DiskCache.Builder()
        .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
        .maxSizeBytes(512L * 1024 * 1024) // 512MB
        .build()
}

actual fun getPlatformComponents(): List<Decoder.Factory> = listOf()
