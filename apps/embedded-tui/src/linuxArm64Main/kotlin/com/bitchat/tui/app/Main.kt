package com.bitchat.tui.app

import androidx.compose.runtime.CompositionLocalProvider
import com.bitchat.domain.base.LogPolicy
import com.bitchat.domain.base.invoke
import com.bitchat.embedded.BuildIdentity
import com.bitchat.tui.LocalConsoleSafe
import com.bitchat.tui.consoleSafeFor
import com.jakewharton.mosaic.RenderMode
import com.jakewharton.mosaic.runMosaic
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
        app.initializeApplication()
        // Created once, outside the composition, so no recomposition can make a second set.
        val viewModels = app.viewModels()
        println("Application initialized; starting the terminal UI (console-safe: $consoleSafe)")
        // The alternate screen: the app owns the terminal, a frame may use every row, and only the
        // cells that changed are sent, which is what makes it usable over a slow link.
        runMosaic(renderMode = RenderMode.FullScreen) {
            CompositionLocalProvider(LocalConsoleSafe provides consoleSafe) {
                BitchatTui(viewModels, background, TuiLog.notice)
            }
        }
    }
    // Mosaic returns on an unhandled Ctrl+C; the transports' threads would keep the process alive.
    println("Terminal UI closed")
    exitProcess(0)
}
