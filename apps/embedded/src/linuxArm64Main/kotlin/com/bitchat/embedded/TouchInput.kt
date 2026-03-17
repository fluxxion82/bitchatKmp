@file:OptIn(ExperimentalForeignApi::class)

package com.bitchat.embedded

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import evdev.ABS_MT_POSITION_X
import evdev.ABS_MT_POSITION_Y
import evdev.ABS_X
import evdev.ABS_Y
import evdev.BTN_TOUCH
import evdev.EV_ABS
import evdev.EV_KEY
import evdev.EV_SYN
import evdev.SYN_REPORT
import evdev.input_event
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.ioctl
import platform.posix.open
import platform.posix.strerror

/**
 * Touch event data for Compose scene.
 */
data class TouchEvent(
    val eventType: PointerEventType,
    val position: Offset,
    val timeMillis: Long,
)

/**
 * Calibration data for a touch axis.
 */
data class AxisCalibration(
    val min: Int,
    val max: Int,
    val screenSize: Int,
) {
    /**
     * Scale a raw touch value to screen coordinates.
     * Clamps to valid range and applies linear interpolation.
     */
    fun scale(raw: Int): Float {
        val clamped = raw.coerceIn(min, max)
        val normalized = (clamped - min).toFloat() / (max - min).toFloat()
        return normalized * screenSize
    }
}

/**
 * Reads touch events from an evdev input device (e.g., /dev/input/event0).
 * Processes raw input_event structs into Compose-compatible TouchEvent.
 *
 * Includes calibration to map raw ADC values to screen coordinates.
 */
