package com.bitchat.domain.location.model

data class ObservedFix(
    val point: GeoPoint,
    val source: LocationFixInfo.Source,
    val ageMillis: Long,
)
