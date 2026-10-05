@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.embedded

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.flock
import platform.posix.open

internal actual fun lockWithoutWaiting(descriptor: Int): Int = flock(descriptor, LOCK_EX or LOCK_NB)

// No openat here, and nothing but the tests runs on this target: the path does.
internal actual fun openInside(folder: Int, path: String, name: String, flags: Int, mode: Int): Int =
    open(path, flags, mode)
