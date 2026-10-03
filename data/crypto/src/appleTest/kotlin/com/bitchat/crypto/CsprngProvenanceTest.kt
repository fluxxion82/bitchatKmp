@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import libsodium.randombytes_implementation_name
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Secret bytes are bytes libsodium's CSPRNG produced - not merely bytes of the right shape.
 *
 * The Apple counterpart of the JVM test of the same name. Length and uniqueness checks pass just as
 * well with `kotlin.random.Random`. These install a deterministic generator as libsodium's (see
 * [withInstalledCsprng]) and require each secret to be exactly the bytes it handed out.
 *
 * Linux makes the same `randombytes_buf` calls, but linuxArm64 test binaries cannot run on the
 * hosts that build them; `WeakRandomnessGuardTest` covers its sources.
 */
class CsprngProvenanceTest {

    @Test
    fun `secureRandomBytes hands back exactly what libsodium's generator produced`() {
        withInstalledCsprng { csprng ->
            val bytes = Cryptography.secureRandomBytes(32)

            assertContentEquals(InstalledCsprng.pattern(32), bytes, notFromCsprng("secureRandomBytes"))
            assertEquals(listOf(32), csprng.draws.map { it.size })
        }
    }

    @Test
    fun `a secp256k1 private key is bytes libsodium's generator produced`() {
        withInstalledCsprng { csprng ->
            val (privateKeyHex, _) = Cryptography.generateKeyPair()

            assertEquals(InstalledCsprng.pattern(32).toHex(), privateKeyHex, notFromCsprng("the secp256k1 private key"))
            assertEquals(listOf(32), csprng.draws.map { it.size })
        }
    }

    @Test
    fun `an Ed25519 private key is bytes libsodium's generator produced`() {
        withInstalledCsprng { csprng ->
            val (privateKeyHex, _) = Cryptography.generateEd25519KeyPair()

            assertEquals(InstalledCsprng.pattern(32).toHex(), privateKeyHex, notFromCsprng("the Ed25519 private key"))
            assertEquals(listOf(32), csprng.draws.map { it.size })
        }
    }

    @Test
    fun `an AES-GCM nonce is bytes libsodium's generator produced`() {
        // A repeated GCM nonce under one key gives away the authentication key; a predictable
        // one is the first step to repeating it.
        withInstalledCsprng { csprng ->
            val nonce = Cryptography.encryptAESGCM("x", ByteArray(32) { it.toByte() }).copyOf(12)

            assertContentEquals(InstalledCsprng.pattern(12), nonce, notFromCsprng("the AES-GCM nonce"))
            assertEquals(listOf(12), csprng.draws.map { it.size })
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `an XChaCha20-Poly1305 envelope nonce is bytes libsodium's generator produced`() {
        val recipientPublicKeyHex = Cryptography.derivePublicKey("00".repeat(31) + "02")
        withInstalledCsprng { csprng ->
            val envelope = Cryptography.sealBitchatEnvelope("x", recipientPublicKeyHex, "00".repeat(31) + "01")
            val nonce = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
                .decode(envelope.removePrefix("v2:"))
                .copyOf(24)

            assertContentEquals(InstalledCsprng.pattern(24), nonce, notFromCsprng("the XChaCha20-Poly1305 nonce"))
            assertEquals(listOf(24), csprng.draws.map { it.size })
        }
    }

    @Test
    fun `Schnorr signing mixes in bytes from libsodium's generator`() {
        // A draw that is made and then dropped would still be recorded, so the draw alone proves
        // nothing. BIP340 fixes the signature once the key, message and auxiliary bytes are fixed:
        // this is the one for the key and message of the BIP's test vector 0 with the pattern as
        // the auxiliary bytes, computed with a port of the BIP's reference.py.
        val signedWithThePattern =
            "e4fa35db6d095723c0e5db8e861d8384dc4721d4cbe7462a145a22eaa3b232b1" +
                "48747a05beece377b5aefb3b40b284dadbe58452b4348b2c1f59ea5c6048dd66"
        withInstalledCsprng { csprng ->
            val signatureHex = Cryptography.schnorrSign(ByteArray(32), "00".repeat(31) + "03")

            assertEquals(
                listOf(32),
                csprng.draws.map { it.size },
                "schnorrSign drew no auxiliary randomness from libsodium's generator",
            )
            assertEquals(
                signedWithThePattern,
                signatureHex.lowercase(),
                "schnorrSign did not sign with the auxiliary bytes libsodium's generator produced",
            )
        }
    }

    @Test
    fun `libsodium's own generator is back once the test generator is removed`() {
        // Every other test in this binary must draw real randomness again, even after a failure.
        val before = randombytes_implementation_name()?.toKString()

        val failure = assertFailsWith<IllegalStateException> { withInstalledCsprng { error("the test body failed") } }

        assertEquals("the test body failed", failure.message)
        assertEquals(before, randombytes_implementation_name()?.toKString())
        assertFalse(Cryptography.secureRandomBytes(32).contentEquals(InstalledCsprng.pattern(32)))
    }

    private fun notFromCsprng(what: String) =
        "$what is not the bytes libsodium's generator produced, so it came from some other generator"

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
