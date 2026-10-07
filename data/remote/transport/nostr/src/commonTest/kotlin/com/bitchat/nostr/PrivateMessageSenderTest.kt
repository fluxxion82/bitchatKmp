package com.bitchat.nostr

import com.bitchat.crypto.Cryptography
import com.bitchat.nostr.model.NostrEvent
import com.bitchat.nostr.model.NostrIdentity
import com.bitchat.nostr.model.NostrKind
import com.bitchat.transport.IdentityStoreState
import com.bitchat.transport.TransportIdentityProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * The gift wrap is signed by a throwaway key, and the rumor inside the seal is unsigned, so the
 * seal's signature is the only proof of who sent a DM. ChatRepo attributes the message, checks the
 * block list and handles favourite notices by the sender [NostrClient.decryptPrivateMessage]
 * returns, so that sender must be the seal's signer and nothing else.
 *
 * The hand-built envelopes below differ from a genuine one in a single field each; the
 * `a hand-built envelope ...` case shows the same helpers produce something that is accepted.
 */
class PrivateMessageSenderTest {
    private val client = NostrClient(UnusedPreferences, UnusedIdentityProvider)
    private val recipient = NostrIdentity.generate()
    private val sender = NostrIdentity.generate()

    @Test
    fun `a message carries the time it is given, which is what the other side sorts by`() {
        val wrap = client.createPrivateMessage("bitchat1:hello", recipient.publicKeyHex, sender, createdAt = 1_791_000_123).single()

        val (_, _, timestamp) = assertNotNull(client.decryptPrivateMessage(wrap, recipient))

        assertEquals(1_791_000_123, timestamp)
    }

    @Test
    fun `a genuine message round-trips and names the seal signer as sender`() {
        val wrap = client.createPrivateMessage("bitchat1:hello", recipient.publicKeyHex, sender).single()

        val (content, senderPubkey, _) = assertNotNull(client.decryptPrivateMessage(wrap, recipient))

        assertEquals("bitchat1:hello", content)
        assertEquals(sender.publicKeyHex, senderPubkey)
    }

