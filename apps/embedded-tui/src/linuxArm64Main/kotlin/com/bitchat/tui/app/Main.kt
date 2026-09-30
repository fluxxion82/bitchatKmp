package com.bitchat.tui.app

import com.bitchat.domain.base.LogPolicy
import com.bitchat.domain.base.invoke
import com.bitchat.embedded.BuildIdentity
import com.bitchat.tui.consoleSafeFor
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import platform.posix.fprintf
import platform.posix.stderr

/** This binary's identity; the name must match its base name in build.gradle.kts (and so its sidecar). */
private val buildIdentity = BuildIdentity("bitchat-tui")

/**
 * Entry point of the bitchat terminal UI.
 *
 * `--version` (or `-v`) prints the build identity and exits, touching nothing else, so a deploy can
 * verify the binary on the device. Otherwise, before anything prints: when standard output is a
 * terminal, stdout and stderr go to `~/.bitchat/tui.log` (see [TuiLog]; to /dev/null if that fails,
 * with a notice in the UI, and startup stops if even that fails), because the data layer's `println`s would scroll the screen under
 * Mosaic's frames (Mosaic draws on the controlling tty, not on stdout). Under systemd stdout is the
 * journal and stays there. Message bodies are kept out of either unless `BITCHAT_LOG_BODIES=1`.
 * Then Koin, the application initialisers, and Mosaic, in one `runBlocking`.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    if (args.any { it == "--version" || it == "-v" }) {
        println(buildIdentity.line)
        return
    }
    TuiLog.redirectIfTerminal() // Exits when output cannot be kept off the terminal.
    LogPolicy.configure(getenv(LogPolicy.ENV_VAR)?.toKString())
    println("=== bitchat TUI ===")
    println(buildIdentity.line)
    if (LogPolicy.messageBodies) println("${LogPolicy.ENV_VAR} is set: message bodies are logged")

    // The Linux console's fonts lack wide glyphs; decided once, here (see LocalConsoleSafe).
    val consoleSafe = consoleSafeFor(getenv("TERM")?.toKString())
    val app = TuiApplication(buildIdentity)
    // Rotation, and the Locations view model's teardown, outlive the screens that start them.
    val background = CoroutineScope(Dispatchers.Default)
    background.launch {
        while (true) {
            delay(10.seconds)
            TuiLog.rotateIfLarge()
        }
    }
    runBlocking {
        println("Application initialized; starting the terminal UI (console-safe: $consoleSafe)")
        val interactive = runBitchatTui({ app.initializeApplication() }, app::viewModels, consoleSafe, background, TuiLog.notice)
        if (!interactive) {
            fprintf(stderr, "bitchat-tui needs an interactive terminal\\n")
            exitProcess(2)
        }
    }
    // Mosaic returns on an unhandled Ctrl+C; the transports' threads would keep the process alive.
    println("Terminal UI closed")
    exitProcess(0)
}
