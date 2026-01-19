package com.bitchat.domain.chat.model

import kotlin.time.Instant

sealed class DeliveryStatus {
    object Sending : DeliveryStatus()
    object Sent : DeliveryStatus()
    data class Delivered(val to: String, val at: Instant) : DeliveryStatus()
    data class Read(val by: String, val at: Instant) : DeliveryStatus()
    data class Failed(val reason: String) : DeliveryStatus()
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus()
}
