package com.bitchat.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Secret bytes are bytes the platform CSPRNG produced - not merely bytes of the right shape.
 *
 * Length and uniqueness checks pass just as well with `kotlin.random.Random`, which is how two
 * reviews swapped it in without a test noticing. These install a recording generator as the
 * platform CSPRNG (see [withInstalledCsprng]) and require each secret to be one of its draws.
 *
 * They depend on `Cryptography` resolving the platform CSPRNG on each use: a generator cached
 * before the test installs its own is invisible here, and fails these tests by design.
 */
class CsprngProvenanceTest {

    @Test
    fun `secureRandomBytes hands back exactly what the platform CSPRNG produced`() {
        withInstalledCsprng { csprng ->
            val bytes = Cryptography.secureRandomBytes(32)

            assertEquals(32, bytes.size)
            assertTrue(csprng.draws.any { it.contentEquals(bytes) }, notFromCsprng("secureRandomBytes"))
        }
    }

    @Test
    fun `a secp256k1 private key is bytes the platform CSPRNG produced`() {
        withInstalledCsprng { csprng ->
            val (privateKeyHex, _) = Cryptography.generateKeyPair()

            assertTrue(csprng.draws.any { it.toHex() == privateKeyHex }, notFromCsprng("the secp256k1 private key"))
        }
    }

    @Test
    fun `an Ed25519 private key is bytes the platform CSPRNG produced`() {
        withInstalledCsprng { csprng ->
            val (privateKeyHex, _) = Cryptography.generateEd25519KeyPair()

            assertTrue(csprng.draws.any { it.toHex() == privateKeyHex }, notFromCsprng("the Ed25519 private key"))
        }
    }

    @Test
    fun `an AES-GCM nonce is bytes the platform CSPRNG produced`() {
        // A repeated GCM nonce under one key gives away the authentication key; a predictable
        // one is the first step to repeating it.
        withInstalledCsprng { csprng ->
            val nonce = Cryptography.encryptAESGCM("x", ByteArray(32) { it.toByte() }).copyOf(12)

            assertTrue(csprng.draws.any { it.contentEquals(nonce) }, notFromCsprng("the AES-GCM nonce"))
        }
    }

    @Test
    fun `Schnorr signing mixes in bytes from the platform CSPRNG`() {
        withInstalledCsprng { csprng ->
            Cryptography.schnorrSign(ByteArray(32), "00".repeat(31) + "03")

            assertTrue(csprng.draws.any { it.size == 32 }, "schnorrSign drew no auxiliary randomness from the platform CSPRNG")
        }
    }

    @Test
    fun `a failing platform CSPRNG fails key generation instead of falling back`() {
        withInstalledCsprng(fill = { throw IllegalStateException("entropy source unavailable") }) {
            assertFailsWith<IllegalStateException> { Cryptography.secureRandomBytes(32) }
            assertFailsWith<IllegalStateException> { Cryptography.generateKeyPair() }
            assertFailsWith<IllegalStateException> { Cryptography.generateEd25519KeyPair() }
        }
    }

    private fun notFromCsprng(what: String) =
        "$what is not among the bytes the platform CSPRNG produced, so it came from some other " +
            "generator (or from one resolved before this test installed its own: Cryptography " +
            "must look the platform CSPRNG up on each use, see platformRandom)"

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
