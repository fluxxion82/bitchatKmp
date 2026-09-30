@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.bitchat.tui.app

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.stat

actual fun fileSize(path: String): Long? = memScoped {
    val info = alloc<stat>()
    if (stat(path, info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFREG) info.st_size else null
}
