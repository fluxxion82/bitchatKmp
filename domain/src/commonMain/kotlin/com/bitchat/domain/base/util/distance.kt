package com.bitchat.domain.base.util

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

fun Double.toRadians(): Double = this * PI / 180

fun haversineDistance(
    centerLat: Double,
    centerLon: Double,
    targetLat: Double,
    targetLon: Double,
): Double {
    val earthRadius = 6371.0

    val dLat = (targetLat - centerLat).toRadians()
    val dLon = (targetLon - centerLon).toRadians()

    val a = sin(dLat / 2).pow(2.0) +
            cos(centerLat.toRadians()) * cos(targetLat.toRadians()) *
            sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return earthRadius * c
}
