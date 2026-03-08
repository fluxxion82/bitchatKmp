package com.bitchat.design.di

/**
 * linuxArm64 stub - image loading not supported on embedded device.
 */
actual fun newImageLoader(
    context: Any,
    debug: Boolean,
): Any? = null

actual fun getPlatformComponents(): List<Any> = emptyList()
