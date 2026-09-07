@file:OptIn(
    ExperimentalForeignApi::class,
    InternalComposeUiApi::class,
)

package com.bitchat.embedded

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ComposeUiMainDispatcher
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.bitchat.bluetooth.di.bluetoothModule
import com.bitchat.client.di.clientModule
import com.bitchat.design.BitchatTheme
import com.bitchat.domain.app.model.AppTheme
import com.bitchat.domain.base.invoke
import com.bitchat.domain.di.domainModule
import com.bitchat.domain.initialization.InitializeApplication
import com.bitchat.embedded.di.buildConfigModule
import com.bitchat.embedded.di.LoRaProtocolSelector
import com.bitchat.local.di.commonLocal
import com.bitchat.local.di.localModule
import com.bitchat.lora.bitchat.di.bitChatLoraModule
import com.bitchat.lora.di.loraProtocolManagerModule
import com.bitchat.lora.meshcore.di.meshcoreLoraModule
import com.bitchat.lora.meshtastic.di.meshtasticLoraModule
import com.bitchat.nostr.di.nostrModule
import com.bitchat.repo.di.commonRepoModule
import com.bitchat.repo.di.repoModule
import com.bitchat.screens.BitchatGraph
import com.bitchat.tor.di.torModule
import com.bitchat.viewmodel.di.viewModelModule
import com.bitchat.viewmodel.main.MainViewModel
import drm.drmEventContext
import drm.drmHandleEvent
import drm.drm_event_context_version
import evdev.input_event
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.fd_set
import platform.posix.getenv
import platform.posix.read
import platform.posix.select
import platform.posix.strerror
import select.select_fd_isset
import select.select_fd_set
import select.select_fd_zero
import kotlin.concurrent.AtomicReference

/**
 * App class for Koin dependency injection.
 */
class App : KoinComponent {
    val initializeApplication: InitializeApplication by inject()
    val mainViewModel: MainViewModel by inject()

    init {
        // Get preferred protocol for initial selection
        val initialProtocol = LoRaProtocolSelector.getPreferredProtocol()

        startKoin {
            modules(
                buildConfigModule,
                domainModule,
                commonLocal,
                localModule,
                clientModule,
                commonRepoModule,
                repoModule,
                viewModelModule,
                nostrModule,
                bluetoothModule,
                // Load all LoRa protocol modules
                bitChatLoraModule,
                meshtasticLoraModule,
                meshcoreLoraModule,
                // Protocol manager for runtime switching
                loraProtocolManagerModule(initialProtocol),
                torModule,
            )
        }
    }
}

/**
 * Entry point for the embedded bitchat application.
 *
 * `--version` (or `-v`) prints the build identity and exits without touching DRM,
 * so a deploy script can verify a binary on the device. Everything else runs the app.
 */
fun main(args: Array<String>) {
    if (args.any { it == "--version" || it == "-v" }) {
        println(BuildIdentity.line)
        return
    }
    runApp()
}

/**
 * Runs the embedded bitchat application.
 *
 * Phase 3: Event-driven rendering via DRM page flips.
 * Uses select() to block until events arrive (touch input or page flip completion),
 * significantly reducing power consumption when UI is idle.
 *
 */
