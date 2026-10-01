package com.bitchat.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Where encryptNIP44/decryptNIP44 stand against NIP-44 v2. Despite the names they implement
 * BitChat's private envelope, the same scheme as upstream bitchat iOS and Android, not NIP-44.
 * See docs/reviews/2026-10-01-nip44-conformance.md.
 *
 * - legacy*: pins today's wire behaviour, including envelopes produced by upstream clients.
 *   Any migration must keep these green or deployed conversations break.
 * - nip44Deviation*: characterises the gap against the official vectors; passes today.
 * - nip44Spec*: official-vector acceptance, @Ignore'd because it is EXPECTED TO FAIL today.
 *
 * Official vectors are from paulmillr/nip44 nip44.vectors.json (sha256 269ed0f6..., the checksum
 * published in NIP-44). Upstream envelopes are from permissionlesstech/bitchat (Unlicense)
 * bitchatTests/Nostr/Fixtures at 5e9287f.
 */
class Nip44ConformanceTest {
    @Test
    fun legacyEnvelope_opensGiftWrapProducedByUpstreamAndroid() {
        val rumor = openLegacyGiftWrap(
            wrapPubkey = ANDROID_B7F0B33D_WRAP_PUBKEY,
            wrapContent = ANDROID_B7F0B33D_WRAP_CONTENT,
            recipientPrivateKeyHex = "0000000000000000000000000000000000000000000000000000000000000002",
            expectedSenderPubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        )

        assertEquals("legacy fixture from Android b7f0b33d", rumor.string("content"))
    }

