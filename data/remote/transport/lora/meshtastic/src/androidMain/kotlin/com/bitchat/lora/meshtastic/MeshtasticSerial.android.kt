package com.bitchat.lora.meshtastic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android implementation of Meshtastic serial using usb-serial-for-android.
 *
 * Requires USB permission to be granted by the user.
 */
actual class MeshtasticSerial {

    private var usbManager: UsbManager? = null
    private var port: UsbSerialPort? = null
    private var driver: UsbSerialDriver? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    actual val isConnected: Boolean get() = port?.isOpen == true

    /**
     * Callback invoked when connection is lost unexpectedly.
     * Used by MeshtasticProtocol to trigger reconnection.
     */
    actual var onDisconnect: (() -> Unit)? = null

    // Context must be set before calling open()
    var context: Context? = null

    actual fun open(): Boolean {
        val ctx = context ?: run {
            println("❌ Context not set")
            return false
        }

        usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

        val driver = findMeshtasticDriver()
        if (driver == null) {
            println("❌ No Meshtastic device found")
            return false
        }

        this.driver = driver
        val device = driver.device
        println("📡 Found Meshtastic device: ${device.productName ?: device.deviceName}")

        // Check if we have permission
        if (!usbManager!!.hasPermission(device)) {
            println("⏳ Requesting USB permission...")
            requestPermission(ctx, device)
            return false
        }

        return openPort(driver)
    }

    private fun openPort(driver: UsbSerialDriver): Boolean {
        val connection = usbManager?.openDevice(driver.device)
        if (connection == null) {
            println("❌ Failed to open USB device")
            return false
        }

        try {
            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )

            this.port = port
            println("✅ Meshtastic serial port opened")

            startReading()
            return true
        } catch (e: Exception) {
            println("❌ Failed to open port: ${e.message}")
            return false
        }
    }

    private fun requestPermission(context: Context, device: UsbDevice) {
        val ACTION_USB_PERMISSION = "com.bitchat.lora.meshtastic.USB_PERMISSION"

        val permissionIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    if (granted) {
                        println("✅ USB permission granted")
                        driver?.let { openPort(it) }
                    } else {
                        println("❌ USB permission denied")
                    }
                    context.unregisterReceiver(this)
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        usbManager?.requestPermission(device, permissionIntent)
    }

    actual fun close() {
        readJob?.cancel()
        readJob = null

        try {
            port?.close()
        } catch (e: Exception) {
            println("⚠️ Error closing port: ${e.message}")
        }

        port = null
        driver = null
        scope.cancel()
    }

    actual fun send(data: ByteArray): Boolean {
        val currentPort = port ?: return false
        if (!currentPort.isOpen) return false

        val framed = buildFrame(data)

        return try {
            currentPort.write(framed, 1000)
            true
        } catch (e: Exception) {
            println("❌ Send error: ${e.message}")
            false
        }
    }

    /**
     * Find a Meshtastic USB device.
     */
    private fun findMeshtasticDriver(): UsbSerialDriver? {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        for (driver in availableDrivers) {
            val device = driver.device
            val vendorId = device.vendorId
            val productId = device.productId

            // Common Meshtastic device VID/PIDs
            // CP2102/CP2104 (Silicon Labs) - common on ESP32
            // CH340/CH341 - common on budget boards
            // CH9102 - newer alternative
            // Espressif native USB
            val isMeshtastic = when (vendorId) {
                0x10C4 -> true  // Silicon Labs CP210x
                0x1A86 -> true  // CH340/CH341/CH9102
                0x303A -> true  // Espressif
                0x239A -> true  // Adafruit
                0x2E8A -> true  // Raspberry Pi (RP2040)
                else -> false
            }

            if (isMeshtastic) {
                println("📱 Found compatible device: VID=${vendorId.toString(16)} PID=${productId.toString(16)}")
                return driver
            }
        }

        // Fallback: return first available driver
        return availableDrivers.firstOrNull()
    }

    /**
     * Start reading from the serial port.
     */
    private fun startReading() {
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            val frameBuffer = mutableListOf<Byte>()
            var state: ReadState = ReadState.WAIT_START_1

            while (isActive) {
                val currentPort = port ?: run {
                    println("⚠️ MeshtasticSerial: Port became null, connection lost")
                    onDisconnect?.invoke()
                    break
                }
                if (!currentPort.isOpen) {
                    println("⚠️ MeshtasticSerial: Port closed, connection lost")
                    onDisconnect?.invoke()
                    break
                }

                val bytesRead = try {
                    currentPort.read(buffer, 100)
                } catch (e: Exception) {
                    if (isActive) {
                        println("⚠️ Read error: ${e.message}")
                        // If we get an exception while reading, the connection is likely dead
                        port = null
                        onDisconnect?.invoke()
                    }
                    break
                }

                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }

                for (i in 0 until bytesRead) {
                    val b = buffer[i]

                    when (state) {
                        ReadState.WAIT_START_1 -> {
                            if (b == MeshtasticSerialConstants.START_BYTE_1.toByte()) {
                                frameBuffer.clear()
                                state = ReadState.WAIT_START_2
                            }
                        }

                        ReadState.WAIT_START_2 -> {
                            state = if (b == MeshtasticSerialConstants.START_BYTE_2.toByte()) {
                                ReadState.READ_VARINT
                            } else if (b == MeshtasticSerialConstants.START_BYTE_1.toByte()) {
                                ReadState.WAIT_START_2
                            } else {
                                ReadState.WAIT_START_1
                            }
                        }

                        ReadState.READ_VARINT -> {
                            frameBuffer.add(b)

                            if ((b.toInt() and 0x80) == 0) {
                                val length = decodeVarint(frameBuffer.toByteArray())
                                frameBuffer.clear()
                                if (length in 1..1023) {
                                    state = ReadState.READ_PAYLOAD(length, 0)
                                } else {
                                    state = ReadState.WAIT_START_1
                                }
                            } else if (frameBuffer.size > 4) {
                                frameBuffer.clear()
                                state = ReadState.WAIT_START_1
                            }
                        }

                        is ReadState.READ_PAYLOAD -> {
                            frameBuffer.add(b)
                            val readPayload = state as ReadState.READ_PAYLOAD
                            val newRead = readPayload.bytesRead + 1

                            if (newRead >= readPayload.length) {
                                val payload = frameBuffer.toByteArray()
                                frameBuffer.clear()
                                state = ReadState.WAIT_START_1

                                try {
                                    _incoming.emit(payload)
                                } catch (e: Exception) {
                                    println("❌ Error emitting: ${e.message}")
                                }
                            } else {
                                state = ReadState.READ_PAYLOAD(readPayload.length, newRead)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildFrame(data: ByteArray): ByteArray {
        val varint = encodeVarint(data.size)
        val frame = ByteArray(2 + varint.size + data.size)

        frame[0] = MeshtasticSerialConstants.START_BYTE_1.toByte()
        frame[1] = MeshtasticSerialConstants.START_BYTE_2.toByte()
        varint.copyInto(frame, 2)
        data.copyInto(frame, 2 + varint.size)

        return frame
    }

    private fun encodeVarint(value: Int): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value

        while (v >= 0x80) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add(v.toByte())

        return result.toByteArray()
    }

    private fun decodeVarint(bytes: ByteArray): Int {
        var result = 0
        var shift = 0

        for (b in bytes) {
            result = result or ((b.toInt() and 0x7F) shl shift)
            if ((b.toInt() and 0x80) == 0) break
            shift += 7
        }

        return result
    }

    private sealed class ReadState {
        data object WAIT_START_1 : ReadState()
        data object WAIT_START_2 : ReadState()
        data object READ_VARINT : ReadState()
        data class READ_PAYLOAD(val length: Int, val bytesRead: Int) : ReadState()
    }

}
