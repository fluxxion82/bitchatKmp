package com.bitchat.repo.di

import com.bitchat.bluetooth.facade.CryptoSigningFacade
import com.bitchat.local.prefs.BlockListPreferences
import com.bitchat.local.prefs.SecureIdentityPreferences
import com.bitchat.local.prefs.UserPreferences
import com.bitchat.local.prefs.isSecureStoreUnavailable
import org.koin.core.Koin

/** Whether a start got what it needs from the secure store. */
sealed interface SecureStoreStart {
    /** The stores are open and the identity is loaded. */
    data object Ready : SecureStoreStart

    /**
     * The secure store did not answer: as a rule because the device is locked. The start did
     * not complete; what was built before the failure stays for the next try, which is to be made
     * when the device may have been unlocked.
     *
     * @param reason the store's own words for it, for a log line or a waiting screen.
     */
    data class Unavailable(val reason: String) : SecureStoreStart
}

/**
 * The first step of a start: opens the three secure stores and loads the identity.
 *
 * It does for real what the rest of the start would do anyway, and reports the one outcome a
 * start must survive. An app the system launches in the background on a locked phone cannot read
 * a Keychain that is still locked (before the first unlock after a restart, or with items an
 * older build saved as readable only when unlocked), and each of these steps then fails where
 * nobody takes the failure. Asking beforehand whether the store can be read would settle nothing:
 * the phone can lock between the question and the read, and one item says nothing about another.
 *
 * Every failure of the secure store is taken the same way, whatever status it came with: there
 * is no telling a locked Keychain from the outside by the way it fails. Nothing that failed is
 * kept, so the same call can be made again.
 *
 * After [SecureStoreStart.Ready] the identity is in memory and every store has been opened. On
 * Apple an opened store has its items readable on a locked phone from then on, with one
 * exception it reports in the log: items it could not move out of the old class for a reason
 * other than the device being locked.
 *
 * @throws Throwable whatever stopped it, when that is not the secure store: a damaged identity
 *   record and a fault of this code fail a start as they did before this existed.
 */
fun Koin.openSecureStores(): SecureStoreStart = try {
    get<UserPreferences>()
    get<BlockListPreferences>()
    get<SecureIdentityPreferences>()
    get<CryptoSigningFacade>()
    SecureStoreStart.Ready
} catch (e: Exception) {
    if (!e.isSecureStoreUnavailable()) throw e
    SecureStoreStart.Unavailable(storesReason(e))
}

// The innermost message is the store's own; what wrapped it on the way up repeats or hides it.
private fun storesReason(failure: Throwable): String =
    generateSequence(failure) { it.cause }.take(32).last().message?.lineSequence()?.firstOrNull() ?: "no reason given"
