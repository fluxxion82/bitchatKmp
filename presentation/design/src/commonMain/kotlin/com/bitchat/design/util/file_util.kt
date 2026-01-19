package com.bitchat.design.util

import kotlin.math.pow
import kotlin.math.round

fun formatFileSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024.0
        unitIndex++
    }
    val sizeString = size.asFixed(1)
    return "$sizeString ${units[unitIndex]}"
}

fun Double.asFixed(decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = round(this * factor) / factor
    return rounded.toString()
}

fun Double.fixed(decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = round(this * factor) / factor
    return buildString {
        append(rounded)
        if (decimals > 0 && !contains(".")) {
            append(".")
            repeat(decimals) { append("0") }
        }
    }
}
