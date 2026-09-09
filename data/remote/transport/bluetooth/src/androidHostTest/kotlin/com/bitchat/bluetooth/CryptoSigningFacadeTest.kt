package com.bitchat.bluetooth

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.protocol.BitchatPacket
import com.bitchat.bluetooth.protocol.MessageType
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Hex
import org.junit.Test
import java.security.SecureRandom
import kotlin.test.assertTrue

class CryptoSigningFacadeTest {
    @Test
    fun signPacket_usesEd25519AndLegacySigningData() {
        val keyPairGenerator = Ed25519KeyPairGenerator()
        keyPairGenerator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        val publicKey = keyPair.public as Ed25519PublicKeyParameters

        val privateKeyHex = Hex.toHexString(privateKey.encoded)
        val signingFacade = CryptoSigningFacade(privateKeyHex)

        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 3u,
            senderID = "0011223344556677",
            payload = byteArrayOf(0x01, 0x02, 0x03)
        )

        val dataToSign = packet.toBinaryDataForSigning() ?: error("Failed to build signing data")
        val signature = signingFacade.signPacket(dataToSign)

        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(dataToSign, 0, dataToSign.size)

        assertTrue(
            verifier.verifySignature(signature),
            "Expected Ed25519 signature over legacy signing data"
        )
        assertTrue(
            signingFacade.getSigningPublicKey().contentEquals(publicKey.encoded),
            "Expected Ed25519 public key to be used for announcements"
        )
    }
}
