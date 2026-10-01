package com.bitchat.design.media

/**
 * Returns a Coil-safe local image model, or null when [raw] could cause a remote fetch.
 */
fun localImageModel(raw: String): String? {
    val path = raw.trim()
    return when {
        path.startsWith("/") -> "file://$path"
        path.startsWith("file:///") -> path
        else -> null
    }
}
