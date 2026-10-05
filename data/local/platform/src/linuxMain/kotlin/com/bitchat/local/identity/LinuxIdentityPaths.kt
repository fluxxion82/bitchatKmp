package com.bitchat.local.identity

import com.bitchat.local.statedir.StateDirectory
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
 * There is no `/tmp` fallback. Another local user can create a state directory there first, and
 * it does not survive a reboot; an unset or invalid `HOME` therefore refuses startup instead.
 */
@OptIn(ExperimentalForeignApi::class)
object LinuxIdentityPaths {

    val home: String = StateDirectory.home()

    /** The private state directory shared by preferences and settings. */
    val stateDir: String = StateDirectory.path()

    /** Secret stores: `*.prefs`, and later `*.prefs.enc`. */
    val prefsDir: String = "$stateDir/prefs"

    /** The nine non-secret stores. No secrets, but their existence proves the app has run here. */
    val settingsDir: String = "$stateDir/settings"

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
