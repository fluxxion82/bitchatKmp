package com.bitchat.design.di

/**
 * Platform-specific image loader creation.
 * On platforms with Coil support, returns a configured Coil ImageLoader.
 * On linuxArm64, returns null as image loading is not supported.
 */
expect fun newImageLoader(
    context: Any,
    debug: Boolean,
): Any?

/**
 * Platform-specific component factories for image decoders.
 */
expect fun getPlatformComponents(): List<Any>
