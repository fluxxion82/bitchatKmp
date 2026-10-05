package com.bitchat.desktop.tui

import com.bitchat.desktop.SingleInstanceLock
import com.bitchat.desktop.ble.NativeBleLoader
import com.bitchat.desktop.di.LoRaProtocolSelector
import com.bitchat.desktop.location.NativeLocationLoader
import com.bitchat.domain.base.LogPolicy
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.tui.app.runBitchatTui
import com.bitchat.tui.app.tuiViewModels
import com.bitchat.tui.consoleSafeFor
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import sun.misc.Signal

private object CodeSourceMarker

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    if (args.any { it == "--version" || it == "-v" }) {
        println(desktopTuiVersionLine())
        return
    }
    val hangupNote = exitOnTerminalHangup()
    // Before the lock, Koin, Tor or any relay: a run with no terminal must not start (or touch) anything.
    if (!hasControllingTerminal()) {
        System.err.println("bitchat-tui needs an interactive terminal")
        exitProcess(2)
    }
    checkNotNull(SingleInstanceLock.acquireOrExit())
    runGuarded(
        block = {
            JvmTuiLog.redirectIfTerminal()
            LogPolicy.configure(System.getenv(LogPolicy.ENV_VAR))
            println("=== bitchat TUI ===")
            println(desktopTuiVersionLine())
            println("Desktop TUI startup: isHeadless=${GraphicsEnvironment.isHeadless()}")
            println("Desktop TUI startup: SIGHUP exits with 129 ($hangupNote)")
            configureArtiPath()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                JvmTuiLog.reportOriginal("bitchat-tui background crash (${thread.name}): ${error::class.simpleName}")
                error.printStackTrace()
            }
            NativeBleLoader.loadIfEnabled()
            NativeLocationLoader.loadIfEnabled()
            val koin = startKoin {
                modules(
                    desktopTuiDataModules(LoRaProtocolSelector.getPreferredProtocol()),
                )
            }.koin
            val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            background.launch {
                while (true) {
                    delay(10.seconds)
                    JvmTuiLog.rotateIfLarge()
                }
            }
            val interactive = runBlocking {
                runBitchatTui(
                    initialize = { koin.get<InitializeApplication>()(Unit) },
                    viewModels = { koin.tuiViewModels() },
                    consoleSafe = consoleSafeFor(System.getenv("TERM"), forced = false),
                    background = background,
                    notice = JvmTuiLog.notice,
                )
            }
            if (!interactive) {
                JvmTuiLog.reportOriginal("bitchat-tui needs an interactive terminal")
                exitProcess(2)
            }
        },
        report = { error -> JvmTuiLog.reportOriginal("bitchat-tui: ${error::class.simpleName}: ${error.message.orEmpty()}") },
        exit = ::exitProcess,
    )
}

private fun hasControllingTerminal(): Boolean = try {
    JvmTuiLog.hasControllingTerminal()
} catch (error: Throwable) {
    System.err.println("bitchat-tui: cannot check for a terminal: ${error.message.orEmpty()}")
    exitProcess(1)
}

/** Pure top-level failure policy; injected exit/report make the shutdown path directly testable. */
internal fun runGuarded(block: () -> Unit, report: (Throwable) -> Unit, exit: (Int) -> Unit) {
    try {
        block()
        exit(0)
    } catch (error: Throwable) {
        report(error)
        exit(1)
    }
}

/**
 * jSerialComm's native library, loaded by the static initialiser of `SerialPort` (which the desktop LoRa modules
 * reach), sets SIGHUP to SIG_IGN for the whole process. Closing the terminal window would then leave this JVM
 * running headless with the single-instance lock held. So force that initialisation here, before anything else
 * can trigger it, and then restore SIGHUP handling. The JVM refuses to install a handler over an ignored
 * HUP/INT/TERM (JVM_RegisterSignal returns without changing anything), so the disposition goes back to SIG_DFL
 * through libc first; only then does `Signal.handle` install the JVM's own handler. Exiting runs the shutdown
 * hooks, which is how Mosaic restores the terminal (the same path SIGTERM takes; jSerialComm leaves SIGTERM alone).
 * Returns a note for the startup log (nothing may be printed yet: stdout is not redirected to the log).
 */
private fun exitOnTerminalHangup(): String {
    val initialised = try {
        Class.forName("com.fazecast.jSerialComm.SerialPort")
        "jSerialComm initialised first"
    } catch (error: Throwable) {
        "jSerialComm not initialised: ${error::class.simpleName}"
    }
    return try {
        Native.load("c", SignalLibrary::class.java).signal(SIGHUP, null)
        Signal.handle(Signal("HUP")) {
            // Mosaic's shutdown hook waits for the tty to drain, which never ends on a terminal that is gone but
            // not closed. A hangup must not leave the process (and its single-instance lock) alive, so halt
            // if the orderly exit has not finished in time.
            thread(isDaemon = true, name = "hangup-watchdog") {
                Thread.sleep(HANGUP_GRACE_MILLIS)
                Runtime.getRuntime().halt(129)
            }
            exitProcess(129)
        }
        initialised
    } catch (error: Throwable) {
        "$initialised; SIGHUP handler NOT installed: ${error::class.simpleName}"
    }
}

private const val SIGHUP = 1
private const val HANGUP_GRACE_MILLIS = 5_000L

private interface SignalLibrary : Library {
    fun signal(signal: Int, handler: Pointer?): Pointer?
}

private fun configureArtiPath() {
    val property = "compose.application.resources.dir"
    if (System.getProperty(property) != null) return
    val jar = CodeSourceMarker::class.java.protectionDomain.codeSource.location.toURI().let { java.nio.file.Path.of(it) }
    val native = jar.parent.resolve("native")
    val library = native.resolve(System.mapLibraryName("arti_desktop"))
    if (Files.isRegularFile(library)) {
        System.setProperty(property, native.toString())
        println("Desktop TUI startup: Arti native directory $native")
    } else {
        println("Desktop TUI startup: Arti native library not packaged at $library")
    }
}