    @Test
    fun legacyEnvelope_opensGiftWrapProducedByUpstreamIosRelease() {
        val rumor = openLegacyGiftWrap(
            wrapPubkey = IOS_733098BB_WRAP_PUBKEY,
            wrapContent = IOS_733098BB_WRAP_CONTENT,
            recipientPrivateKeyHex = "8355a5c110cdfef2e644f4ad5d51c39f253b2c2c80ebb6856379fb16531dc1fa",
            expectedSenderPubkey = "2e3d79df7047204f02b726c574e256f8de1dd80510f7dcb8b0d12df13acb87e6"
        )

        assertEquals("legacy fixture from 733098bb", rumor.string("content"))
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun legacyEnvelope_isV2PrefixedBase64UrlOfNonceCiphertextTag() {
        val plaintext = "hello nip44"

        val payload = Cryptography.encryptNIP44(
            plaintext = plaintext,
            recipientPublicKeyHex = Cryptography.derivePublicKey(SEC2),
            senderPrivateKeyHex = SEC1
        )

        // ':' is outside the Base64 alphabet, so a NIP-44 v2 reader rejects this before any crypto.
        assertTrue(payload.startsWith("v2:"))
        val body = payload.removePrefix("v2:")
        assertTrue(body.none { it == '+' || it == '/' || it == '=' })
        val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(body)
        // 24-byte XChaCha20 nonce + unpadded ciphertext + 16-byte Poly1305 tag. NIP-44 v2 would be
        // version(1) + nonce(32) + padded ciphertext(34) + HMAC(32) = 99 bytes here.
        assertEquals(24 + plaintext.length + 16, decoded.size)
    }

    @Test
    fun legacyKeySchedule_isEmptySaltHkdfOverCompressedSharedPoint() {
        // sec1 = 1, so the shared point is lift_x(pub2) and its compressed form is 0x02 || pub2.
        val compressedSharedPoint = byteArrayOf(0x02) + G_X.unhex()
        val prk = Cryptography.hmacSha256(ByteArray(0), compressedSharedPoint)
        val okm = Cryptography.hmacSha256(prk, "nip44-v2".encodeToByteArray() + byteArrayOf(0x01))

        val derived = Cryptography.deriveNIP44Key(compressedSharedPoint)

        assertEquals(okm.hex(), derived.hex())
        assertEquals("094ae949687633b63ca2c51dc2c0b2ec2ece8920b7e2e51d29fee568cca28c68", derived.hex())
    }

    @Test
    fun nip44Deviation_conversationKeyIsLabelSaltedExtractOverSharedX() {
        // Official vectors with sec1 = 1, where shared_x equals pub2.
        val vectors = listOf(
            G_X to "3b4610cb7189beb9cc29eb3716ecc6102f1247e8f3101a03a1787d8908aeb54e",
            TWO_G_X to "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d"
        )

        for ((sharedX, conversationKey) in vectors) {
            val ours = Cryptography.deriveNIP44Key(byteArrayOf(0x02) + sharedX.unhex())
            val oursOverX = Cryptography.deriveNIP44Key(sharedX.unhex())
            val spec = Cryptography.hmacSha256("nip44-v2".encodeToByteArray(), sharedX.unhex())

            assertNotEquals(conversationKey, ours.hex())
            assertNotEquals(conversationKey, oursOverX.hex())
            assertEquals(conversationKey, spec.hex())
        }
    }

    @Test
    fun nip44Deviation_officialPayloadsAreRejected() {
        for (vector in OFFICIAL_ENCRYPT_DECRYPT) {
            assertFailsWith<Exception> {
                Cryptography.decryptNIP44(
                    ciphertext = vector.payload,
                    senderPublicKeyHex = Cryptography.derivePublicKey(vector.sec1),
                    recipientPrivateKeyHex = vector.sec2
                )
            }
        }
    }

    // EXPECTED TO FAIL until a NIP-44 v2 path exists (decryptNIP44 rejects the format, see above).
    // Un-ignore as part of the migration in docs/reviews/2026-10-01-nip44-conformance.md.
    @Ignore
    @Test
    fun nip44Spec_decryptsOfficialPayloads() {
        for (vector in OFFICIAL_ENCRYPT_DECRYPT) {
            val decrypted = Cryptography.decryptNIP44(
                ciphertext = vector.payload,
                senderPublicKeyHex = Cryptography.derivePublicKey(vector.sec1),
                recipientPrivateKeyHex = vector.sec2
            )

            assertEquals(vector.plaintext, decrypted)
        }
    }

    private fun openLegacyGiftWrap(
        wrapPubkey: String,
        wrapContent: String,
        recipientPrivateKeyHex: String,
        expectedSenderPubkey: String
    ): JsonObject {
        val seal = Json.parseToJsonElement(
            Cryptography.decryptNIP44(wrapContent, wrapPubkey, recipientPrivateKeyHex)
        ).jsonObject
        assertEquals(13, seal.getValue("kind").jsonPrimitive.int)
        assertEquals(expectedSenderPubkey, seal.string("pubkey"))

        val rumor = Json.parseToJsonElement(
            Cryptography.decryptNIP44(seal.string("content"), seal.string("pubkey"), recipientPrivateKeyHex)
        ).jsonObject
        assertEquals(14, rumor.getValue("kind").jsonPrimitive.int)
        assertEquals(expectedSenderPubkey, rumor.string("pubkey"))
        return rumor
    }

    private class OfficialVector(val sec1: String, val sec2: String, val plaintext: String, val payload: String)

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val SEC1 = "0000000000000000000000000000000000000000000000000000000000000001"
        const val SEC2 = "0000000000000000000000000000000000000000000000000000000000000002"
        const val G_X = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        const val TWO_G_X = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5"

        val OFFICIAL_ENCRYPT_DECRYPT = listOf(
            OfficialVector(
                sec1 = SEC1,
                sec2 = SEC2,
                plaintext = "a",
                payload = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"
            ),
            OfficialVector(
                sec1 = SEC2,
                sec2 = SEC1,
                plaintext = "🍕🫃",
                payload = "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"
            ),
            OfficialVector(
                sec1 = "5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a",
                sec2 = "4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d",
                plaintext = "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀",
                payload = "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="
            )
        )

        // Released iOS 733098bb and upstream Android b7f0b33d gift wraps (kind 1059), verbatim.
        const val ANDROID_B7F0B33D_WRAP_PUBKEY = "6981231b5745520fd982f66f485fa1b42f8f91ad25ab32242d4afb839d696b0a"
        const val ANDROID_B7F0B33D_WRAP_CONTENT =
            "v2:ETvtGOrkDQ2JIiiMFMdxBlrYNLh07-QKmLds0xsjkgs-v54CP4E4uoKl1L_VvKvaREg-EviPQmksd3DOYHrBVAd5E6xuR" +
            "n1o9vkoss4MYV7I71mu4RfADRt77ohZaNbc-KhrmgyRTgE-WnEsqErax8LUN6GUukjkKncVA9MAmC1WUB1MI1AR8c4BQ0IOc" +
            "_ubrEqi640a8AeBZaCZOYutCb3ttqNSPBxR63XErE761KNg1uiq5pgBVo8iKvLO-N2ei7IWvQhmDTapBaEU7LexeIdHDEwMd" +
            "XDoAtr5Nmd54H1VN12tBvk1wXvHnENgZCOLOR5J2E1eSwrFXXBto-ohrNpBaLKIXBTPGEoepa7x0gC0Vrh1OTf4tCI7JJ5UW" +
            "nkUAQFGUgPGlTRYC8MdESgEthqmdgKT3Jc0N6sylTmv6zSVx5dXqO4fvSLHC6_7it8F_V_8-uAxUYstJz65oK4F9CwOEVglU" +
            "uUdfn2mN_3cMBzASLOlKvL8jbkwwo5aMBVrShGiEwDix02hfGMNMKf7OKsLlfNiBAVSPh6MQSvAWhxbDCDnWN-yHKWF4TYxb" +
            "nH70X2KGl_ZD9pXTphKVIpFuRmP7UNM-01oG53NKcv9puBSxAkLbTZd122uFL_zQebxid1ukOXT8WgSB_WYJYoAlQekeu4IT" +
            "ryB4I60vAAzMAa4AppCUNnf6T8jwWxuC-8G0TPf18MTxpeNkzcSpPVyVE0jtLaOwsJDXs_Pg82Id03Qc-b_fWMm9V07UzGmE" +
            "nMqQ1gBMLmEXHb_4Ebg5X7TdzuXy87O36CJzau7Dm5ZfoalryF-16Z4MxzQOyXb1G61yFthRsGCT6sYi-68YkhPScMf7u_Bo" +
            "btMuGWiMiWoBqN_IrQ_ecMHVfaeEYvpCz5NYlrE26iAktNmzCBUDNcIr6P_nHdb6I3Q1rOOmWwEF7jsLbvnU_w_82_nXE_yf" +
            "dGsoly24A2wB0L0SpdnyyEWgvKWjjfS3J3vkIVW4_iM0FT0jNelANc2X_ryb3EPTmGenlqm_qRGh86PYk_R07hKYu3ULNEgL" +
            "zDTijeZ9-bP23tsMXUDdJS2VR7LP5063ygVuSC0J-GL1FmQ2c-DmJaWQSeqp0NN3sCND3pSIRzwQRwChnMNjVB65mJj"
        const val IOS_733098BB_WRAP_PUBKEY = "960e391e314a7fb00bbdd85eccb0a93c17e981b6fed38487cf891f1ed6b66aeb"
        const val IOS_733098BB_WRAP_CONTENT =
            "v2:SorfnTaoQ_Rv0XLKA8b3FZojgaFvnWgx66JR6Gj20ztnorQyL-hUYBZwAa5ohFC6ioR9hlJARJm0cgNTvWgSZuNTcfSAv" +
            "oMdSG6mXK73kzsp99x351zUB_lc1_5ZXm0qqePAEz3Dl5jj5EsfAb8ZzsCd-BZENCsTwqjqC_hCFl7RitlRfIL2Tq_n4TUFE" +
            "FandDCplNz3W4L7V07V89aGEoUlhzcwPU44BcxfvQjeqVKq0IRf2AetypoOduPrjSqkv674ubWaRcHtw3Asrqgo5XSYbpOlS" +
            "k1PF_TttrQSPZGViNe5MuiK2P-7d-XqKXuDf_bUgAzW884KXogbct-wtIJZJbVM-utMd-dHrpC1mY81lgpS4_kPuhj0Z6Ro9" +
            "hU5nCcEk2K4_vNoSM9m9QbcXP34h47qSPsw9ikmz8UoD00-1fXQVB4YJcBVUSVI06IzbZEWulo8SPXvQ4pJjV3nwPgYqRgwn" +
            "rNWMNfeuKojlF8yA17UNOWvD2U7r1cs84HL8dxztzX9NdN0DGxvjMILvt3D4eWrMbcSrsIkBgyvV-uskEPd4eX3fc9GmX8MP" +
            "kMgxRErcxbuq6JBUNXikOJXNH_qspOt4UIw5dCIajrHsKycKd8A_3rSgLEQteirOWMGaD2gOJEzpbe4iT72dmPkvRq7k-wDj" +
            "LbO5dOSuUvpFM-ipkB07ATJz_1uUcRpl8fD_oSlcdAzdPjGKG5Y-tNv3AcUstkqCi52E5x-aO8EDUNBFi_OCbD86nkTUv1jO" +
            "pAQwejowSiiOnCZ91zUEg5pRQyhBV0Ozib-j0Wf8S8VAz9M8bqQQaKedPCEowDn6csOKbFdBtqSeROf1XWKCkm1mSJGHWW9V" +
            "44MiMiHebVrRUpbP0PqvxiIeJE0"
    }
}