class TouchInput private constructor(
    val fd: Int,
    val devicePath: String,
    private val xCalibration: AxisCalibration,
    private val yCalibration: AxisCalibration,
) {
    // Touch state machine
    private var isTouching = false       // Currently in touch gesture
    private var needsPress = false       // Need to send Press event
    private var needsRelease = false     // Need to send Release event
    private var nextX = 0
    private var nextY = 0

    // For filtering jitter and improving tap detection
    private var pressX = 0f              // Screen X at PRESS
    private var pressY = 0f              // Screen Y at PRESS
    private var lastSentX = 0f           // Last sent screen X
    private var lastSentY = 0f           // Last sent screen Y
    private var hasMoved = false         // True if significant movement occurred

    /**
     * Process a raw input event and return a TouchEvent if this completes a gesture.
     * Returns null for intermediate events (position updates without sync).
     */
    fun process(event: input_event): TouchEvent? {
        when (event.type.toInt()) {
            EV_ABS -> {
                when (event.code.toInt()) {
                    ABS_MT_POSITION_X, ABS_X -> {
                        nextX = event.value
                    }
                    ABS_MT_POSITION_Y, ABS_Y -> {
                        nextY = event.value
                    }
                }
            }

            EV_KEY -> {
                when (event.code.toInt()) {
                    BTN_TOUCH -> {
                        if (event.value == 1) {
                            // Touch started
                            needsPress = true
                            needsRelease = false
                        } else if (event.value == 0) {
                            // Touch ended
                            needsRelease = true
                        }
                    }
                }
            }

            EV_SYN -> {
                if (event.code.toInt() == SYN_REPORT) {
                    // Scale raw touch values to screen coordinates using calibration
                    val screenX = xCalibration.scale(nextX)
                    val screenY = yCalibration.scale(nextY)
                    val timeMillis = event.time.tv_sec * 1000 + event.time.tv_usec / 1000

                    when {
                        needsPress -> {
                            needsPress = false
                            isTouching = true
                            hasMoved = false
                            // Store press position for jitter filtering
                            pressX = screenX
                            pressY = screenY
                            lastSentX = screenX
                            lastSentY = screenY

                            println("[Touch] PRESS: raw=($nextX, $nextY) -> screen=(${screenX.toInt()}, ${screenY.toInt()})")
                            return TouchEvent(
                                eventType = PointerEventType.Press,
                                position = Offset(screenX, screenY),
                                timeMillis = timeMillis,
                            )
                        }
                        needsRelease -> {
                            needsRelease = false
                            isTouching = false
                            // For taps (no significant movement), use the press position
                            // This ensures the release happens at the same spot as press
                            val releaseX = if (hasMoved) screenX else pressX
                            val releaseY = if (hasMoved) screenY else pressY

                            println("[Touch] RELEASE: hasMoved=$hasMoved -> screen=(${releaseX.toInt()}, ${releaseY.toInt()})")
                            return TouchEvent(
                                eventType = PointerEventType.Release,
                                position = Offset(releaseX, releaseY),
                                timeMillis = timeMillis,
                            )
                        }
                        isTouching -> {
                            // Filter out small movements (jitter) that would confuse Compose's tap detection
                            val dx = screenX - lastSentX
                            val dy = screenY - lastSentY
                            val distance = kotlin.math.sqrt(dx * dx + dy * dy)

                            if (distance >= MOVE_THRESHOLD) {
                                hasMoved = true
                                lastSentX = screenX
                                lastSentY = screenY

                                println("[Touch] MOVE: raw=($nextX, $nextY) -> screen=(${screenX.toInt()}, ${screenY.toInt()}) dist=${distance.toInt()}")
                                return TouchEvent(
                                    eventType = PointerEventType.Move,
                                    position = Offset(screenX, screenY),
                                    timeMillis = timeMillis,
                                )
                            }
                            // Small movement - don't send event, just absorb the jitter
                        }
                    }
                }
            }
        }

        return null
    }

    fun close() {
        close(fd)
        println("[Touch] Closed device: $devicePath")
    }

    companion object {
        // Dead zone threshold in pixels - ignore movements smaller than this
        private const val MOVE_THRESHOLD = 8f

        /**
         * Open a touch input device with calibration for the given screen size.
         * @param path Device path like "/dev/input/event0"
         * @param screenWidth Screen width in pixels
         * @param screenHeight Screen height in pixels
         * @return TouchInput instance, or null if open fails
         */
        fun open(path: String, screenWidth: Int, screenHeight: Int): TouchInput? {
            val fd = open(path, O_RDONLY or O_NONBLOCK)
            if (fd < 0) {
                println("[Touch] Failed to open $path: ${strerror(errno)?.toKString()}")
                return null
            }

            // Query device name
            memScoped {
                val nameBuffer = allocArray<ByteVar>(256)
                // EVIOCGNAME ioctl to get device name
                val EVIOCGNAME_256 = 0x82004506u // EVIOCGNAME(256)
                if (ioctl(fd, EVIOCGNAME_256.toULong(), nameBuffer) >= 0) {
                    val name = nameBuffer.toKString()
                    println("[Touch] Opened device: $path")
                    println("[Touch] Device name: $name")
                } else {
                    println("[Touch] Opened device: $path (couldn't get name)")
                }

                // Query axis calibration using EVIOCGABS
                // struct input_absinfo { value, minimum, maximum, fuzz, flat, resolution }
                // EVIOCGABS(axis) = _IOR('E', 0x40 + axis, struct input_absinfo)
                // For ABS_X (0x00): 0x80184540
                // For ABS_Y (0x01): 0x80184541
                val absInfo = allocArray<IntVar>(6) // input_absinfo has 6 int fields

                val EVIOCGABS_X = 0x80184540u
                val EVIOCGABS_Y = 0x80184541u

                var xMin = 0
                var xMax = screenWidth
                var yMin = 0
                var yMax = screenHeight

                val xResult = ioctl(fd, EVIOCGABS_X.toULong(), absInfo)
                if (xResult >= 0) {
                    xMin = absInfo[1]  // minimum
                    xMax = absInfo[2]  // maximum
                    println("[Touch] X axis: min=$xMin, max=$xMax (ioctl returned $xResult)")
                } else {
                    println("[Touch] Warning: Couldn't query X axis range (ioctl returned $xResult, errno=${errno}), using screen size")
                }

                val yResult = ioctl(fd, EVIOCGABS_Y.toULong(), absInfo)
                if (yResult >= 0) {
                    yMin = absInfo[1]  // minimum
                    yMax = absInfo[2]  // maximum
                    println("[Touch] Y axis: min=$yMin, max=$yMax (ioctl returned $yResult)")
                } else {
                    println("[Touch] Warning: Couldn't query Y axis range (ioctl returned $yResult, errno=${errno}), using screen size")
                }

                println("[Touch] Calibration: X[$xMin..$xMax] -> [0..$screenWidth], Y[$yMin..$yMax] -> [0..$screenHeight]")

                val xCalibration = AxisCalibration(xMin, xMax, screenWidth)
                val yCalibration = AxisCalibration(yMin, yMax, screenHeight)

                return TouchInput(fd, path, xCalibration, yCalibration)
            }
        }

        /**
         * Find the first available touch device by scanning /dev/input/event*.
         * Returns the device path or null if none found.
         */
        fun findTouchDevice(): String? {
            for (i in 0..15) {
                val path = "/dev/input/event$i"
                val fd = open(path, O_RDONLY or O_NONBLOCK)
                if (fd < 0) continue

                // Check if this device has touch capabilities
                memScoped {
                    val evBits = allocArray<ULongVar>(1)
                    val EVIOCGBIT_0 = 0x80084520u // EVIOCGBIT(0, sizeof(evBits))
                    if (ioctl(fd, EVIOCGBIT_0.toULong(), evBits) >= 0) {
                        val hasAbs = (evBits[0].toLong() and (1L shl EV_ABS)) != 0L
                        if (hasAbs) {
                            // Check for touch-specific ABS codes
                            val absBits = allocArray<ULongVar>(2)
                            val EVIOCGBIT_ABS = 0x80084523u // EVIOCGBIT(EV_ABS, sizeof(absBits))
                            if (ioctl(fd, EVIOCGBIT_ABS.toULong(), absBits) >= 0) {
                                val hasX = (absBits[0].toLong() and (1L shl ABS_MT_POSITION_X)) != 0L ||
                                        (absBits[0].toLong() and (1L shl ABS_X)) != 0L
                                val hasY = (absBits[0].toLong() and (1L shl ABS_MT_POSITION_Y)) != 0L ||
                                        (absBits[0].toLong() and (1L shl ABS_Y)) != 0L
                                if (hasX && hasY) {
                                    close(fd)
                                    println("[Touch] Found touch device: $path")
                                    return path
                                }
                            }
                        }
                    }
                }
                close(fd)
            }
            println("[Touch] No touch device found")
            return null
        }
    }
}
