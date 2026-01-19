package com.bitchat.bluetooth.model

import kotlinx.datetime.Instant

data class PeerInfo(
    val id: String,
    var nickname: String,
    var isConnected: Boolean,
    var isDirectConnection: Boolean,
    var noisePublicKey: ByteArray?,
    var signingPublicKey: ByteArray?,      // Ed25519 public key for verification
    var isVerifiedNickname: Boolean,       // Verification status flag
    var lastSeen: Instant                   // Using kotlinx.datetime instead of Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as PeerInfo

        if (id != other.id) return false
        if (nickname != other.nickname) return false
        if (isConnected != other.isConnected) return false
        if (isDirectConnection != other.isDirectConnection) return false
        if (noisePublicKey != null) {
            if (other.noisePublicKey == null) return false
            if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        } else if (other.noisePublicKey != null) return false
        if (signingPublicKey != null) {
            if (other.signingPublicKey == null) return false
            if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false
        } else if (other.signingPublicKey != null) return false
        if (isVerifiedNickname != other.isVerifiedNickname) return false
        if (lastSeen != other.lastSeen) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + nickname.hashCode()
        result = 31 * result + isConnected.hashCode()
        result = 31 * result + isDirectConnection.hashCode()
        result = 31 * result + (noisePublicKey?.contentHashCode() ?: 0)
        result = 31 * result + (signingPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + isVerifiedNickname.hashCode()
        result = 31 * result + lastSeen.hashCode()
        return result
    }
}
