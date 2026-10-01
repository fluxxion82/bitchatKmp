package com.bitchat.local.identity

import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.HealthReportingSettings
import com.bitchat.local.prefs.impl.LocalSecureIdentityPreferences
import com.bitchat.local.transport.SecureTransportIdentityProvider
import com.bitchat.nostr.NostrClient
import com.bitchat.nostr.NostrPreferences
import com.bitchat.transport.IdentityRefusedException
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Properties
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEED_KEY = "nostr_device_seed"
private const val GEOHASH = "u4pruyd"

/**
 * The Nostr device seed's custody end to end: the real `NostrClient`, through the real transport
 * adapter, into the real identity store, custodian and gate. Only the settings backend and the
 * ledger file are stand-ins.
 *
 * The unit tests beside this one cover each piece. This is the one that fails if the pieces stop
 * meeting - if the client stops routing the seed through the custodian, say, or the adapter
 * swallows a refusal - which is how a weak or replacement seed would actually reach a device.
 */
class NostrDeviceSeedCustodyTest {

    @Test
    fun `a first run mints a 32-byte seed and records its claim after it`() {
        val settings = PropertiesSettings(Properties())
        val ledger = Ledger(LedgerClaims.Absent)

        client(settings, inspector(DomainVerdict.Virgin), ledger).deriveIdentity(GEOHASH)

        assertEquals(32, Base64.decode(settings.getString(SEED_KEY, "")).size)
        val claims = assertIs<LedgerClaims.Present>(ledger.claims)
        assertEquals(IdentityLedger.CLAIM_PRESENT, IdentityLedger.claim(claims, IdentityComponent.NOSTR_DEVICE_SEED))
    }

    @Test
    fun `a seed the ledger claims but the store has lost is refused and not replaced`() {
        val settings = PropertiesSettings(Properties()).apply {
            putString("signing_private_key", "c2lnbmluZw==")
            putString("nostr_private_key", "00".repeat(31) + "01")
        }
        val ledger = Ledger(
            IdentityLedger.withClaim(
                IdentityLedger.create("0123456789abcdef0123456789abcdef"),
                IdentityComponent.NOSTR_DEVICE_SEED,
                IdentityLedger.CLAIM_PRESENT,
            ),
        )

        val refusal = assertFailsWith<IdentityRefusedException> {
            client(settings, inspector(DomainVerdict.Inhabited(emptyList())), ledger).deriveIdentity(GEOHASH)
        }

        assertTrue(refusal.reason.contains("recovered, not replaced"), refusal.reason)
        assertNull(settings.getStringOrNull(SEED_KEY), "a replacement seed was written")
        assertEquals(0, ledger.writes)
    }

    @Test
    fun `an unreadable store refuses the seed on the keychain-backed platforms`() {
        val settings = Damaged(PropertiesSettings(Properties()).apply { putString("signing_private_key", "c2lnbmluZw==") })

        val refusal = assertFailsWith<IdentityRefusedException> {
            client(settings, NoDomainInspector, NoLedgerStore).deriveIdentity(GEOHASH)
        }

        assertTrue(refusal.reason.contains("did not load cleanly"), refusal.reason)
        assertNull(settings.getStringOrNull(SEED_KEY), "a seed was minted into a store that did not load")
    }

    @Test
    fun `a truncated stored seed is refused and left where it is`() {
        val truncated = Base64.encode(ByteArray(16) { (it + 1).toByte() })
        val settings = PropertiesSettings(Properties()).apply { putString(SEED_KEY, truncated) }

        val refusal = assertFailsWith<IdentityRefusedException> {
            client(settings, NoDomainInspector, NoLedgerStore).deriveIdentity(GEOHASH)
        }

        assertTrue(refusal.reason.contains("it is 16 bytes"), refusal.reason)
        assertEquals(truncated, settings.getStringOrNull(SEED_KEY))
    }

    private fun client(settings: Settings, inspector: DomainInspector, ledger: LedgerStore): NostrClient {
        val identityStore = LocalSecureIdentityPreferences(
            encryptedPreferenceFactory = object : EncryptionSettingsFactory {
                override fun createEncrypted(name: String): Settings = settings
            },
            domainInspector = inspector,
            ledgerStore = ledger,
        )
        return NostrClient(NoNostrPreferences, SecureTransportIdentityProvider(identityStore))
    }

    private fun inspector(verdict: DomainVerdict) = object : DomainInspector {
        override fun scan(): DomainVerdict = verdict
    }

    private class Ledger(var claims: LedgerClaims) : LedgerStore {
        var writes = 0
        override val location: String = "/tmp/identity-ledger"
        override fun read(): LedgerClaims = claims
        override fun write(claims: LedgerClaims.Present): Boolean {
            writes++
            this.claims = claims
            return true
        }
    }

    /** A store that loaded with damage, as the embedded flat-file store reports it. */
    private class Damaged(delegate: Settings) : Settings by delegate, HealthReportingSettings {
        override val storeDamage: List<String> = listOf("truncated mid-record")
    }

    private object NoNostrPreferences : NostrPreferences {
        override fun getLastUpdateMs(): Long = 0L
        override fun setLastUpdateMs(value: Long) = Unit
        override fun setPowEnabled(enabled: Boolean) = Unit
        override fun getPowEnabled(): Boolean = false
        override fun setPowDifficulty(difficulty: Int) = Unit
        override fun getPowDifficulty(): Int = 0
        override fun setIsMining(isMining: Boolean) = Unit
        override fun getIsMiningFlow(): Flow<Boolean> = flowOf(false)
    }
}
