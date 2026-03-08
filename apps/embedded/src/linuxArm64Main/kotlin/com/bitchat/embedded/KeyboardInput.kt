@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.embedded

import androidx.compose.ui.input.key.Key
import evdev.EV_KEY
import evdev.input_event
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.ioctl
import platform.posix.open
import platform.posix.strerror

/**
 * Keyboard event data for Compose scene.
 */
data class KeyboardEventData(
    val key: Key,
    val isPressed: Boolean,
    val timeMillis: Long,
    val codePoint: Int,  // UTF-16 code point for printable chars, 0 otherwise
    val modifiers: Int,  // Bitmask of active modifiers
) {
    companion object {
        const val MOD_SHIFT = 1
        const val MOD_CTRL = 2
        const val MOD_ALT = 4
        const val MOD_META = 8
    }
}

/**
 * Reads keyboard events from an evdev input device (e.g., /dev/input/event1).
 * Processes raw input_event structs into Compose-compatible KeyboardEventData.
 *
 * Following the TouchInput pattern for device discovery and event processing.
 */
class KeyboardInput private constructor(
    val fd: Int,
    val devicePath: String,
) {
    // Modifier state tracking
    private var shiftPressed = false
    private var ctrlPressed = false
    private var altPressed = false
    private var metaPressed = false

    /**
     * Process a raw input event and return KeyboardEventData if this is a key event.
     * Returns null for non-key events.
     */
    fun process(event: input_event): KeyboardEventData? {
        if (event.type.toInt() != EV_KEY) {
            return null
        }

        val keyCode = event.code.toInt()
        val value = event.value  // 0=release, 1=press, 2=repeat
        val timeMillis = event.time.tv_sec * 1000 + event.time.tv_usec / 1000

        // Update modifier state
        when (keyCode) {
            KEY_LEFTSHIFT, KEY_RIGHTSHIFT -> {
                shiftPressed = value != 0
                return KeyboardEventData(
                    key = if (keyCode == KEY_LEFTSHIFT) Key.ShiftLeft else Key.ShiftRight,
                    isPressed = value != 0,
                    timeMillis = timeMillis,
                    codePoint = 0,
                    modifiers = currentModifiers(),
                )
            }
            KEY_LEFTCTRL, KEY_RIGHTCTRL -> {
                ctrlPressed = value != 0
                return KeyboardEventData(
                    key = if (keyCode == KEY_LEFTCTRL) Key.CtrlLeft else Key.CtrlRight,
                    isPressed = value != 0,
                    timeMillis = timeMillis,
                    codePoint = 0,
                    modifiers = currentModifiers(),
                )
            }
            KEY_LEFTALT, KEY_RIGHTALT -> {
                altPressed = value != 0
                return KeyboardEventData(
                    key = if (keyCode == KEY_LEFTALT) Key.AltLeft else Key.AltRight,
                    isPressed = value != 0,
                    timeMillis = timeMillis,
                    codePoint = 0,
                    modifiers = currentModifiers(),
                )
            }
            KEY_LEFTMETA, KEY_RIGHTMETA -> {
                metaPressed = value != 0
                return KeyboardEventData(
                    key = if (keyCode == KEY_LEFTMETA) Key.MetaLeft else Key.MetaRight,
                    isPressed = value != 0,
                    timeMillis = timeMillis,
                    codePoint = 0,
                    modifiers = currentModifiers(),
                )
            }
        }

        // Map evdev key code to Compose Key
        val composeKey = mapKeyCode(keyCode) ?: return null

        // Get printable character if applicable
        val codePoint = if (value != 0) getCodePoint(keyCode, shiftPressed) else 0

        return KeyboardEventData(
            key = composeKey,
            isPressed = value != 0,  // treat repeat (2) same as press
            timeMillis = timeMillis,
            codePoint = codePoint,
            modifiers = currentModifiers(),
        )
    }

    private fun currentModifiers(): Int {
        var mods = 0
        if (shiftPressed) mods = mods or KeyboardEventData.MOD_SHIFT
        if (ctrlPressed) mods = mods or KeyboardEventData.MOD_CTRL
        if (altPressed) mods = mods or KeyboardEventData.MOD_ALT
        if (metaPressed) mods = mods or KeyboardEventData.MOD_META
        return mods
    }

    fun close() {
        // Release the exclusive grab before closing
        memScoped {
            val grab = alloc<IntVar>()
            grab.value = 0  // 0 = release
            ioctl(fd, EVIOCGRAB.toULong(), grab.ptr)
        }
        close(fd)
        println("[Keyboard] Closed device: $devicePath")
    }

    companion object {
        // EVIOCGRAB ioctl - grabs device exclusively (prevents events going to console)
        // _IOW('E', 0x90, int) = 0x40044590
        private const val EVIOCGRAB = 0x40044590u

        // Linux evdev key codes (from linux/input-event-codes.h)
        private const val KEY_ESC = 1
        private const val KEY_1 = 2
        private const val KEY_2 = 3
        private const val KEY_3 = 4
        private const val KEY_4 = 5
        private const val KEY_5 = 6
        private const val KEY_6 = 7
        private const val KEY_7 = 8
        private const val KEY_8 = 9
        private const val KEY_9 = 10
        private const val KEY_0 = 11
        private const val KEY_MINUS = 12
        private const val KEY_EQUAL = 13
        private const val KEY_BACKSPACE = 14
        private const val KEY_TAB = 15
        private const val KEY_Q = 16
        private const val KEY_W = 17
        private const val KEY_E = 18
        private const val KEY_R = 19
        private const val KEY_T = 20
        private const val KEY_Y = 21
        private const val KEY_U = 22
        private const val KEY_I = 23
        private const val KEY_O = 24
        private const val KEY_P = 25
        private const val KEY_LEFTBRACE = 26
        private const val KEY_RIGHTBRACE = 27
        private const val KEY_ENTER = 28
        private const val KEY_LEFTCTRL = 29
        private const val KEY_A = 30
        private const val KEY_S = 31
        private const val KEY_D = 32
        private const val KEY_F = 33
        private const val KEY_G = 34
        private const val KEY_H = 35
        private const val KEY_J = 36
        private const val KEY_K = 37
        private const val KEY_L = 38
        private const val KEY_SEMICOLON = 39
        private const val KEY_APOSTROPHE = 40
        private const val KEY_GRAVE = 41
        private const val KEY_LEFTSHIFT = 42
        private const val KEY_BACKSLASH = 43
        private const val KEY_Z = 44
        private const val KEY_X = 45
        private const val KEY_C = 46
        private const val KEY_V = 47
        private const val KEY_B = 48
        private const val KEY_N = 49
        private const val KEY_M = 50
        private const val KEY_COMMA = 51
        private const val KEY_DOT = 52
        private const val KEY_SLASH = 53
        private const val KEY_RIGHTSHIFT = 54
        private const val KEY_KPASTERISK = 55
        private const val KEY_LEFTALT = 56
        private const val KEY_SPACE = 57
        private const val KEY_CAPSLOCK = 58
        private const val KEY_F1 = 59
        private const val KEY_F2 = 60
        private const val KEY_F3 = 61
        private const val KEY_F4 = 62
        private const val KEY_F5 = 63
        private const val KEY_F6 = 64
        private const val KEY_F7 = 65
        private const val KEY_F8 = 66
        private const val KEY_F9 = 67
        private const val KEY_F10 = 68
        private const val KEY_NUMLOCK = 69
        private const val KEY_SCROLLLOCK = 70
        private const val KEY_KP7 = 71
        private const val KEY_KP8 = 72
        private const val KEY_KP9 = 73
        private const val KEY_KPMINUS = 74
        private const val KEY_KP4 = 75
        private const val KEY_KP5 = 76
        private const val KEY_KP6 = 77
        private const val KEY_KPPLUS = 78
        private const val KEY_KP1 = 79
        private const val KEY_KP2 = 80
        private const val KEY_KP3 = 81
        private const val KEY_KP0 = 82
        private const val KEY_KPDOT = 83
        private const val KEY_F11 = 87
        private const val KEY_F12 = 88
        private const val KEY_KPENTER = 96
        private const val KEY_RIGHTCTRL = 97
        private const val KEY_KPSLASH = 98
        private const val KEY_RIGHTALT = 100
        private const val KEY_HOME = 102
        private const val KEY_UP = 103
        private const val KEY_PAGEUP = 104
        private const val KEY_LEFT = 105
        private const val KEY_RIGHT = 106
        private const val KEY_END = 107
        private const val KEY_DOWN = 108
        private const val KEY_PAGEDOWN = 109
        private const val KEY_INSERT = 110
        private const val KEY_DELETE = 111
        private const val KEY_LEFTMETA = 125
        private const val KEY_RIGHTMETA = 126

        /**
         * Map Linux evdev key code to Compose Key.
         */
        private fun mapKeyCode(keyCode: Int): Key? = when (keyCode) {
            // Letters
            KEY_A -> Key.A
            KEY_B -> Key.B
            KEY_C -> Key.C
            KEY_D -> Key.D
            KEY_E -> Key.E
            KEY_F -> Key.F
            KEY_G -> Key.G
            KEY_H -> Key.H
            KEY_I -> Key.I
            KEY_J -> Key.J
            KEY_K -> Key.K
            KEY_L -> Key.L
            KEY_M -> Key.M
            KEY_N -> Key.N
            KEY_O -> Key.O
            KEY_P -> Key.P
            KEY_Q -> Key.Q
            KEY_R -> Key.R
            KEY_S -> Key.S
            KEY_T -> Key.T
            KEY_U -> Key.U
            KEY_V -> Key.V
            KEY_W -> Key.W
            KEY_X -> Key.X
            KEY_Y -> Key.Y
            KEY_Z -> Key.Z

            // Numbers (top row)
            KEY_0 -> Key.Zero
            KEY_1 -> Key.One
            KEY_2 -> Key.Two
            KEY_3 -> Key.Three
            KEY_4 -> Key.Four
            KEY_5 -> Key.Five
            KEY_6 -> Key.Six
            KEY_7 -> Key.Seven
            KEY_8 -> Key.Eight
            KEY_9 -> Key.Nine

            // Function keys
            KEY_F1 -> Key.F1
            KEY_F2 -> Key.F2
            KEY_F3 -> Key.F3
            KEY_F4 -> Key.F4
            KEY_F5 -> Key.F5
            KEY_F6 -> Key.F6
            KEY_F7 -> Key.F7
            KEY_F8 -> Key.F8
            KEY_F9 -> Key.F9
            KEY_F10 -> Key.F10
            KEY_F11 -> Key.F11
            KEY_F12 -> Key.F12

            // Special keys
            KEY_ESC -> Key.Escape
            KEY_TAB -> Key.Tab
            KEY_CAPSLOCK -> Key.CapsLock
            KEY_ENTER -> Key.Enter
            KEY_KPENTER -> Key.NumPadEnter
            KEY_BACKSPACE -> Key.Backspace
            KEY_SPACE -> Key.Spacebar
            KEY_DELETE -> Key.Delete
            KEY_INSERT -> Key.Insert

            // Navigation
            KEY_UP -> Key.DirectionUp
            KEY_DOWN -> Key.DirectionDown
            KEY_LEFT -> Key.DirectionLeft
            KEY_RIGHT -> Key.DirectionRight
            KEY_HOME -> Key.MoveHome
            KEY_END -> Key.MoveEnd
            KEY_PAGEUP -> Key.PageUp
            KEY_PAGEDOWN -> Key.PageDown

            // Modifiers (already handled separately but include for completeness)
            KEY_LEFTSHIFT -> Key.ShiftLeft
            KEY_RIGHTSHIFT -> Key.ShiftRight
            KEY_LEFTCTRL -> Key.CtrlLeft
            KEY_RIGHTCTRL -> Key.CtrlRight
            KEY_LEFTALT -> Key.AltLeft
            KEY_RIGHTALT -> Key.AltRight
            KEY_LEFTMETA -> Key.MetaLeft
            KEY_RIGHTMETA -> Key.MetaRight

            // Symbols
            KEY_MINUS -> Key.Minus
            KEY_EQUAL -> Key.Equals
            KEY_LEFTBRACE -> Key.LeftBracket
            KEY_RIGHTBRACE -> Key.RightBracket
            KEY_BACKSLASH -> Key.Backslash
            KEY_SEMICOLON -> Key.Semicolon
            KEY_APOSTROPHE -> Key.Apostrophe
            KEY_GRAVE -> Key.Grave
            KEY_COMMA -> Key.Comma
            KEY_DOT -> Key.Period
            KEY_SLASH -> Key.Slash

            // Numpad
            KEY_NUMLOCK -> Key.NumLock
            KEY_KP0 -> Key.NumPad0
            KEY_KP1 -> Key.NumPad1
            KEY_KP2 -> Key.NumPad2
            KEY_KP3 -> Key.NumPad3
            KEY_KP4 -> Key.NumPad4
            KEY_KP5 -> Key.NumPad5
            KEY_KP6 -> Key.NumPad6
            KEY_KP7 -> Key.NumPad7
            KEY_KP8 -> Key.NumPad8
            KEY_KP9 -> Key.NumPad9
            KEY_KPDOT -> Key.NumPadDot
            KEY_KPASTERISK -> Key.NumPadMultiply
            KEY_KPMINUS -> Key.NumPadSubtract
            KEY_KPPLUS -> Key.NumPadAdd
            KEY_KPSLASH -> Key.NumPadDivide

            // Other
            KEY_SCROLLLOCK -> Key.ScrollLock

            else -> null
        }

        /**
         * Get UTF-16 code point for a key based on shift state (US QWERTY layout).
         * Returns 0 for non-printable keys.
         */
        private fun getCodePoint(keyCode: Int, shifted: Boolean): Int = when (keyCode) {
            // Letters
            KEY_A -> if (shifted) 'A'.code else 'a'.code
            KEY_B -> if (shifted) 'B'.code else 'b'.code
            KEY_C -> if (shifted) 'C'.code else 'c'.code
            KEY_D -> if (shifted) 'D'.code else 'd'.code
            KEY_E -> if (shifted) 'E'.code else 'e'.code
            KEY_F -> if (shifted) 'F'.code else 'f'.code
            KEY_G -> if (shifted) 'G'.code else 'g'.code
            KEY_H -> if (shifted) 'H'.code else 'h'.code
            KEY_I -> if (shifted) 'I'.code else 'i'.code
            KEY_J -> if (shifted) 'J'.code else 'j'.code
            KEY_K -> if (shifted) 'K'.code else 'k'.code
            KEY_L -> if (shifted) 'L'.code else 'l'.code
            KEY_M -> if (shifted) 'M'.code else 'm'.code
            KEY_N -> if (shifted) 'N'.code else 'n'.code
            KEY_O -> if (shifted) 'O'.code else 'o'.code
            KEY_P -> if (shifted) 'P'.code else 'p'.code
            KEY_Q -> if (shifted) 'Q'.code else 'q'.code
            KEY_R -> if (shifted) 'R'.code else 'r'.code
            KEY_S -> if (shifted) 'S'.code else 's'.code
            KEY_T -> if (shifted) 'T'.code else 't'.code
            KEY_U -> if (shifted) 'U'.code else 'u'.code
            KEY_V -> if (shifted) 'V'.code else 'v'.code
            KEY_W -> if (shifted) 'W'.code else 'w'.code
            KEY_X -> if (shifted) 'X'.code else 'x'.code
            KEY_Y -> if (shifted) 'Y'.code else 'y'.code
            KEY_Z -> if (shifted) 'Z'.code else 'z'.code

            // Numbers and their shifted symbols (US QWERTY)
            KEY_1 -> if (shifted) '!'.code else '1'.code
            KEY_2 -> if (shifted) '@'.code else '2'.code
            KEY_3 -> if (shifted) '#'.code else '3'.code
            KEY_4 -> if (shifted) '$'.code else '4'.code
            KEY_5 -> if (shifted) '%'.code else '5'.code
            KEY_6 -> if (shifted) '^'.code else '6'.code
            KEY_7 -> if (shifted) '&'.code else '7'.code
            KEY_8 -> if (shifted) '*'.code else '8'.code
            KEY_9 -> if (shifted) '('.code else '9'.code
            KEY_0 -> if (shifted) ')'.code else '0'.code

            // Symbols
            KEY_MINUS -> if (shifted) '_'.code else '-'.code
            KEY_EQUAL -> if (shifted) '+'.code else '='.code
            KEY_LEFTBRACE -> if (shifted) '{'.code else '['.code
            KEY_RIGHTBRACE -> if (shifted) '}'.code else ']'.code
            KEY_BACKSLASH -> if (shifted) '|'.code else '\\'.code
            KEY_SEMICOLON -> if (shifted) ':'.code else ';'.code
            KEY_APOSTROPHE -> if (shifted) '"'.code else '\''.code
            KEY_GRAVE -> if (shifted) '~'.code else '`'.code
            KEY_COMMA -> if (shifted) '<'.code else ','.code
            KEY_DOT -> if (shifted) '>'.code else '.'.code
            KEY_SLASH -> if (shifted) '?'.code else '/'.code

            // Special printable
            KEY_SPACE -> ' '.code
            KEY_TAB -> '\t'.code
            KEY_ENTER, KEY_KPENTER -> '\n'.code

            // Numpad (no shift variants)
            KEY_KP0 -> '0'.code
            KEY_KP1 -> '1'.code
            KEY_KP2 -> '2'.code
            KEY_KP3 -> '3'.code
            KEY_KP4 -> '4'.code
            KEY_KP5 -> '5'.code
            KEY_KP6 -> '6'.code
            KEY_KP7 -> '7'.code
            KEY_KP8 -> '8'.code
            KEY_KP9 -> '9'.code
            KEY_KPDOT -> '.'.code
            KEY_KPASTERISK -> '*'.code
            KEY_KPMINUS -> '-'.code
            KEY_KPPLUS -> '+'.code
            KEY_KPSLASH -> '/'.code

            else -> 0
        }

        /**
         * Find the first available keyboard device by scanning /dev/input/event*.
         * Returns the device path or null if none found.
         *
         * Looks for devices that have EV_KEY capability with alphabetic keys,
         * distinguishing keyboards from mice (which only have button keys).
         */
        fun findKeyboardDevice(): String? {
            var usbPreferredPath: String? = null
            var usbPreferredName: String? = null
            var cardKbFallbackPath: String? = null
            var cardKbFallbackName: String? = null
            for (i in 0..15) {
                val path = "/dev/input/event$i"
                val fd = open(path, O_RDONLY or O_NONBLOCK)
                if (fd < 0) continue

                memScoped {
                    val evBits = allocArray<ULongVar>(1)
                    val EVIOCGBIT_0 = 0x80084520u  // EVIOCGBIT(0, sizeof(evBits))

                    if (ioctl(fd, EVIOCGBIT_0.toULong(), evBits) >= 0) {
                        val hasKey = (evBits[0].toLong() and (1L shl EV_KEY)) != 0L
                        if (hasKey) {
                            // Check for alphabetic key capability (KEY_A = 30)
                            // EVIOCGBIT(EV_KEY, KEY_MAX/8) to get key bits
                            // We need enough bytes to cover KEY_A (30) = 4 bytes minimum
                            val keyBits = allocArray<ULongVar>(8)  // 512 bits = covers all keys
                            val EVIOCGBIT_KEY = 0x80404521u  // EVIOCGBIT(EV_KEY, 64)

                            if (ioctl(fd, EVIOCGBIT_KEY.toULong(), keyBits) >= 0) {
                                // Check if KEY_A (30) is supported
                                // bit 30 is in keyBits[0], at position 30
                                val hasKeyA = (keyBits[0].toLong() and (1L shl KEY_A)) != 0L
                                if (hasKeyA) {
                                    // Get device name for logging
                                    val nameBuffer = allocArray<ByteVar>(256)
                                    val EVIOCGNAME_256 = 0x82004506u
                                    val name = if (ioctl(fd, EVIOCGNAME_256.toULong(), nameBuffer) >= 0) {
                                        nameBuffer.toKString()
                                    } else {
                                        "unknown"
                                    }

                                    // Prefer external keyboards (USB/Bluetooth/etc.) when present.
                                    // Keep CardKB as fallback so built-in input still works.
                                    if (name.contains("CardKb", ignoreCase = true)) {
                                        if (cardKbFallbackPath == null) {
                                            cardKbFallbackPath = path
                                            cardKbFallbackName = name
                                        }
                                    } else if (usbPreferredPath == null) {
                                        usbPreferredPath = path
                                        usbPreferredName = name
                                    }

                                    close(fd)
                                    continue
                                }
                            }
                        }
                    }
                }
                close(fd)
            }
            if (usbPreferredPath != null) {
                println("[Keyboard] Found keyboard device: $usbPreferredPath ($usbPreferredName)")
                return usbPreferredPath
            }
            if (cardKbFallbackPath != null) {
                println("[Keyboard] Found keyboard device: $cardKbFallbackPath ($cardKbFallbackName)")
                return cardKbFallbackPath
            }
            println("[Keyboard] No keyboard device found")
            return null
        }

        /**
         * Open a keyboard input device.
         * @param path Device path like "/dev/input/event1"
         * @return KeyboardInput instance, or null if open fails
         */
        fun open(path: String): KeyboardInput? {
            val fd = open(path, O_RDONLY or O_NONBLOCK)
            if (fd < 0) {
                println("[Keyboard] Failed to open $path: ${strerror(errno)?.toKString()}")
                return null
            }

            // Query device name
            memScoped {
                val nameBuffer = allocArray<ByteVar>(256)
                val EVIOCGNAME_256 = 0x82004506u
                if (ioctl(fd, EVIOCGNAME_256.toULong(), nameBuffer) >= 0) {
                    val name = nameBuffer.toKString()
                    println("[Keyboard] Opened device: $path")
                    println("[Keyboard] Device name: $name")
                } else {
                    println("[Keyboard] Opened device: $path (couldn't get name)")
                }

                // Grab the device exclusively to prevent events from going to the console
                val grab = alloc<IntVar>()
                grab.value = 1  // 1 = grab, 0 = release
                if (ioctl(fd, EVIOCGRAB.toULong(), grab.ptr) < 0) {
                    println("[Keyboard] Warning: Failed to grab device exclusively: ${strerror(errno)?.toKString()}")
                    println("[Keyboard] Keyboard input may also appear on console")
                } else {
                    println("[Keyboard] Device grabbed exclusively (console input disabled)")
                }
            }

            return KeyboardInput(fd, path)
        }
    }
}
