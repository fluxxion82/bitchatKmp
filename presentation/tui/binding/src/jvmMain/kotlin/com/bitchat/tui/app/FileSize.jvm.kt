package com.bitchat.tui.app

import java.io.File

actual fun fileSize(path: String): Long? = File(path).takeIf { it.isFile }?.length()
