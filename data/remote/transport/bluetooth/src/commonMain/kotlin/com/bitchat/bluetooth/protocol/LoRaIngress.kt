package com.bitchat.bluetooth.protocol

/** The name of the radio as a link: no Bluetooth address can equal it. */
const val LORA_LINK = "lora"

/** Why a packet from the radio was not let in. */
enum class LoRaRejection { TOO_LARGE, NOT_A_PACKET, NOT_FOR_THIS_DEVICE, FROM_THIS_DEVICE, WRONG_TYPE, WRONG_TTL }

sealed interface LoRaIngressResult {
    class Accepted(val packet: BitchatPacket, val senderPeerID: String) : LoRaIngressResult
    class Rejected(val reason: LoRaRejection) : LoRaIngressResult
}

/**
 * Decides whether [data], received from the radio, enters the mesh pipeline. The radio is one
 * shared link that anyone in range can write to, so only complete, addressed Noise packets can
 * create mesh state or cause a reply.
 */
fun admitFromLoRa(data: ByteArray, myPeerID: String): LoRaIngressResult {
    if (data.size !in 1..MAX_LORA_PACKET_BYTES) return LoRaIngressResult.Rejected(LoRaRejection.TOO_LARGE)

    val packet = BinaryProtocol.decodeExact(data) ?: return LoRaIngressResult.Rejected(LoRaRejection.NOT_A_PACKET)
    val recipient = packet.recipientID?.toLowerHex()
    val localPeerID = myPeerID.lowercase()
    if (recipient != localPeerID) return LoRaIngressResult.Rejected(LoRaRejection.NOT_FOR_THIS_DEVICE)

    val senderPeerID = packet.senderID.toLowerHex()
    if (senderPeerID == localPeerID) return LoRaIngressResult.Rejected(LoRaRejection.FROM_THIS_DEVICE)

    if (packet.type != MessageType.NOISE_HANDSHAKE.value && packet.type != MessageType.NOISE_ENCRYPTED.value) {
        return LoRaIngressResult.Rejected(LoRaRejection.WRONG_TYPE)
    }
    if (packet.ttl != 0.toUByte()) return LoRaIngressResult.Rejected(LoRaRejection.WRONG_TTL)

    return LoRaIngressResult.Accepted(packet, senderPeerID)
}

private fun ByteArray.toLowerHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}
