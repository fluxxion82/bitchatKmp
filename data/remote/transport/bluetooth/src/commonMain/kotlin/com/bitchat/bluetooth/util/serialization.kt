package com.bitchat.bluetooth.util

import com.bitchat.api.dto.packet.IdentityAnnouncement

fun IdentityAnnouncement.encode(): ByteArray? {
    val nicknameData = nickname.encodeToByteArray()

    if (nicknameData.size > 255 || noisePublicKeyHex.encodeToByteArray().size > 255 || signingPublicKeyHex.encodeToByteArray().size > 255) {
        return null
    }

    val result = mutableListOf<Byte>()

    // TLV for nickname
    result.add(IdentityAnnouncement.TLVType.NICKNAME.value.toByte())
    result.add(nicknameData.size.toByte())
    result.addAll(nicknameData.toList())

    // TLV for noise public key
    result.add(IdentityAnnouncement.TLVType.NOISE_PUBLIC_KEY.value.toByte())
    result.add(noisePublicKeyHex.encodeToByteArray().size.toByte())
    result.addAll(noisePublicKeyHex.encodeToByteArray().toList())

    // TLV for signing public key
    result.add(IdentityAnnouncement.TLVType.SIGNING_PUBLIC_KEY.value.toByte())
    result.add(signingPublicKeyHex.encodeToByteArray().size.toByte())
    result.addAll(signingPublicKeyHex.encodeToByteArray().toList())

    return result.toByteArray()
}
