package com.bitchat.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One frozen legacy envelope per platform that produces them, each opened by whichever platform runs
 * this suite. `Nip44ConformanceTest` pins the format against two upstream clients; these pin it
 * against *us*, so a change to one platform's actual cannot quietly stop its own traffic being
 * readable by the others.
 *
 * Why this matters more than it looks: when the legacy path breaks, nothing reports it. The message
 * never appears, its acks never arrive, and the sender sees "sent" for ever
 * (docs/reviews/2026-10-01-nip44-conformance.md section 5.6). If a case here goes red, stop and find
 * out why rather than re-recording the payload.
 *
 * Each payload was produced by that platform's own `encryptNIP44` at commit 25199be, from the fixed
 * keys below, and recorded verbatim. The nonce is random per call, so these are samples of the
 * format, not reproducible outputs: never "fix" a failure by pasting a fresh payload.
 *
 * Recorded on: JVM `:data:crypto:jvmTest`; Android `:data:crypto:testAndroidHostTest` (its own
 * `Cryptography.android.kt`); Apple `:data:crypto:macosArm64Test` (`appleMain`, shared with iOS);
 * Linux `linuxArm64` `test.kexe` run on the Orange Pi, since there is no linuxArm64 host test gate.
 */
class LegacyEnvelopeFixturesTest {
    @Test
    fun legacyFixture_fromJvmOpens() = assertOpens(JVM_PAYLOAD)

    @Test
    fun legacyFixture_fromAndroidOpens() = assertOpens(ANDROID_PAYLOAD)

    @Test
    fun legacyFixture_fromAppleOpens() = assertOpens(APPLE_PAYLOAD)

    @Test
    fun legacyFixture_fromLinuxArm64Opens() = assertOpens(LINUX_ARM64_PAYLOAD)

    @Test
    fun legacyFixture_keysAreTheOnesTheFixturesWereMadeWith() {
        // A changed derivation would otherwise look like a decryption failure above.
        assertEquals(SENDER_PUBLIC, Cryptography.derivePublicKey(SENDER_PRIVATE))
        assertEquals(RECIPIENT_PUBLIC, Cryptography.derivePublicKey(RECIPIENT_PRIVATE))
    }

    @Test
    fun legacyFixture_everyPlatformProducedADistinctEnvelope() {
        // Guards the recording itself: two identical payloads would mean one platform was never run.
        val payloads = listOf(JVM_PAYLOAD, ANDROID_PAYLOAD, APPLE_PAYLOAD, LINUX_ARM64_PAYLOAD)
        assertEquals(payloads.size, payloads.toSet().size, "a fixture was copied, not recorded")
        assertTrue(payloads.all { it.startsWith("v2:") }, "a fixture is not a legacy envelope")
    }

    private fun assertOpens(payload: String) {
        assertEquals(PLAINTEXT, Cryptography.decryptNIP44(payload, SENDER_PUBLIC, RECIPIENT_PRIVATE))
    }

    private companion object {
        const val PLAINTEXT = "legacy envelope fixture"
        const val SENDER_PRIVATE = "0000000000000000000000000000000000000000000000000000000000000003"
        const val RECIPIENT_PRIVATE = "0000000000000000000000000000000000000000000000000000000000000004"
        const val SENDER_PUBLIC = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        const val RECIPIENT_PUBLIC = "e493dbf1c10d80f3581e4904930b1404cc6c13900ee0758474fa94abe8c4cd13"

        const val JVM_PAYLOAD =
            "v2:6nM0gseJBP4oohBRSdJ53qqVL_rjYhDoVykEvQdFO8mJvFV0OnYI05j2aso4jmnPtVLRvg-IKEghRD4aDUdQ"
        const val ANDROID_PAYLOAD =
            "v2:73muCD3b1oyLIGTelbM6s2umekwbgaBKpV-NbEvatCgEoWeVyPqbjk5SVJsWoY_OIz5oxwR1UiLX_ANz5bsV"
        const val APPLE_PAYLOAD =
            "v2:y_wPs5qZKQD93z2kst7T8RncRyB8jgu3kgwMAJ4EpFFqdGvjjcJyHtyTvVTVYXsSktWhB1rLoFb0-I2e2wEG"
        const val LINUX_ARM64_PAYLOAD =
            "v2:RTmwWCo2y358kCtAL9yFsGyhslkhJKnULpilOKBirSIpnvd-7Ay6ZLm8fEhiG2acdzwLAxRK-CLv21__4TQ9"
    }
}
