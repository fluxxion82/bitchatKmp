package com.bitchat.domain.location.model

@ConsistentCopyVisibility
data class SavedPlace private constructor(val geohash: String) {
    companion object {
        fun of(input: String): SavedPlace? {
            val geohash = input.trim().removePrefix("#").lowercase()
            return geohash.takeIf {
                it.length in 2..12 && it.all { character -> character in BASE32 }
            }?.let(::SavedPlace)
        }

        private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    }
}
