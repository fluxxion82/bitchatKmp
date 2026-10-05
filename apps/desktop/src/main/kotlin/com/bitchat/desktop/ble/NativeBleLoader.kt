package com.bitchat.desktop.ble

import java.nio.file.Files

/**
 * Loads the native BLE shared library when enabled via -Dble.native=macos (mac host only).
 * Falls back to stub BLE if not present.
 */
object NativeBleLoader {
    private const val ENABLE_PROP = "ble.native"
    private const val EXPECTED_VALUE = "macos"
    private const val RESOURCE_PATH = "/native/macos/libbitchat_ble.dylib"
    private const val LIBPATH_PROP = "ble.native.libpath"

    @Volatile
    var loaded: Boolean = false
        private set

    fun loadIfEnabled() {
        val enabled = System.getProperty(ENABLE_PROP)?.lowercase() == EXPECTED_VALUE
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (!enabled) {
            println("NativeBleLoader: native BLE not enabled (set -D$ENABLE_PROP=$EXPECTED_VALUE to enable)")
            return
        }
        if (!isMac) {
            println("NativeBleLoader: native BLE enabled but host is not macOS; skipping")
            return
        }

        val resource = javaClass.getResourceAsStream(RESOURCE_PATH)
        if (resource == null) {
            println("NativeBleLoader: native BLE library resource not found at $RESOURCE_PATH")
            return
        }

        val tempLib = Files.createTempFile("libbitchat_ble", ".dylib").toFile()
        resource.use { input ->
            tempLib.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        tempLib.deleteOnExit()
        try {
            System.load(tempLib.absolutePath)
            loaded = true
            // Expose the loaded path so JNA bridge can bind directly
            System.setProperty(LIBPATH_PROP, tempLib.absolutePath)
            println("NativeBleLoader: loaded native BLE library from ${tempLib.absolutePath}")
        } catch (e: UnsatisfiedLinkError) {
            println("NativeBleLoader: failed to load native BLE library: ${e.message}")
        }
    }
}
