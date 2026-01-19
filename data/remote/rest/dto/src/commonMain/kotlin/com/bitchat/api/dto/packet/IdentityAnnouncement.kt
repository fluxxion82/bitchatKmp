package com.bitchat.api.dto.packet

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKeyHex: String,
    val signingPublicKeyHex: String,
) {
    enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u);
    }
}
