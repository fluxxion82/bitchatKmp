package com.bitchat.embedded

import com.bitchat.local.statedir.StateDirectory
import com.bitchat.local.statedir.StateDirectoryException
import kotlin.system.exitProcess

/**
 * Stops an embedded app whose state directory is not its user's own: `HOME` unset, or a `~/.bitchat` that
 * another user owns or that is a link ([StateDirectory] has the rule). Both apps call this right after
 * `--version` and before the lock.
 *
 * The data layer makes the same check for itself when it first needs a directory, and that stays as the
 * last line of defence. It is made here as well so that the refusal is one plain line, before Koin, the
 * radio, Bluetooth or the display are touched, rather than an exception from the middle of startup.
 * Unlike the lock this fails closed: what it guards is where the identity keys are written.
 *
 * Neither unit lists 78 in `RestartPreventExitStatus=`, on purpose: once the directory is repaired, the
 * next restart comes up by itself.
 */
object StateDirectoryGate {
    /** EX_CONFIG of sysexits.h; systemd prints it as status=78/CONFIG. */
    const val REFUSED_EXIT_STATUS = 78

    /**
     * Returns when the state directory is usable (creating it, mode 0700, on a first run); otherwise prints
     * one line on stderr and exits with [REFUSED_EXIT_STATUS].
     */
    fun requireOrExit(
        name: String,
        ensure: () -> String = { StateDirectory.own() },
        stderr: (String) -> Unit = ::printToStandardError,
        exit: (Int) -> Unit = ::exitProcess,
    ) {
        try {
            ensure()
        } catch (e: StateDirectoryException) {
            stderr("$name: ${e.message}; not starting")
            exit(REFUSED_EXIT_STATUS)
        }
    }
}
