package com.bitchat.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every platform must derive the same key from the same password, and the same MAC from the same key.
 * These vectors come from Python's `hashlib`/`hmac`, so they are independent of our implementations.
 *
 * Why this exists: linuxArm64 used to derive channel keys with Argon2id while the JVM, Android and
 * Apple actuals used PBKDF2-HMAC-SHA256. `ChatRepo` keys password-protected channels with this, and a
 * failed decrypt is swallowed, so a channel written on a phone was silently unreadable on the Orange Pi
 * and the other way round. The long-key HMAC cases pin the related bug underneath it: the Linux HMAC
 * padded keys to 32 bytes and hashed anything longer, which is not RFC 2104 for keys of 33..64 bytes -
 * exactly the range a password can land in once it keys PBKDF2.
 */
class KeyDerivationParityTest {
    @Test
    fun createAESSecretKey_matchesPbkdf2WithAnAsciiPassword() {
        assertEquals(
            "0394a2ede332c9a13eb82e9b24631604c31df978b4e2f0fbd2c549944f9d79a5",
            Cryptography.createAESSecretKey("password", "salt".encodeToByteArray()).toHexString(),
        )
    }

    @Test
    fun createAESSecretKey_matchesPbkdf2WithAPasswordThatIsLongerThanAnHmacKeyBlockHalf() {
        // 40 bytes: longer than the 32 the Linux HMAC used to normalise to, shorter than SHA-256's block.
        assertEquals(
            "905ff1c8779d4eeacacb42804752903130fed48d5c5101f4b4ee5e52ef758fb0",
            Cryptography.createAESSecretKey("a".repeat(40), "channel-salt".encodeToByteArray()).toHexString(),
        )
    }

    @Test
    fun hmacSha256_matchesRfc2104ForShortLongAndOverlongKeys() {
        val message = "bitchat hmac vector".encodeToByteArray()

        assertEquals(
            "a8700c26bb42027b11baee5b94110270bc2733db75b286613586886da01b1d30",
            Cryptography.hmacSha256("k".repeat(8).encodeToByteArray(), message).toHexString(),
        )
        // 40 bytes: padded to the block in RFC 2104, hashed by the old Linux code.
        assertEquals(
            "70047c3c5352eaa688e1f95724a99a95ee7f41caf92cabdaa1aa9cef66d965a9",
            Cryptography.hmacSha256("k".repeat(40).encodeToByteArray(), message).toHexString(),
        )
        // 80 bytes: hashed first by the standard, so this case always agreed.
        assertEquals(
            "c6cd5a4a52f687945dd04956eb4c7e2eef1adfffa4da93c05eecc25c1c4fc645",
            Cryptography.hmacSha256("k".repeat(80).encodeToByteArray(), message).toHexString(),
        )
    }
}
