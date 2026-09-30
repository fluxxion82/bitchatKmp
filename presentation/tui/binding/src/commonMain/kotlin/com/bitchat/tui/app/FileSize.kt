package com.bitchat.tui.app

/** The byte size of a regular file at [path], or null when it is absent or not a file. */
expect fun fileSize(path: String): Long?
