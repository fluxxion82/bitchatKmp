package com.bitchat.desktop.location

import java.nio.file.Files

/**
 * Loads the native Location shared library when enabled via -Dlocation.native=macos (mac host only).
 * Falls back to IP-based location if not present.
 */
object NativeLocationLoader {
    private const val ENABLE_PROP = "location.native"
    private const val EXPECTED_VALUE = "macos"
    private const val RESOURCE_PATH = "/native/macos/libbitchat_location.dylib"
    private const val LIBPATH_PROP = "location.native.libpath"

    @Volatile
    var loaded: Boolean = false
        private set

    fun loadIfEnabled() {
        val enabled = System.getProperty(ENABLE_PROP)?.lowercase() == EXPECTED_VALUE
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (!enabled) {
            println("NativeLocationLoader: native location not enabled (set -D$ENABLE_PROP=$EXPECTED_VALUE to enable)")
            return
        }
        if (!isMac) {
            println("NativeLocationLoader: native location enabled but host is not macOS; skipping")
            return
        }

        val resource = javaClass.getResourceAsStream(RESOURCE_PATH)
        if (resource == null) {
            println("NativeLocationLoader: native location library resource not found at $RESOURCE_PATH")
            return
        }

        val tempLib = Files.createTempFile("libbitchat_location", ".dylib").toFile()
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
            println("NativeLocationLoader: loaded native location library from ${tempLib.absolutePath}")
        } catch (e: UnsatisfiedLinkError) {
            println("NativeLocationLoader: failed to load native location library: ${e.message}")
        }
    }
}
