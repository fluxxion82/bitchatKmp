@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.embedded.canary

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Code shapes that an optimized Kotlin/Native build miscompiled into the embedded release binary.
 * This file only means something in the RELEASE test binary (`hostReleaseTest`), which is linked
 * with the same `embedded.kotlinNativeReleaseArgs` as the embedded release executables
 * (see gradle.properties); the debug run passes either way.
 *
 * KT-88544 (Kotlin/Native 2.4.20): the ComputeTypes pass ignored a local variable's writes that
 * reach the loop condition only through `continue`, or the code after the loop only through
 * `break`. In an optimized build, devirtualization then trusted the narrowed type and replaced
 * the `toString()` of a string template with `throw RuntimeException("Unexpected receiver type:
 * kotlin.String")`. That is how the release bitchat-embedded died right after
 * "[Main] Touch input ready": KeyboardInput.findKeyboardDevice found the CardKB.
 */
class ReleaseCompilerCanaryTest {

    /**
     * KeyboardInput.findKeyboardDevice's scan, with the evdev probes replaced by [devices] (one
     * entry per /dev/input/eventN: the device name of a keyboard, or null). Same shape: the names
     * are recorded inside memScoped right before a non-local `continue`, and only read after the
     * loop, in a string template.
     */
    private fun scanForKeyboard(devices: List<String?>): String? {
        var usbPreferredPath: String? = null
        var usbPreferredName: String? = null
        var cardKbFallbackPath: String? = null
        var cardKbFallbackName: String? = null
        for (i in devices.indices) {
            val path = "/dev/input/event$i"
            memScoped {
                val probed = devices[i]
                if (probed != null) {
                    // A name read back from native memory, as nameBuffer.toKString() does.
                    val name = probed.cstr.getPointer(this).toKString()
                    if (name.contains("CardKb", ignoreCase = true)) {
                        if (cardKbFallbackPath == null) {
                            cardKbFallbackPath = path
                            cardKbFallbackName = name
                        }
                    } else if (usbPreferredPath == null) {
                        usbPreferredPath = path
                        usbPreferredName = name
                    }
                    continue
                }
            }
        }
        if (usbPreferredPath != null) {
            return "Found keyboard device: $usbPreferredPath ($usbPreferredName)"
        }
        if (cardKbFallbackPath != null) {
            return "Found keyboard device: $cardKbFallbackPath ($cardKbFallbackName)"
        }
        return null
    }

    @Test
    fun cardKbIsReportedWithItsName() {
        assertEquals(
            "Found keyboard device: /dev/input/event1 (CardKb-I2C)",
            scanForKeyboard(listOf(null, "CardKb-I2C", null)),
        )
    }

    @Test
    fun externalKeyboardIsPreferredOverCardKb() {
        assertEquals(
            "Found keyboard device: /dev/input/event2 (USB Keyboard)",
            scanForKeyboard(listOf(null, "CardKb-I2C", "USB Keyboard")),
        )
    }

    @Test
    fun noKeyboardFindsNothing() {
        assertNull(scanForKeyboard(listOf(null, null)))
    }

    /** The same bug without memScoped: the only write is followed by `continue`. */
    private fun lastWrittenBeforeContinue(count: Int): String {
        var last: String? = null
        for (i in 0 until count) {
            last = "event$i"
            continue
        }
        return "last=$last"
    }

    /** And with `break`: the only write leaves the loop through it. */
    private fun writtenBeforeBreak(count: Int, wanted: Int): String {
        var found: String? = null
        for (i in 0 until count) {
            if (i == wanted) {
                found = "event$i"
                break
            }
        }
        return "found=$found"
    }

    @Test
    fun writeRightBeforeContinueIsSeenAfterTheLoop() {
        assertEquals("last=event2", lastWrittenBeforeContinue(3))
    }

    @Test
    fun writeRightBeforeBreakIsSeenAfterTheLoop() {
        assertEquals("found=event1", writtenBeforeBreak(3, wanted = 1))
    }
}
