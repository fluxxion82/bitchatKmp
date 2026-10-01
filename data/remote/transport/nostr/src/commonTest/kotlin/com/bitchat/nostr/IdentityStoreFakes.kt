package com.bitchat.nostr

import com.bitchat.transport.IdentityRefusedException
import com.bitchat.transport.IdentityStoreState
import com.bitchat.transport.TransportIdentityProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The identity store as a transport sees it.
 *
 * Mints are decided by [NostrIdentityMintPolicy], the frozen reference for the keychain-backed
 * platforms. What only the embedded custodian can see - a ledger claim for a key the store has
 * lost, say - is modelled by [refusal]: the real decision table is tested where it lives, in
 * `:data:local:platform`, which this module cannot depend on.
 */
internal class FakeIdentityStore(
    initial: Map<String, String> = emptyMap(),
    /** Overrides the state derived from [values], e.g. to model a store that did not load. */
    var state: IdentityStoreState? = null,
    /** Thrown instead of minting, as the custodian would for evidence this module cannot see. */
    var refusal: IdentityRefusedException? = null,
    /** Thrown when a minted value is saved. */
    var saveFailure: Throwable? = null,
) : TransportIdentityProvider {
    val values = LinkedHashMap(initial)

    /** How many times a `mint` function was invoked, whether or not it succeeded. */
    var mintCalls = 0
        private set

    override fun loadKey(key: String): String? = values[key]

    override fun saveKey(key: String, value: String) {
        saveFailure?.let { throw it }
        values[key] = value
    }

    override fun hasKey(key: String): Boolean = key in values

    override fun removeKeys(vararg keys: String) {
        keys.forEach { values.remove(it) }
    }

    override fun clearAll() = values.clear()

    override fun storeState(): IdentityStoreState =
        state ?: if (values.isEmpty()) IdentityStoreState.FIRST_RUN else IdentityStoreState.POPULATED

    override fun loadOrMint(key: String, publicFormOf: (String) -> String, mint: () -> String): String {
        values[key]?.let { return it }
        refusal?.let { throw it }
        val decision = NostrIdentityMintPolicy.decide(storeState(), key)
        if (decision is MintDecision.Refuse) {
            throw IdentityRefusedException(decision.reason, "restore the identity store")
        }
        mintCalls++
        return mint().also { saveKey(key, it) }
    }
}

internal class FakeNostrPreferences : NostrPreferences {
    override fun getLastUpdateMs(): Long = 0L
    override fun setLastUpdateMs(value: Long) = Unit
    override fun setPowEnabled(enabled: Boolean) = Unit
    override fun getPowEnabled(): Boolean = false
    override fun setPowDifficulty(difficulty: Int) = Unit
    override fun getPowDifficulty(): Int = 0
    override fun setIsMining(isMining: Boolean) = Unit
    override fun getIsMiningFlow(): Flow<Boolean> = flowOf(false)
}
