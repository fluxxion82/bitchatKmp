package com.bitchat.local.identity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Where the identity domain actually is on this machine.
 *
 * Resolved once, from the environment, and never hardcoded anywhere else: the point of an
 * `XDG_CONFIG_HOME`-aware resolver is that no document and no procedure can know in advance
 * where it landed.
 *
 * On the target device `HOME` is set by systemd (it derives `HOME`, `USER`, `LOGNAME` and
 * `SHELL` from the account database for a `User=` unit) and `XDG_CONFIG_HOME` is not, so the
 * `$HOME/.config` branch is the production path rather than an edge case.
 *
 * The `?: "/tmp"` fallback mirrors, deliberately, what `LinuxEncryptionSettingsFactory` and
 * `LinuxSettingsFactory` already do with an unset `HOME`. The scan has to look where the stores
 * are actually written, not where they ought to be, or it would report an empty domain for a
 * device whose files are all in `/tmp`. Tightening the unset-`HOME` case is a later task's job,
 * and it has to change all three call sites at once.
 */
@OptIn(ExperimentalForeignApi::class)
object LinuxIdentityPaths {

    val home: String = getenv("HOME")?.toKString()?.takeIf { it.isNotEmpty() } ?: "/tmp"

    /** Secret stores: `*.prefs`, and later `*.prefs.enc`. */
    val prefsDir: String = "$home/.bitchat/prefs"

    /** The nine non-secret stores. No secrets, but their existence proves the app has run here. */
    val settingsDir: String = "$home/.bitchat/settings"

    /** The identity ledger, and later the master key. Deliberately outside `~/.bitchat`. */
    val configDir: String = run {
        val xdg = getenv("XDG_CONFIG_HOME")?.toKString()
        // The XDG spec says a relative value is invalid and must be ignored.
        val base = if (xdg != null && xdg.startsWith("/")) xdg else "$home/.config"
        "$base/bitchat"
    }

    /** The three directories of the identity domain, in a stable order. */
    val domain: List<String> = listOf(prefsDir, configDir, settingsDir)
}
