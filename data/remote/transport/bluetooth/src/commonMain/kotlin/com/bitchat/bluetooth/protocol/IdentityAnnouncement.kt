package com.bitchat.bluetooth.protocol

data class IdentityAnnouncement(
    val nickname: String,
    val noisePublicKey: ByteArray,
    val signingPublicKey: ByteArray,
) {
    private enum class TLVType(val value: UByte) {
        NICKNAME(0x01u),
        NOISE_PUBLIC_KEY(0x02u),
        SIGNING_PUBLIC_KEY(0x03u);

        companion object {
            fun fromValue(value: UByte): TLVType? {
                return entries.find { it.value == value }
            }
        }
    }

    fun encode(): ByteArray? {
        val nicknameData = nickname.encodeToByteArray()

        if (nicknameData.size > 255 || noisePublicKey.size > 255 || signingPublicKey.size > 255) {
            return null
        }

        val result = mutableListOf<Byte>()

        result.add(TLVType.NICKNAME.value.toByte())
        result.add(nicknameData.size.toByte())
        result.addAll(nicknameData.toList())

        result.add(TLVType.NOISE_PUBLIC_KEY.value.toByte())
        result.add(noisePublicKey.size.toByte())
        result.addAll(noisePublicKey.toList())

        result.add(TLVType.SIGNING_PUBLIC_KEY.value.toByte())
        result.add(signingPublicKey.size.toByte())
        result.addAll(signingPublicKey.toList())

        return result.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): IdentityAnnouncement? {
            val tlvResult = decodeTLV(data)
            if (tlvResult != null) {
                return tlvResult
            }

            return try {
                val nickname = data.decodeToString()
                IdentityAnnouncement(
                    nickname = nickname,
                    noisePublicKey = ByteArray(32), // Placeholder
                    signingPublicKey = ByteArray(32) // Placeholder
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun decodeTLV(data: ByteArray): IdentityAnnouncement? {
            var offset = 0
            var nickname: String? = null
            var noisePublicKey: ByteArray? = null
            var signingPublicKey: ByteArray? = null

            while (offset + 2 <= data.size) {
                // Read TLV type
                val typeValue = data[offset].toUByte()
                val type = TLVType.fromValue(typeValue)
                offset += 1

                // Read TLV length
                val length = data[offset].toUByte().toInt()
                offset += 1

                // Check bounds
                if (offset + length > data.size) return null

                // Read TLV value
                val value = data.sliceArray(offset until offset + length)
                offset += length

                // Process known TLV types, skip unknown ones for forward compatibility
                when (type) {
                    TLVType.NICKNAME -> {
                        nickname = value.decodeToString()
                    }

                    TLVType.NOISE_PUBLIC_KEY -> {
                        noisePublicKey = value
                    }

                    TLVType.SIGNING_PUBLIC_KEY -> {
                        signingPublicKey = value
                    }

                    null -> {
                        // Unknown TLV; skip (tolerant decoder)
                        continue
                    }
                }
            }

            // All three fields are required for valid TLV announce
            return if (nickname != null && noisePublicKey != null && signingPublicKey != null) {
                IdentityAnnouncement(nickname, noisePublicKey, signingPublicKey)
            } else {
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as IdentityAnnouncement

        if (nickname != other.nickname) return false
        if (!noisePublicKey.contentEquals(other.noisePublicKey)) return false
        if (!signingPublicKey.contentEquals(other.signingPublicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = nickname.hashCode()
        result = 31 * result + noisePublicKey.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        return result
    }
}
