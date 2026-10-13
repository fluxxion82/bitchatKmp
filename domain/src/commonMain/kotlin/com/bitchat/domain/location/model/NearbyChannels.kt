package com.bitchat.domain.location.model

/** The nearby channels and where the fix behind them came from, read together. */
data class NearbyChannels(val channels: List<GeohashChannel>, val fix: LocationFixInfo?)
