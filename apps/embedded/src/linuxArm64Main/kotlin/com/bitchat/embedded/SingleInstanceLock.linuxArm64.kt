@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.embedded

import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.flock
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.openat

internal actual fun lockWithoutWaiting(descriptor: Int): Int = flock(descriptor, LOCK_EX or LOCK_NB)

internal actual fun openInside(folder: Int, path: String, name: String, flags: Int, mode: Int): Int =
    openat(folder, name, flags, mode)
