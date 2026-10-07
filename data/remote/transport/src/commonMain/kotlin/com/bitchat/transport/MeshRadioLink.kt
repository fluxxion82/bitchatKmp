package com.bitchat.transport

/** What a mesh packet handed to the radio is. The radio side decides whose time on air pays for it. */
sealed interface RadioPurpose {
    /** Message 1 of a handshake; [byUser] when this device's user asked for the session. */
    data class HandshakeOpening(val byUser: Boolean) : RadioPurpose
    /** Message 2: the answer to an opening. Nothing about whoever opened is proven. */
    data object HandshakeAnswer : RadioPurpose
    /** Message 3: the peer's key has been validated. */
    data object HandshakeFinal : RadioPurpose
    data object PrivateMessage : RadioPurpose
}

enum class RadioSendResult { SENT, NO_TIME_ON_AIR, FAILED }

/** The LoRa radio as a second way to one peer of the mesh. */
interface MeshRadioLink {
    /** Whether a device with this mesh id has been heard on the radio lately (unauthenticated). */
    fun hears(peerID: String): Boolean
    /** Sends one whole packet (already in its radio form) to [peerID]; returns when it has left the air or was refused. */
    suspend fun send(packet: ByteArray, peerID: String, purpose: RadioPurpose): RadioSendResult
}
