package com.bitchat.lora.bitchat.di

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun isBeaconProbeEnabled(): Boolean {
    val value = getenv("BITCHAT_LORA_PROBE")?.toKString()?.trim()?.lowercase() ?: return false
    return value == "1" || value == "true" || value == "yes"
}
