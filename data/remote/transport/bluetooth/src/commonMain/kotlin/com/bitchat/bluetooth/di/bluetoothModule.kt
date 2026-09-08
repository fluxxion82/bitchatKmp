package com.bitchat.bluetooth.di

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.bluetooth.service.BluetoothMeshService
import com.bitchat.crypto.Cryptography
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.local.util.hexToByteArray
import com.bitchat.local.util.toHexString
import org.koin.core.module.Module
import org.koin.dsl.module

expect val platformBleModule: Module
expect val connectionModule: Module

val bluetoothModule = module {
    includes(platformBleModule, connectionModule)
    single {
        BluetoothMeshService(
            scanningService = get(),
            connectionService = get(),
            gattServerService = get(),
            advertisingService = get(),
            cryptoSigning = get(),
        )
    }

    single {
        val securePrefs: SecureIdentityPreferences = get()

        // This is the first identity write on a new device - it runs while Koin builds the
        // graph, before Nostr is ever asked for its key - and it used to create a fresh Ed25519
        // keypair whenever loadSigningKey() came back null, with no check of any kind on why it
        // was null. It now goes through the same custodian as the Nostr keys, which mints only
        // on positive proof of a first run and throws IdentityRefusedException otherwise.
        val (privateKeyBytes, publicKeyBytes) = securePrefs.loadOrMintSigningKey {
            val (privHex, pubHex) = Cryptography.generateEd25519KeyPair()
            privHex.hexToByteArray() to pubHex.hexToByteArray()
        }

        // Repair, not a mint: the private key is present and authoritative, and only the stored
        // public key is being corrected. Left exactly as it was.
        val privateKeyHex = privateKeyBytes.toHexString()
        val derivedPublicHex = Cryptography.deriveEd25519PublicKey(privateKeyHex)
        if (!publicKeyBytes.toHexString().equals(derivedPublicHex, ignoreCase = true)) {
            securePrefs.saveSigningKey(privateKeyBytes, derivedPublicHex.hexToByteArray())
        }

        CryptoSigningFacade(privateKeyHex = privateKeyHex)
    }
}
