package com.bitchat.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64.Default.decode
import kotlin.io.encoding.Base64.Default.encode

actual object Cryptography {
    private val secureRandom = SecureRandom()
    private val secp256k1Curve = CustomNamedCurves.getByName("secp256k1")
    private val secp256k1Params = ECDomainParameters(
        secp256k1Curve.curve,
        secp256k1Curve.g,
        secp256k1Curve.n,
        secp256k1Curve.h
    )

    actual fun generateKeyPair(): Pair<String, String> {
        val generator = ECKeyPairGenerator()
        val keyGenParams = ECKeyGenerationParameters(secp256k1Params, secureRandom)
        generator.init(keyGenParams)

        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private as ECPrivateKeyParameters
        val publicKey = keyPair.public as ECPublicKeyParameters

        val privateKeyBigInt = privateKey.d
        val privateKeyBytes = privateKeyBigInt.toByteArray()

        val privateKeyPadded = ByteArray(32)
        if (privateKeyBytes.size <= 32) {
            val srcStart = maxOf(0, privateKeyBytes.size - 32)
            val destStart = maxOf(0, 32 - privateKeyBytes.size)
            val length = minOf(privateKeyBytes.size, 32)
            System.arraycopy(privateKeyBytes, srcStart, privateKeyPadded, destStart, length)
        } else {
            System.arraycopy(privateKeyBytes, privateKeyBytes.size - 32, privateKeyPadded, 0, 32)
        }

        val publicKeyPoint = publicKey.q.normalize()
        val xCoord = publicKeyPoint.xCoord.encoded

        return Pair(
            privateKeyPadded.toHexString(),
            xCoord.toHexString()
        )
    }

    actual fun derivePublicKey(privateKeyHex: String): String {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val privateKeyBigInt = BigInteger(1, privateKeyBytes)

        val publicKeyPoint = secp256k1Params.g.multiply(privateKeyBigInt).normalize()
        val xCoord = publicKeyPoint.xCoord.encoded

        return xCoord.toHexString()
    }

    actual fun generateEd25519KeyPair(): Pair<String, String> {
        val generator = Ed25519KeyPairGenerator()
        val keyGenParams = Ed25519KeyGenerationParameters(secureRandom)
        generator.init(keyGenParams)

        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        val publicKey = keyPair.public as Ed25519PublicKeyParameters

        return Pair(
            privateKey.encoded.toHexString(),
            publicKey.encoded.toHexString()
        )
    }

    actual fun deriveEd25519PublicKey(privateKeyHex: String): String {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        require(privateKeyBytes.size == 32) { "Ed25519 private key must be 32 bytes" }
        val privateKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
        return privateKey.generatePublicKey().encoded.toHexString()
    }

    actual fun ed25519Sign(message: ByteArray, privateKeyHex: String): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        require(privateKeyBytes.size == 32) { "Ed25519 private key must be 32 bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    actual fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKeyHex: String): Boolean {
        return try {
            val publicKeyBytes = publicKeyHex.hexToByteArray()
            require(publicKeyBytes.size == 32) { "Ed25519 public key must be 32 bytes" }
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKeyBytes, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    actual fun deriveNIP44Key(sharedSecret: ByteArray): ByteArray {
        val zeroSalt = ByteArray(0)
        val prk = hkdfExtract(zeroSalt, sharedSecret)
        return hkdfExpand(prk, info = "nip44-v2".toByteArray(Charsets.UTF_8), length = 32)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(salt))
        hmac.update(ikm, 0, ikm.size)
        val prk = ByteArray(hmac.macSize)
        hmac.doFinal(prk, 0)
        return prk
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray?, length: Int): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(prk))
        if (info != null && info.isNotEmpty()) {
            hmac.update(info, 0, info.size)
        }
        hmac.update(byteArrayOf(0x01), 0, 1)
        val t = ByteArray(hmac.macSize)
        hmac.doFinal(t, 0)
        return t.copyOf(length)
    }

    private fun recoverPublicKeyPoint(xOnlyBytes: ByteArray): ECPoint {
        require(xOnlyBytes.size == 32) { "X-only public key must be 32 bytes" }
        try {
            val compressedBytes = ByteArray(33)
            compressedBytes[0] = 0x02
            System.arraycopy(xOnlyBytes, 0, compressedBytes, 1, 32)
            return secp256k1Curve.curve.decodePoint(compressedBytes)
        } catch (e: Exception) {
            val compressedBytes = ByteArray(33)
            compressedBytes[0] = 0x03
            System.arraycopy(xOnlyBytes, 0, compressedBytes, 1, 32)
            return secp256k1Curve.curve.decodePoint(compressedBytes)
        }
    }

    private fun recoverPublicKeyPointWithParity(xOnlyBytes: ByteArray, preferOddY: Boolean): ECPoint {
        require(xOnlyBytes.size == 32) { "X-only public key must be 32 bytes" }
        val prefix: Byte = if (preferOddY) 0x03 else 0x02
        val compressedBytes = ByteArray(33)
        compressedBytes[0] = prefix
        System.arraycopy(xOnlyBytes, 0, compressedBytes, 1, 32)
        return secp256k1Curve.curve.decodePoint(compressedBytes)
    }

    private fun computeSharedPointWithParity(privateKeyHex: String, publicKeyHex: String, preferOddY: Boolean): ECPoint {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        val publicKeyBytes = publicKeyHex.hexToByteArray()
        val privateKeyBigInt = BigInteger(1, privateKeyBytes)
        val point = recoverPublicKeyPointWithParity(publicKeyBytes, preferOddY)
        return point.multiply(privateKeyBigInt).normalize()
    }

    private fun compressedPoint(point: ECPoint): ByteArray {
        val normalized = point.normalize()
        val x = normalized.xCoord.encoded
        val prefix: Byte = if (hasEvenY(normalized)) 0x02 else 0x03
        val out = ByteArray(33)
        out[0] = prefix
        System.arraycopy(x, 0, out, 1, 32)
        return out
    }

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val tagBytes = tag.toByteArray(Charsets.UTF_8)
        val tagHash = MessageDigest.getInstance("SHA-256").digest(tagBytes)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(data)
        return digest.digest()
    }

    private fun hasEvenY(point: ECPoint): Boolean {
        val yCoord = point.normalize().yCoord.encoded
        return (yCoord[yCoord.size - 1].toInt() and 1) == 0
    }

    private fun liftX(xBytes: ByteArray): ECPoint? {
        return try {
            val point = recoverPublicKeyPoint(xBytes)
            val normalizedPoint = point.normalize()

            if (hasEvenY(normalizedPoint)) {
                normalizedPoint
            } else {
                normalizedPoint.negate()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun base64UrlNoPad(data: ByteArray): String {
        val b64 = encode(data)
        return b64.replace('+', '-').replace('/', '_').replace("=", "")
    }

    private fun base64UrlDecode(s: String): ByteArray? {
        var str = s.replace('-', '+').replace('_', '/')
        val pad = (4 - (str.length % 4)) % 4
        if (pad > 0) str += "=".repeat(pad)
        return try {
            decode(str)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    actual fun encryptNIP44(
        plaintext: String,
        recipientPublicKeyHex: String,
        senderPrivateKeyHex: String
    ): String {
        try {
            val sharedPoint = computeSharedPointWithParity(senderPrivateKeyHex, recipientPublicKeyHex, preferOddY = false)
            val secretMaterial = compressedPoint(sharedPoint)
            val encryptionKey = deriveNIP44Key(secretMaterial)
            val aead = XChaCha20Poly1305(encryptionKey)
            val combined = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null) // nonce||ct||tag
            val b64 = base64UrlNoPad(combined)
            return "v2:$b64"
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 encryption failed: ${e.message}", e)
        }
    }

    actual fun decryptNIP44(
        ciphertext: String,
        senderPublicKeyHex: String,
        recipientPrivateKeyHex: String
    ): String {
        try {
            require(ciphertext.startsWith("v2:")) { "Invalid NIP-44 version prefix" }
            val encoded = ciphertext.substring(3)
            val encryptedData = base64UrlDecode(encoded)
                ?: throw IllegalArgumentException("Invalid base64url payload")

            var lastError: Exception? = null
            for (preferOdd in listOf(false, true)) {
                try {
                    val point = computeSharedPointWithParity(recipientPrivateKeyHex, senderPublicKeyHex, preferOddY = preferOdd)
                    val secretMaterial = compressedPoint(point)
                    val key = deriveNIP44Key(secretMaterial)
                    val aead = XChaCha20Poly1305(key)
                    val pt = aead.decrypt(encryptedData, null) // expects nonce||ct||tag
                    return String(pt, Charsets.UTF_8)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: RuntimeException("NIP-44 v2 decryption failed")
        } catch (e: Exception) {
            throw RuntimeException("NIP-44 v2 decryption failed: ${e.message}", e)
        }
    }

    actual fun randomizeTimestampUpToPast(maxPastSeconds: Int): Int {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val offset = if (maxPastSeconds > 0) secureRandom.nextInt(maxPastSeconds + 1) else 0
        return now - offset
    }

    actual fun isValidPrivateKey(privateKeyHex: String): Boolean {
        return try {
            val privateKeyBytes = privateKeyHex.hexToByteArray()
            if (privateKeyBytes.size != 32) return false

            val privateKeyBigInt = BigInteger(1, privateKeyBytes)

            privateKeyBigInt > BigInteger.ZERO && privateKeyBigInt < secp256k1Params.n
        } catch (e: Exception) {
            false
        }
    }

    actual fun isValidPublicKey(publicKeyHex: String): Boolean {
        return try {
            val publicKeyBytes = publicKeyHex.hexToByteArray()
            if (publicKeyBytes.size != 32) return false

            recoverPublicKeyPoint(publicKeyBytes)
            true
        } catch (e: Exception) {
            false
        }
    }

    actual fun getDigestHash(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun generateNonce(privateKey: BigInteger, messageHash: ByteArray, publicKeyBytes: ByteArray): BigInteger {
        val random = ByteArray(32)
        secureRandom.nextBytes(random)

        val privateKeyBytes = privateKey.toByteArray()
        val nonceInput = ByteArray(privateKeyBytes.size + messageHash.size + publicKeyBytes.size + random.size)
        var offset = 0

        System.arraycopy(privateKeyBytes, 0, nonceInput, offset, privateKeyBytes.size)
        offset += privateKeyBytes.size

        System.arraycopy(messageHash, 0, nonceInput, offset, messageHash.size)
        offset += messageHash.size

        System.arraycopy(publicKeyBytes, 0, nonceInput, offset, publicKeyBytes.size)
        offset += publicKeyBytes.size

        System.arraycopy(random, 0, nonceInput, offset, random.size)

        val nonceHash = getDigestHash(nonceInput)
        val nonce = BigInteger(1, nonceHash)

        return if (nonce >= secp256k1Params.n) {
            nonce.mod(secp256k1Params.n)
        } else {
            nonce
        }
    }

    actual fun schnorrSign(messageHash: ByteArray, privateKeyHex: String): String {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }

        val privateKeyBytes = privateKeyHex.hexToByteArray()
        require(privateKeyBytes.size == 32) { "Private key must be 32 bytes" }

        val d = BigInteger(1, privateKeyBytes)
        require(d > BigInteger.ZERO && d < secp256k1Params.n) { "Invalid private key" }

        val P = secp256k1Params.g.multiply(d).normalize()

        val (adjustedD, publicKeyBytes) = if (hasEvenY(P)) {
            Pair(d, P.xCoord.encoded)
        } else {
            Pair(secp256k1Params.n.subtract(d), P.xCoord.encoded)
        }

        val k = generateNonce(adjustedD, messageHash, publicKeyBytes)

        val R = secp256k1Params.g.multiply(k).normalize()

        val adjustedK = if (hasEvenY(R)) k else secp256k1Params.n.subtract(k)
        val r = R.xCoord.encoded

        val challengeData = ByteArray(96)
        System.arraycopy(r, 0, challengeData, 0, 32)
        System.arraycopy(publicKeyBytes, 0, challengeData, 32, 32)
        System.arraycopy(messageHash, 0, challengeData, 64, 32)

        val eBytes = taggedHash("BIP0340/challenge", challengeData)
        val e = BigInteger(1, eBytes).mod(secp256k1Params.n)

        val s = adjustedK.add(e.multiply(adjustedD)).mod(secp256k1Params.n)

        val rPadded = ByteArray(32)
        val sPadded = ByteArray(32)

        val rBytes = r
        val sBytes = s.toByteArray()

        System.arraycopy(rBytes, 0, rPadded, 0, minOf(32, rBytes.size))

        if (sBytes.size <= 32) {
            val srcStart = maxOf(0, sBytes.size - 32)
            val destStart = maxOf(0, 32 - sBytes.size)
            val length = minOf(sBytes.size, 32)
            System.arraycopy(sBytes, srcStart, sPadded, destStart, length)
        } else {
            System.arraycopy(sBytes, sBytes.size - 32, sPadded, 0, 32)
        }

        return (rPadded + sPadded).toHexString()
    }

    actual fun schnorrVerify(messageHash: ByteArray, signatureHex: String, publicKeyHex: String): Boolean {
        return try {
            require(messageHash.size == 32) { "Message hash must be 32 bytes" }

            val signatureBytes = signatureHex.hexToByteArray()
            require(signatureBytes.size == 64) { "Signature must be 64 bytes" }

            val publicKeyBytes = publicKeyHex.hexToByteArray()
            require(publicKeyBytes.size == 32) { "Public key must be 32 bytes" }

            val r = signatureBytes.copyOfRange(0, 32)
            val sBytes = signatureBytes.copyOfRange(32, 64)
            val s = BigInteger(1, sBytes)

            val rBigInt = BigInteger(1, r)
            if (rBigInt >= secp256k1Params.curve.field.characteristic) return false
            if (s >= secp256k1Params.n) return false

            val P = liftX(publicKeyBytes) ?: return false

            val challengeData = ByteArray(96)
            System.arraycopy(r, 0, challengeData, 0, 32)
            System.arraycopy(publicKeyBytes, 0, challengeData, 32, 32)
            System.arraycopy(messageHash, 0, challengeData, 64, 32)

            val eBytes = taggedHash("BIP0340/challenge", challengeData)
            val e = BigInteger(1, eBytes).mod(secp256k1Params.n)

            val sG = secp256k1Params.g.multiply(s)
            val eP = P.multiply(e)
            val R = sG.subtract(eP).normalize()

            if (!hasEvenY(R)) return false

            val computedR = R.xCoord.encoded
            return r.contentEquals(computedR)

        } catch (e: Exception) {
            false
        }
    }

    actual fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKeySpec)
        return mac.doFinal(message)
    }

    actual fun createAESSecretKey(password: String, salt: ByteArray): ByteArray {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(
            password.toCharArray(),
            salt,
            100000, // 100,000 iterations (same as iOS)
            256 // 256-bit key
        )
        val secretKey = factory.generateSecret(spec)
        return secretKey.encoded
    }

    actual fun encryptAESGCM(plaintext: String, key: ByteArray): ByteArray {
        require(key.size == 32) { "AES key must be 32 bytes (256-bit)" }

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")

        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plainBytes)

        return iv + ciphertext
    }

    actual fun decryptAESGCM(encryptedData: ByteArray, key: ByteArray): String? {
        return try {
            require(key.size == 32) { "AES key must be 32 bytes (256-bit)" }
            if (encryptedData.size < 12) return null // Minimum: 12 bytes IV

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")

            val iv = encryptedData.sliceArray(0 until 12)
            val ciphertext = encryptedData.sliceArray(12 until encryptedData.size)

            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    actual fun deriveX25519PublicKey(privateKeyHex: String): String {
        val privateKeyBytes = privateKeyHex.hexToByteArray()
        require(privateKeyBytes.size == 32) { "X25519 private key must be 32 bytes" }

        val clampedPrivate = privateKeyBytes.clone()
        clampedPrivate[0] = (clampedPrivate[0].toInt() and 248).toByte()
        clampedPrivate[31] = (clampedPrivate[31].toInt() and 127).toByte()
        clampedPrivate[31] = (clampedPrivate[31].toInt() or 64).toByte()

        val publicKeyBytes = ByteArray(32)
        org.bouncycastle.math.ec.rfc7748.X25519.scalarMultBase(clampedPrivate, 0, publicKeyBytes, 0)

        return publicKeyBytes.toHexString()
    }
}
