package com.bitchat.local.di

import com.bitchat.local.prefs.EncryptionSettingsFactory
import com.bitchat.local.prefs.NativeEncryptionSettingsFactory
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The iOS graph asks for secure stores whose items can be read on a locked phone. With the other
 * kind the app ends the first time the phone locks while it runs.
 */
class IosSecureStoreBindingTest {
    @Test
    fun `the iOS secure stores keep their items readable while the phone is locked`() {
        val application = koinApplication { modules(localModule) }
        try {
            val factory = application.koin.get<EncryptionSettingsFactory>()

            assertIs<NativeEncryptionSettingsFactory>(factory)
            assertTrue(factory.readableWhileLocked)
        } finally {
            application.close()
        }
    }
}