@OptIn(ExperimentalFoundationApi::class, InternalCoroutinesApi::class)
private fun runApp() = memScoped {
    val mainDispatcher = FlushCoroutineDispatcher()
    ComposeUiMainDispatcher = mainDispatcher

    println("=== Bitchat Embedded ===")
    println(BuildIdentity.line)
    println("Initializing application...")

    // Initialize Koin and app
    val app = App()
    runBlocking {
        app.initializeApplication()
    }
    println("Application initialized")

    println("Setting up display...")
    val drm = Drm.initialize()
    val width = drm.mode.width
    val height = drm.mode.height
    val gbm = Gbm.initialize(drm)
    val egl = Egl.initialize(gbm)
    val renderer = Renderer.initialize(width, height)
    val touchDevicePath = TouchInput.findTouchDevice()
    val touchInput = touchDevicePath?.let { TouchInput.open(it, width, height) }
    if (touchInput != null) {
        println("[Main] Touch input ready (fd=${touchInput.fd})")
    } else {
        println("[Main] No touch device found (touch disabled)")
    }

    // Read once at startup, and only for a debug binary: BuildIdentity.isDebug is checked first
    // so no value of BITCHAT_INPUT_DEBUG can make a release binary log key events at all.
    val keyLogger = KeyLogger(
        if (BuildIdentity.isDebug) {
            when (getenv("BITCHAT_INPUT_DEBUG")?.toKString()) {
                "keys" -> KeyLogMode.KEYS
                "classes" -> KeyLogMode.CLASSES
                else -> KeyLogMode.FIRST_ONLY
            }
        } else {
            KeyLogMode.OFF
        },
    )
    val keyboardDevicePath = KeyboardInput.findKeyboardDevice()
    val keyboardInput = keyboardDevicePath?.let { KeyboardInput.open(it) }
    if (keyboardInput != null) {
        println("[Main] Keyboard input ready (fd=${keyboardInput.fd})")
        when (keyLogger.mode) {
            KeyLogMode.OFF -> Unit
            KeyLogMode.FIRST_ONLY -> println(
                "[Keyboard] key event logging: first key only (debug binary; " +
                    "set BITCHAT_INPUT_DEBUG=classes or =keys for per-key logging)",
            )
            KeyLogMode.CLASSES -> println(
                "[Keyboard] key event logging: classes (debug binary; BITCHAT_INPUT_DEBUG=classes, " +
                    "key classes only, never which key)",
            )
            KeyLogMode.KEYS -> println(
                "[Keyboard] key event logging: keys (debug binary; BITCHAT_INPUT_DEBUG=keys: " +
                    "logging key identities, do not type secrets)",
            )
        }
    } else {
        println("[Main] No keyboard device found (keyboard disabled)")
    }

    val stateRef = AtomicReference<State?>(null)
    val scene = CanvasLayersComposeScene(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        size = IntSize(width, height),
        coroutineContext = ComposeUiMainDispatcher,
        platformContext = PlatformContext.Empty(),
        invalidate = {
            stateRef.value?.requestRender()
        },
    )
    scene.setContent {
        val mainViewModel = app.mainViewModel
        val appTheme by mainViewModel.appTheme.collectAsState()
        println("app theme: $appTheme")
        val isDarkTheme = when (appTheme) {
            AppTheme.SYSTEM -> isSystemInDarkTheme()
            AppTheme.LIGHT -> false
            AppTheme.DARK -> true
        }

        BitchatTheme(darkTheme = isDarkTheme) {
            BitchatGraph(app.mainViewModel)
        }
    }

    println("[Main] Compose scene created (${width}x${height})")

    val state = State(
        drm = drm,
        gbm = gbm,
        egl = egl,
        renderer = renderer,
        scene = scene,
        touchInput = touchInput,
        keyboardInput = keyboardInput,
        mainDispatcher = mainDispatcher,
    )
    stateRef.value = state

    val eventContext = alloc<drmEventContext>()
    eventContext.version = drm_event_context_version()
    eventContext.page_flip_handler = staticCFunction { fd, sequence, tvSec, tvUsec, userData ->
        pageFlipHandler(fd, sequence, tvSec, tvUsec, userData)
    }

    println("[Main] Rendering initial frame...")
    initialRender(state)

    println("[Main] Entering event-driven loop (power-efficient mode)")
    val fds = alloc<fd_set>()
    val event = alloc<input_event>()
    val eventSize = sizeOf<input_event>().convert<ULong>()

    val maxFd = maxOf(drm.fd, touchInput?.fd ?: 0, keyboardInput?.fd ?: 0) + 1

    while (true) {
        select_fd_zero(fds.ptr)
        select_fd_set(drm.fd, fds.ptr)
        touchInput?.let { select_fd_set(it.fd, fds.ptr) }
        keyboardInput?.let { select_fd_set(it.fd, fds.ptr) }

        val result = select(maxFd, fds.ptr, null, null, null)
        if (result < 0) {
            val err = errno
            if (err == EINTR) continue // Interrupted by signal, retry
            println("[Main] select error: ${strerror(err)?.toKString()}")
            break
        }

        if (touchInput != null && select_fd_isset(touchInput.fd, fds.ptr) != 0) {
            processTouchEvents(touchInput, scene, event, eventSize)
        }

        if (keyboardInput != null && select_fd_isset(keyboardInput.fd, fds.ptr) != 0) {
            processKeyboardEvents(keyboardInput, scene, event, eventSize, keyLogger)
        }

        // Flush pending Compose tasks before checking render needs
        // This ensures all recomposition happens on the main thread
        state.mainDispatcher.flush()

        if (select_fd_isset(drm.fd, fds.ptr) != 0) {
            drmHandleEvent(drm.fd, eventContext.ptr)
        }
    }

    state.cleanup()
}

/**
 * Process all available touch events from evdev and dispatch to Compose scene.
 * Called when select() indicates the touch fd is ready.
 */
private fun processTouchEvents(
    touchInput: TouchInput,
    scene: ComposeScene,
    event: input_event,
    eventSize: ULong,
) {
    while (true) {
        val bytesRead = read(touchInput.fd, event.ptr, eventSize)
        if (bytesRead < eventSize.toLong()) {
            break
        }

        val touchEvent = touchInput.process(event) ?: continue
        scene.sendPointerEvent(
            eventType = touchEvent.eventType,
            position = touchEvent.position,
            timeMillis = touchEvent.timeMillis,
            type = PointerType.Touch,
        )
    }
}

