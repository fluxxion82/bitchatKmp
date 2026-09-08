package com.bitchat.local.identity

import com.bitchat.local.prefs.ensureDirectory
import com.bitchat.local.prefs.ensureParentDirectory
import com.bitchat.local.prefs.readWholeFileOrNull
import com.bitchat.local.prefs.writeFileDurably

/**
 * The identity ledger as a file in the config directory.
 *
 * Reads through the same whole-file reader the preference stores use and writes through the same
 * atomic, durable write - `mkstemp`, 0600, `fsync`, `rename`, `fsync` of the directory - rather
 * than inventing a second, less careful writer. The live file is never moved aside first.
 *
 * Neither method throws. A ledger that cannot be read must present as [LedgerClaims.Damaged],
 * which refuses, rather than as an exception thrown somewhere up the startup path of a headless
 * device; and a claim that cannot be written must not fail the load that produced it, because
 * the key it describes is already saved and the next start will record it.
 */
class LinuxLedgerStore(
    private val configDir: String = LinuxIdentityPaths.configDir,
) : LedgerStore {

    override val location: String = "$configDir/${IdentityLedger.FILE_NAME}"

    override fun read(): LedgerClaims = try {
        IdentityLedger.parse(readWholeFileOrNull(location))
    } catch (e: Throwable) {
        LedgerClaims.Damaged(listOf("$location could not be read: ${e.message}"))
    }

    override fun write(claims: LedgerClaims.Present): Boolean = try {
        // The parent is $HOME/.config, which belongs to the user, not to this application:
        // created if it is missing, never chmod'ed if it is not.
        ensureParentDirectory(configDir.substringBeforeLast('/', ".").ifEmpty { "/" })
        ensureDirectory(configDir)
        writeFileDurably(location, IdentityLedger.encode(claims).encodeToByteArray())
        true
    } catch (e: Throwable) {
        println("LinuxLedgerStore: could not write $location: ${e.message}")
        false
    }
}