    @Test
    fun `a hand-built envelope is accepted when the rumor names its sealer`() {
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex), sealer = sender))

        val (content, senderPubkey, _) = assertNotNull(client.decryptPrivateMessage(wrap, recipient))

        assertEquals("hi", content)
        assertEquals(sender.publicKeyHex, senderPubkey)
    }

    @Test
    fun `a rumor claiming a favourite but sealed by another key is rejected`() {
        val favourite = NostrIdentity.generate()
        val attacker = NostrIdentity.generate()
        // A valid seal, honestly signed by the attacker, around a rumor that claims the favourite.
        val wrap = giftWrap(seal(rumor(favourite.publicKeyHex), sealer = attacker))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `an unsigned seal is rejected`() {
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex), sealer = sender).copy(sig = null))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a seal edited after signing is rejected`() {
        val signed = seal(rumor(sender.publicKeyHex), sealer = sender)
        val wrap = giftWrap(signed.copy(createdAt = signed.createdAt - 1))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a seal signed by a key other than its pubkey is rejected`() {
        val other = NostrIdentity.generate()
        val forged = unsignedSeal(rumor(sender.publicKeyHex), sealer = sender).sign(other.privateKeyHex)

        assertNull(client.decryptPrivateMessage(giftWrap(forged), recipient))
    }

    @Test
    fun `a seal of the wrong kind is rejected`() {
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex), sealer = sender, kind = NostrKind.TEXT_NOTE))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a seal carrying tags is rejected`() {
        val tags = listOf(listOf("p", recipient.publicKeyHex))
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex), sealer = sender, tags = tags))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a rumor of the wrong kind is rejected`() {
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex, kind = NostrKind.TEXT_NOTE), sealer = sender))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a signed rumor is rejected`() {
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex).sign(sender.privateKeyHex), sealer = sender))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a rumor addressed to someone else is rejected`() {
        val tags = listOf(listOf("p", NostrIdentity.generate().publicKeyHex))
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex, tags = tags), sealer = sender))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a rumor with extra tags is rejected`() {
        val tags = listOf(listOf("p", recipient.publicKeyHex), listOf("e", "00".repeat(32)))
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex, tags = tags), sealer = sender))

        assertNull(client.decryptPrivateMessage(wrap, recipient))
    }

    @Test
    fun `a rumor without tags is accepted as upstream iOS sends it`() {
        val wrap = giftWrap(seal(rumor(sender.publicKeyHex, tags = emptyList()), sealer = sender))

        val (_, senderPubkey, _) = assertNotNull(client.decryptPrivateMessage(wrap, recipient))

        assertEquals(sender.publicKeyHex, senderPubkey)
    }

    @Test
    fun `a seal produced by upstream Android is accepted`() {
        val fixtureRecipient = NostrIdentity.fromPrivateKey(ANDROID_B7F0B33D_RECIPIENT_PRIVATE_KEY)
        val wrap = rewrap(ANDROID_B7F0B33D_WRAP_PUBKEY, ANDROID_B7F0B33D_WRAP_CONTENT, fixtureRecipient)

        val (content, senderPubkey, _) = assertNotNull(client.decryptPrivateMessage(wrap, fixtureRecipient))

        assertEquals("legacy fixture from Android b7f0b33d", content)
        assertEquals(ANDROID_B7F0B33D_SENDER_PUBKEY, senderPubkey)
    }

    @Test
    fun `a seal produced by an upstream iOS release is accepted`() {
        val fixtureRecipient = NostrIdentity.fromPrivateKey(IOS_733098BB_RECIPIENT_PRIVATE_KEY)
        val wrap = rewrap(IOS_733098BB_WRAP_PUBKEY, IOS_733098BB_WRAP_CONTENT, fixtureRecipient)

        val (content, senderPubkey, _) = assertNotNull(client.decryptPrivateMessage(wrap, fixtureRecipient))

        assertEquals("legacy fixture from 733098bb", content)
        assertEquals(IOS_733098BB_SENDER_PUBKEY, senderPubkey)
    }

    private fun rumor(
        pubkey: String,
        kind: Int = NostrKind.DIRECT_MESSAGE,
        tags: List<List<String>> = listOf(listOf("p", recipient.publicKeyHex)),
    ): NostrEvent {
        val rumor = NostrEvent(pubkey = pubkey, createdAt = now(), kind = kind, tags = tags, content = "hi")
        return rumor.copy(id = rumor.computeEventIdHex())
    }

    private fun unsignedSeal(
        rumor: NostrEvent,
        sealer: NostrIdentity,
        kind: Int = NostrKind.SEAL,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        pubkey = sealer.publicKeyHex,
        createdAt = now(),
        kind = kind,
        tags = tags,
        content = Cryptography.sealBitchatEnvelope(Json.encodeToString(rumor), recipient.publicKeyHex, sealer.privateKeyHex),
    )

    private fun seal(
        rumor: NostrEvent,
        sealer: NostrIdentity,
        kind: Int = NostrKind.SEAL,
        tags: List<List<String>> = emptyList(),
    ) = unsignedSeal(rumor, sealer, kind, tags).sign(sealer.privateKeyHex)

    private fun giftWrap(seal: NostrEvent) = giftWrap(Json.encodeToString(seal), recipient.publicKeyHex)

    /** Puts an upstream client's seal, byte for byte, into a fresh wrap that passes the age check. */
    private fun rewrap(wrapPubkey: String, wrapContent: String, to: NostrIdentity): NostrEvent {
        val sealJson = Cryptography.openBitchatEnvelope(wrapContent, wrapPubkey, to.privateKeyHex)
        return giftWrap(sealJson, to.publicKeyHex)
    }

    private fun giftWrap(sealJson: String, recipientPubkey: String): NostrEvent {
        val (wrapPrivateKey, wrapPublicKey) = Cryptography.generateKeyPair()
        return NostrEvent(
            pubkey = wrapPublicKey,
            createdAt = now(),
            kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipientPubkey)),
            content = Cryptography.sealBitchatEnvelope(sealJson, recipientPubkey, wrapPrivateKey),
        ).sign(wrapPrivateKey)
    }

    private fun now() = Clock.System.now().epochSeconds.toInt()

    private object UnusedPreferences : NostrPreferences {
        override fun getLastUpdateMs(): Long = unused()
        override fun setLastUpdateMs(value: Long) = unused()
        override fun setPowEnabled(enabled: Boolean) = unused()
        override fun getPowEnabled(): Boolean = unused()
        override fun setPowDifficulty(difficulty: Int) = unused()
        override fun getPowDifficulty(): Int = unused()
        override fun setIsMining(isMining: Boolean) = unused()
        override fun getIsMiningFlow(): Flow<Boolean> = unused()
    }

    private object UnusedIdentityProvider : TransportIdentityProvider {
        override fun loadKey(key: String): String? = unused()
        override fun saveKey(key: String, value: String) = unused()
        override fun hasKey(key: String): Boolean = unused()
        override fun removeKeys(vararg keys: String) = unused()
        override fun clearAll() = unused()
        override fun loadOrMint(key: String, publicFormOf: (String) -> String, mint: () -> String): String = unused()
        override fun storeState(): IdentityStoreState = unused()
    }

    private companion object {
        fun unused(): Nothing = error("the DM receive path does not touch preferences or stored identity")

        // Gift wraps (kind 1059) produced by upstream Android b7f0b33d and the released iOS 733098bb,
        // verbatim from permissionlesstech/bitchat (Unlicense) bitchatTests/Nostr/Fixtures at 5e9287f.
        // Their outer signature and timestamp are not part of the fixtures, so the tests re-wrap the
        // decrypted seal; the seal and the rumor inside it are exactly what those clients sent.
        const val ANDROID_B7F0B33D_RECIPIENT_PRIVATE_KEY = "0000000000000000000000000000000000000000000000000000000000000002"
        const val ANDROID_B7F0B33D_SENDER_PUBKEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
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

        const val IOS_733098BB_RECIPIENT_PRIVATE_KEY = "8355a5c110cdfef2e644f4ad5d51c39f253b2c2c80ebb6856379fb16531dc1fa"
        const val IOS_733098BB_SENDER_PUBKEY = "2e3d79df7047204f02b726c574e256f8de1dd80510f7dcb8b0d12df13acb87e6"
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