/**
 * Process all available keyboard events from evdev and dispatch to Compose scene.
 * Called when select() indicates the keyboard fd is ready.
 */
private fun processKeyboardEvents(
    keyboardInput: KeyboardInput,
    scene: ComposeScene,
    event: input_event,
    eventSize: ULong,
    keyLogger: KeyLogger,
) {
    while (true) {
        val bytesRead = read(keyboardInput.fd, event.ptr, eventSize)
        if (bytesRead < eventSize.toLong()) {
            break
        }

        val keyEvent = keyboardInput.process(event) ?: continue

        // Create Compose KeyEvent and dispatch to scene
        val eventType = if (keyEvent.isPressed) KeyEventType.KeyDown else KeyEventType.KeyUp
        val composeKeyEvent = KeyEvent(
            key = keyEvent.key,
            type = eventType,
            codePoint = keyEvent.codePoint,
            isShiftPressed = (keyEvent.modifiers and KeyboardEventData.MOD_SHIFT) != 0,
            isCtrlPressed = (keyEvent.modifiers and KeyboardEventData.MOD_CTRL) != 0,
            isAltPressed = (keyEvent.modifiers and KeyboardEventData.MOD_ALT) != 0,
            isMetaPressed = (keyEvent.modifiers and KeyboardEventData.MOD_META) != 0,
        )

        val consumed = scene.sendKeyEvent(composeKeyEvent)
        // Key-down only (repeat counts as down); the logger itself decides how much to say.
        if (keyEvent.isPressed) {
            keyLogger.onKeyDown(keyEvent, consumed)
        }
    }
}

/**
 * How much a debug binary writes to the journal for each key-down, chosen once at startup from
 * `BITCHAT_INPUT_DEBUG`. A release binary is always [OFF].
 */
private enum class KeyLogMode {
    /** Release binaries: nothing at all. */
    OFF,

    /** Default: a single line on the very first key-down, then silence. */
    FIRST_ONLY,

    /** `BITCHAT_INPUT_DEBUG=classes`: one key-class line per key-down. */
    CLASSES,

    /** `BITCHAT_INPUT_DEBUG=keys`: full key identities. Keylogs everything typed. */
    KEYS,
}

/**
 * Keyboard journal logging.
 *
 * The journal is persistent, so per-key logging is opt-in and never the shipped default: even a
 * class-only trace leaks message and password length, the capitalisation pattern and the
 * inter-keystroke timing, and [KeyLogMode.KEYS] leaks the typed text itself. The default only
 * confirms once that key events are arriving at all, which is what the diagnostics needed.
 */
private class KeyLogger(val mode: KeyLogMode) {
    private var firstLogged = false

    fun onKeyDown(keyEvent: KeyboardEventData, consumed: Boolean) {
        when (mode) {
            KeyLogMode.OFF -> Unit
            KeyLogMode.FIRST_ONLY -> {
                if (firstLogged) return
                firstLogged = true
                println(
                    "[Keyboard] first key event received (class=${keyClassOf(keyEvent)}, " +
                        "consumed=$consumed); per-key logging off " +
                        "(set BITCHAT_INPUT_DEBUG=classes or =keys)",
                )
            }
            KeyLogMode.CLASSES -> println("[Keyboard] class=${keyClassOf(keyEvent)} consumed=$consumed")
            KeyLogMode.KEYS -> println(
                "[Keyboard] evdev=${keyEvent.keyCode} key=${keyEvent.key} " +
                    "codePoint=${keyEvent.codePoint} mods=${keyEvent.modifiers} consumed=$consumed",
            )
        }
    }
}

/**
 * Coarse key class for the class-level debug log: "modifier" for Shift/Ctrl/Alt/Meta keys,
 * "control" for keys without a code point or with an ISO control code point
 * (Enter, Backspace, Tab, arrows, ...), "printable" for everything else.
 */
private fun keyClassOf(keyEvent: KeyboardEventData): String = when {
    keyEvent.key == Key.ShiftLeft || keyEvent.key == Key.ShiftRight ||
        keyEvent.key == Key.CtrlLeft || keyEvent.key == Key.CtrlRight ||
        keyEvent.key == Key.AltLeft || keyEvent.key == Key.AltRight ||
        keyEvent.key == Key.MetaLeft || keyEvent.key == Key.MetaRight -> "modifier"
    keyEvent.codePoint == 0 ||
        keyEvent.codePoint in 0x00..0x1F ||
        keyEvent.codePoint in 0x7F..0x9F -> "control"
    else -> "printable"
}
