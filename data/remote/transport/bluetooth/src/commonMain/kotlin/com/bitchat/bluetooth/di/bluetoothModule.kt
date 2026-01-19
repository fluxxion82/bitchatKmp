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

        // Load existing signing key or generate a new Ed25519 keypair
        val privateKeyHex = securePrefs.loadSigningKey()?.let { (privateKeyBytes, publicKeyBytes) ->
            val privateKeyHexValue = privateKeyBytes.toHexString()
            val derivedPublicHex = Cryptography.deriveEd25519PublicKey(privateKeyHexValue)
            val storedPublicHex = publicKeyBytes.toHexString()
            if (!storedPublicHex.equals(derivedPublicHex, ignoreCase = true)) {
                securePrefs.saveSigningKey(privateKeyBytes, derivedPublicHex.hexToByteArray())
            }
            privateKeyHexValue
        } ?: run {
            val (privHex, pubHex) = Cryptography.generateEd25519KeyPair()
            securePrefs.saveSigningKey(privHex.hexToByteArray(), pubHex.hexToByteArray())
            privHex
        }

        CryptoSigningFacade(privateKeyHex = privateKeyHex)
    }
}
