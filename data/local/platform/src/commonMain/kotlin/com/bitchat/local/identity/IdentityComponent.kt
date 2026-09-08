package com.bitchat.local.identity

/**
 * A piece of identity key material that this device can hold exactly one of, for ever.
 *
 * Every one of these is write-once in a device's life. Creating a second one is not an update,
 * it is a new device wearing the old one's name: a fresh Nostr key means a new npub, so every
 * peer that favourited this device loses it and every message addressed to the old key becomes
 * undeliverable; a fresh mesh signing key breaks every fingerprint anyone recorded.
 *
 * @param storeKey the record name inside the `bitchat_identity` store.
 * @param claimName the record name inside the identity ledger that says "this device has
 *   created this component". Claims hold **public** values only - see [IdentityLedger].
 */
enum class IdentityComponent(val storeKey: String, val claimName: String) {
    MESH_STATIC("static_private_key", "claim.mesh_static"),
    MESH_SIGNING("signing_private_key", "claim.mesh_signing"),
    NOSTR_PRIVATE("nostr_private_key", "claim.nostr"),
    NOSTR_DEVICE_SEED("nostr_device_seed", "claim.nostr_seed"),
    ;

    companion object {
        private val byStoreKey = entries.associateBy { it.storeKey }

        /** The component stored under [key], or null when [key] is not identity material. */
        fun forStoreKey(key: String): IdentityComponent? = byStoreKey[key]
    }
}
