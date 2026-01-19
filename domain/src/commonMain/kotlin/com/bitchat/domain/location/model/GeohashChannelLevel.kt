package com.bitchat.domain.location.model

enum class GeohashChannelLevel(val precision: Int, val displayName: String) {
    BUILDING(8, "Building"),
    BLOCK(7, "Block"),
    NEIGHBORHOOD(6, "Neighborhood"),
    CITY(5, "City"),
    PROVINCE(4, "Province"),
    REGION(2, "REGION");
}
