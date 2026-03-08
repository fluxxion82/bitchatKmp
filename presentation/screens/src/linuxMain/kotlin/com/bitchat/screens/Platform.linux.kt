package com.bitchat.screens

/**
 * Linux returns DESKTOP platform for now.
 * Could add a LINUX/EMBEDDED variant to the enum if needed.
 */
actual fun getPlatform(): Platform = Platform.DESKTOP
