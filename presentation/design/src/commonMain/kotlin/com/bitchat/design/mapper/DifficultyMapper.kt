package com.bitchat.design.mapper

fun Int.toMiningTimeEstimate(): String {
    return when {
        this == 0 -> "instant"
        this <= 8 -> "< 1s"
        this <= 12 -> "~1-2s"
        this <= 16 -> "~5-10s"
        this <= 20 -> "~30s-1m"
        this <= 24 -> "~5-10m"
        else -> "> 30m"
    }
}

fun Int.toPowDifficultyDescription(): String {
    return when {
        this == 0 -> "no proof of work required"
        this <= 8 -> "very low - minimal spam protection"
        this <= 12 -> "low - basic spam protection"
        this <= 16 -> "medium - good spam protection"
        this <= 20 -> "high - strong spam protection"
        this <= 24 -> "very high - may cause delays"
        else -> "extreme - significant computation required"
    }
}
