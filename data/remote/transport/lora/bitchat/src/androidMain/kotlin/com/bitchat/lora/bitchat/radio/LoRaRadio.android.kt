package com.bitchat.lora.bitchat.radio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.Executors

/**
 * Android LoRa radio implementation using USB OTG serial.
 *
 * Requires USB host mode and user permission grant for the device.
 */
actual class LoRaRadio : KoinComponent {
    private val context: Context by inject()

    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var currentConfig: LoRaConfig? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = Executors.newSingleThreadExecutor()

    private val _events = MutableSharedFlow<LoRaEvent>(extraBufferCapacity = 64)
    actual val events: Flow<LoRaEvent> = _events.asSharedFlow()

    actual val isReady: Boolean
        get() = usbSerialPort?.isOpen == true && currentConfig != null

    private val receiveBuffer = StringBuilder()
    private val binaryReceiveBuffer = mutableListOf<Byte>()
    private var receiving = false
    private var permissionGranted = false
    private var transparentMode = false  // True if using transparent mode (no AT commands)
    private var teensyMode = false       // True if Teensy bridge detected (binary protocol)

    // Binary protocol commands (shared by Teensy and RangePi)
    companion object {
        private const val ACTION_USB_PERMISSION = "com.bitchat.lora.USB_PERMISSION"

        // Protocol commands
        private const val CMD_TX: Byte = 0x01
        private const val CMD_RX: Byte = 0x02
        private const val CMD_CONFIG: Byte = 0x03
        private const val CMD_PING: Byte = 0x04
        private const val CMD_PONG: Byte = 0x05

        // USB Vendor IDs for known devices
        private const val VID_PJRC = 0x16C0        // PJRC (Teensy)
        private const val VID_RASPBERRY_PI = 0x2E8A // Raspberry Pi Foundation (RP2040/Pico)

        // USB Product IDs
        private const val PID_TEENSY_SERIAL = 0x0483     // Teensy USB Serial
        private const val PID_RASPBERRY_PI_PICO = 0x000A // Raspberry Pi Pico CDC
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        LoRaLogger.i(LoRaTags.USB, "USB permission granted for ${device?.deviceName}")
                        permissionGranted = true
                    } else {
                        LoRaLogger.w(LoRaTags.USB, "USB permission denied for ${device?.deviceName}")
                        emitEvent(LoRaEvent.Error("USB permission denied"))
                    }
                }
            }
        }
    }

    actual fun configure(config: LoRaConfig): Boolean {
        LoRaLogger.i(LoRaTags.RADIO, "Configuring LoRa radio: $config")
        LoRaLogger.i(LoRaTags.RADIO, "isReady before config: $isReady")

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        // Diagnostic logging for USB device detection
        LoRaLogger.i(LoRaTags.USB, "USB device count: ${drivers.size}")
        drivers.forEachIndexed { index, driver ->
            val device = driver.device
            LoRaLogger.i(LoRaTags.USB, "  Device[$index]: ${device.deviceName}")
            LoRaLogger.i(LoRaTags.USB, "    VID=0x${device.vendorId.toString(16).uppercase()}, PID=0x${device.productId.toString(16).uppercase()}")
            LoRaLogger.i(LoRaTags.USB, "    Permission: ${usbManager.hasPermission(device)}")
            LoRaLogger.i(LoRaTags.USB, "    Manufacturer: ${device.manufacturerName ?: "unknown"}")
            LoRaLogger.i(LoRaTags.USB, "    Product: ${device.productName ?: "unknown"}")
        }

        if (drivers.isEmpty()) {
            LoRaLogger.e(LoRaTags.USB, "No USB serial devices found")
            emitEvent(LoRaEvent.Error("No USB serial devices found"))
            return false
        }

        LoRaLogger.d(LoRaTags.USB, "Found ${drivers.size} USB serial device(s)")

        val driver = drivers.first()
        val device = driver.device

        // Request permission if needed
        if (!usbManager.hasPermission(device)) {
            LoRaLogger.i(LoRaTags.USB, "Requesting USB permission for ${device.deviceName}")
            requestUsbPermission(usbManager, device)
            // Permission will be granted asynchronously
            emitEvent(LoRaEvent.Error("USB permission required - please grant and retry"))
            return false
        }

        return openAndConfigure(driver, usbManager, config)
    }

    private fun openAndConfigure(driver: UsbSerialDriver, usbManager: UsbManager, config: LoRaConfig): Boolean {
        val device = driver.device
        val vendorId = device.vendorId
        val productId = device.productId

        LoRaLogger.i(LoRaTags.USB, "USB device: VID=0x${vendorId.toString(16).uppercase()}, PID=0x${productId.toString(16).uppercase()}")

        // Check if this is a known bridge device (Teensy or RangePi)
        val isKnownBridge = when (vendorId) {
            VID_PJRC -> {
                LoRaLogger.i(LoRaTags.RADIO, "Detected PJRC device (Teensy)")
                true
            }
            VID_RASPBERRY_PI -> {
                LoRaLogger.i(LoRaTags.RADIO, "Detected Raspberry Pi Foundation device (RangePi/Pico)")
                true
            }
            else -> false
        }

        // Try binary bridge protocol FIRST for known devices - it's fast (just a ping) and most reliable
        if (isKnownBridge) {
            LoRaLogger.i(LoRaTags.RADIO, "Trying LoRa bridge detection...")
            if (tryLoRaBridge(driver, usbManager, config)) {
                return true
            }
        }

        LoRaLogger.d(LoRaTags.RADIO, "Not a LoRa bridge - trying AT command modules...")

        val port = driver.ports.first()

        // Try common baud rates for DSD Tech LoRa modules
        val baudRates = listOf(9600, 115200)

        for (baudRate in baudRates) {
            // Get fresh connection for each attempt
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                LoRaLogger.e(LoRaTags.USB, "Failed to open USB device")
                emitEvent(LoRaEvent.Error("Failed to open USB device"))
                return false
            }

            try {
                port.open(connection)
                port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                // Enable DTR and RTS - many modules require these
                port.dtr = true
                port.rts = true

                // Give the module time to initialize after connection
                Thread.sleep(100)

                // Clear any pending data
                try { port.purgeHwBuffers(true, true) } catch (_: Exception) {}

                usbSerialPort = port
                LoRaLogger.d(LoRaTags.USB, "Trying AT commands at $baudRate baud")

                // Quick AT test without long Hayes escape sequence
                if (sendATCommand("AT", expectOk = true)) {
                    LoRaLogger.i(LoRaTags.USB, "Module responding at $baudRate baud")

                    // Now configure via AT commands
                    if (sendATCommands(config)) {
                        currentConfig = config
                        setupDataListener(port)

                        LoRaLogger.i(LoRaTags.RADIO, "LoRa radio configured successfully")
                        emitEvent(LoRaEvent.RadioReady(config))
                        return true
                    }
                }

                // This baud rate didn't work, close and try next
                port.close()
                usbSerialPort = null

            } catch (e: Exception) {
                LoRaLogger.d(LoRaTags.USB, "Baud rate $baudRate failed: ${e.message}")
                try { port.close() } catch (_: Exception) {}
                usbSerialPort = null
            }
        }

        // Nothing worked - try transparent mode as last resort
        LoRaLogger.w(LoRaTags.RADIO, "AT commands not responding - trying transparent mode as fallback")
        return tryTransparentMode(driver, usbManager, config)
    }

    /**
     * Try to detect and configure a LoRa bridge (Teensy or RangePi).
     * Uses binary protocol with ping/pong handshake.
     */
    private fun tryLoRaBridge(driver: UsbSerialDriver, usbManager: UsbManager, config: LoRaConfig): Boolean {
        // Both Teensy and RangePi use 115200 baud
        val bridgeBaudRate = 115200

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            LoRaLogger.e(LoRaTags.USB, "Failed to open USB device for bridge detection")
            return false
        }

        val device = driver.device
        val deviceName = when (device.vendorId) {
            VID_PJRC -> "Teensy"
            VID_RASPBERRY_PI -> "RangePi"
            else -> "Unknown Bridge"
        }

        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(bridgeBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            // Wait for device to initialize (RangePi may need longer due to RP2040 USB init)
            val initDelay = if (device.vendorId == VID_RASPBERRY_PI) 1000L else 500L
            Thread.sleep(initDelay)

            // Clear any startup messages
            val discardBuf = ByteArray(256)
            try { port.read(discardBuf, 100) } catch (_: Exception) {}

            // Send ping command
            LoRaLogger.d(LoRaTags.RADIO, "Sending $deviceName ping...")
            port.write(byteArrayOf(CMD_PING), 500)

            // Wait for pong response
            Thread.sleep(100)
            val response = ByteArray(2)
            val read = port.read(response, 500)

            if (read >= 2 && response[0] == CMD_PONG) {
                LoRaLogger.i(LoRaTags.RADIO, "$deviceName LoRa bridge detected!")

                usbSerialPort = port
                currentConfig = config
                teensyMode = true  // teensyMode means "binary protocol mode"
                transparentMode = false

                setupTeensyDataListener(port)

                LoRaLogger.i(LoRaTags.RADIO, "LoRa radio ready via $deviceName BRIDGE at $bridgeBaudRate baud")
                emitEvent(LoRaEvent.RadioReady(config))
                return true
            } else {
                LoRaLogger.d(LoRaTags.RADIO, "No $deviceName pong response (read=$read)")
                port.close()
                return false
            }

        } catch (e: Exception) {
            LoRaLogger.d(LoRaTags.RADIO, "$deviceName detection failed: ${e.message}")
            try { port.close() } catch (_: Exception) {}
            return false
        }
    }

    /**
     * Try to use the module in transparent mode (no AT commands).
     * In this mode, the module uses its default/factory settings.
     * We just open the port and assume it's ready to send/receive.
     */
    private fun tryTransparentMode(driver: UsbSerialDriver, usbManager: UsbManager, config: LoRaConfig): Boolean {
        // Most DSD TECH modules default to 9600 baud in transparent mode
        val defaultBaudRate = 9600

        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            LoRaLogger.e(LoRaTags.USB, "Failed to open USB device for transparent mode")
            return false
        }

        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(defaultBaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            Thread.sleep(100)

            usbSerialPort = port
            currentConfig = config // Note: actual radio config may differ from requested
            transparentMode = true
            teensyMode = false

            setupTransparentDataListener(port)

            LoRaLogger.i(LoRaTags.RADIO, "LoRa radio opened in TRANSPARENT MODE at $defaultBaudRate baud")
            LoRaLogger.w(LoRaTags.RADIO, "Note: Cannot configure radio parameters - using module defaults")
            LoRaLogger.w(LoRaTags.RADIO, "Module likely uses 433MHz or preset frequency. Check module documentation.")

            emitEvent(LoRaEvent.RadioReady(config))
            return true

        } catch (e: Exception) {
            LoRaLogger.e(LoRaTags.RADIO, "Failed to open in transparent mode", e)
            try { port.close() } catch (_: Exception) {}
            usbSerialPort = null
            return false
        }
    }

    private fun sendATCommands(config: LoRaConfig): Boolean {
        if (!sendATCommand("AT+FREQ=${config.frequency}", expectOk = true)) return false
        if (!sendATCommand("AT+SF=${config.spreadingFactor}", expectOk = true)) return false
        val bwKhz = config.bandwidth / 1000
        if (!sendATCommand("AT+BW=$bwKhz", expectOk = true)) return false
        if (!sendATCommand("AT+CR=${config.codingRate}", expectOk = true)) return false
        if (!sendATCommand("AT+PWR=${config.txPower}", expectOk = true)) return false
        if (!sendATCommand("AT+SYNC=${config.syncWord}", expectOk = true)) return false
        return true
    }

    actual fun send(data: ByteArray): Boolean {
        val port = usbSerialPort
        if (port == null || !port.isOpen) {
            LoRaLogger.e(LoRaTags.RADIO, "Cannot send: port not open")
            return false
        }

        LoRaLogger.d(LoRaTags.RADIO, "Sending ${data.size} bytes (teensy=$teensyMode, transparent=$transparentMode)")

        try {
            if (teensyMode) {
                // Teensy binary protocol: CMD_TX + len_lo + len_hi + payload
                val packet = ByteArray(3 + data.size)
                packet[0] = CMD_TX
                packet[1] = (data.size and 0xFF).toByte()
                packet[2] = ((data.size shr 8) and 0xFF).toByte()
                System.arraycopy(data, 0, packet, 3, data.size)

                port.write(packet, 2000)

                // Wait for config response (success/error)
                Thread.sleep(50)
                val response = ByteArray(2)
                val read = port.read(response, 500)
                if (read >= 2 && response[0] == CMD_CONFIG && response[1] == 0.toByte()) {
                    LoRaLogger.v(LoRaTags.RADIO, "Sent ${data.size} bytes via Teensy bridge")
                    return true
                } else {
                    LoRaLogger.w(LoRaTags.RADIO, "Teensy send response: ${response.take(read).joinToString { "%02X".format(it) }}")
                    return true // Still consider it sent, response might be delayed
                }
            } else if (transparentMode) {
                // In transparent mode, just write data directly
                // The module will transmit whatever it receives on UART
                port.write(data, 2000)
                LoRaLogger.v(LoRaTags.RADIO, "Sent ${data.size} bytes in transparent mode")
            } else {
                // AT command mode: AT+SEND=<length>
                val sendCmd = "AT+SEND=${data.size}\r\n"
                port.write(sendCmd.toByteArray(), 1000)

                // Wait for ">" prompt then send data
                Thread.sleep(50)
                port.write(data, 1000)

                LoRaLogger.v(LoRaTags.RADIO, "Sent ${data.size} bytes via AT command")
            }
            return true
        } catch (e: Exception) {
            LoRaLogger.e(LoRaTags.RADIO, "Send failed", e)
            return false
        }
    }

    actual fun startReceiving() {
        LoRaLogger.i(LoRaTags.RADIO, "Starting receive mode")
        receiving = true
        // Bridge and transparent modes handle RX automatically - only send AT commands for AT-command modules
        if (!transparentMode && !teensyMode) {
            sendATCommand("AT+RX")
        }
    }

    actual fun stopReceiving() {
        LoRaLogger.i(LoRaTags.RADIO, "Stopping receive mode")
        receiving = false
        // Only AT-command modules need explicit idle command
        if (!transparentMode && !teensyMode) {
            sendATCommand("AT+IDLE")
        }
    }

    actual fun close() {
        LoRaLogger.i(LoRaTags.RADIO, "Closing LoRa radio")
        receiving = false
        ioManager?.stop()
        ioManager = null
        usbSerialPort?.close()
        usbSerialPort = null
        currentConfig = null

        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver not registered
        }

        scope.cancel()
        emitEvent(LoRaEvent.Disconnected)
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        // Create explicit intent (required for Android 14+)
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }

        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * Try various methods to enter AT command mode.
     * Different modules use different escape sequences.
     */
    private fun tryEnterATMode(port: UsbSerialPort) {
        LoRaLogger.d(LoRaTags.AT_CMD, "Attempting to enter AT command mode...")

        try {
            // Method 1: Hayes escape sequence (1 second silence, +++, 1 second silence)
            // This is the standard way to enter command mode from data mode
            Thread.sleep(1100)
            port.write("+++".toByteArray(), 500)
            Thread.sleep(1100)

            // Read any response
            val buffer = ByteArray(64)
            val read = port.read(buffer, 500)
            if (read > 0) {
                val response = String(buffer, 0, read)
                LoRaLogger.d(LoRaTags.AT_CMD, "+++ response: $response")
            }

            // Method 2: Some modules need a newline after +++
            port.write("\r\n".toByteArray(), 500)
            Thread.sleep(100)

            // Method 3: Try waking up with a simple AT (no expectation)
            port.write("AT\r\n".toByteArray(), 500)
            Thread.sleep(200)

            // Clear buffer
            try { port.purgeHwBuffers(true, true) } catch (_: Exception) {}

        } catch (e: Exception) {
            LoRaLogger.d(LoRaTags.AT_CMD, "Enter AT mode attempt failed: ${e.message}")
        }
    }

    private fun sendATCommand(command: String, expectOk: Boolean = false): Boolean {
        val port = usbSerialPort ?: return false

        LoRaLogger.v(LoRaTags.AT_CMD, ">> $command")
        val cmd = "$command\r\n"

        try {
            port.write(cmd.toByteArray(), 1000)

            if (expectOk) {
                // Wait for response - some modules are slow
                Thread.sleep(200)
                val response = readResponse()
                LoRaLogger.v(LoRaTags.AT_CMD, "<< $response")

                // Accept various OK formats: "OK", "+OK", "AT OK", etc.
                val upper = response.uppercase()
                return upper.contains("OK") || upper.contains("+AT")
            }
            return true
        } catch (e: Exception) {
            LoRaLogger.e(LoRaTags.AT_CMD, "AT command failed", e)
            return false
        }
    }

    private fun readResponse(): String {
        val port = usbSerialPort ?: return ""
        val buffer = ByteArray(256)
        return try {
            // Try reading multiple times to collect full response
            val totalBuffer = StringBuilder()
            for (i in 0 until 3) {
                val read = port.read(buffer, 500)
                if (read > 0) {
                    val chunk = String(buffer, 0, read)
                    totalBuffer.append(chunk)
                    // Log raw bytes for debugging
                    val hexBytes = buffer.take(read).joinToString(" ") { "%02X".format(it) }
                    LoRaLogger.d(LoRaTags.AT_CMD, "Raw bytes ($read): $hexBytes")
                }
                if (totalBuffer.contains("\n") || totalBuffer.contains("OK")) break
                Thread.sleep(50)
            }
            totalBuffer.toString().trim()
        } catch (e: Exception) {
            LoRaLogger.d(LoRaTags.AT_CMD, "Read error: ${e.message}")
            ""
        }
    }

    /**
     * Data listener for Teensy bridge.
     * Parses binary protocol: CMD_RX + len_lo + len_hi + rssi + snr + payload
     */
    private fun setupTeensyDataListener(port: UsbSerialPort) {
        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                if (data.isEmpty()) return

                // Add to buffer
                data.forEach { binaryReceiveBuffer.add(it) }

                // Process complete packets
                while (binaryReceiveBuffer.size >= 5) {
                    val cmd = binaryReceiveBuffer[0]

                    if (cmd == CMD_RX) {
                        val lenLo = binaryReceiveBuffer[1].toInt() and 0xFF
                        val lenHi = binaryReceiveBuffer[2].toInt() and 0xFF
                        val payloadLen = lenLo or (lenHi shl 8)
                        if (payloadLen <= 0 || payloadLen > 512) {
                            LoRaLogger.w(LoRaTags.RADIO, "Discarding invalid bridge payload length: $payloadLen")
                            binaryReceiveBuffer.removeAt(0)
                            continue
                        }

                        // Check if we have the full packet: cmd + len(2) + rssi + snr + payload
                        val totalLen = 5 + payloadLen
                        if (binaryReceiveBuffer.size < totalLen) {
                            break // Wait for more data
                        }

                        val rssi = binaryReceiveBuffer[3].toInt()
                        val snr = binaryReceiveBuffer[4].toInt()
                        val payload = binaryReceiveBuffer.subList(5, 5 + payloadLen).toByteArray()

                        // Remove processed packet from buffer
                        repeat(totalLen) { binaryReceiveBuffer.removeAt(0) }

                        LoRaLogger.d(LoRaTags.RADIO, "Teensy RX: ${payload.size} bytes, RSSI=$rssi, SNR=$snr")
                        emitEvent(LoRaEvent.PacketReceived(payload, rssi, snr.toFloat()))

                    } else if (cmd == CMD_PONG || cmd == CMD_CONFIG) {
                        // Response to ping or config - just consume it
                        binaryReceiveBuffer.removeAt(0)
                        if (binaryReceiveBuffer.isNotEmpty()) {
                            binaryReceiveBuffer.removeAt(0)
                        }
                    } else {
                        // Ignore ASCII debug/status lines emitted by firmware (e.g. "#DBG: ...\n").
                        val cmdInt = cmd.toInt() and 0xFF
                        if (cmd == '#'.code.toByte() || cmdInt in 0x20..0x7E) {
                            val newlineIdx = binaryReceiveBuffer.indexOf('\n'.code.toByte())
                            if (newlineIdx >= 0) {
                                val lineBytes = binaryReceiveBuffer.subList(0, newlineIdx + 1).toByteArray()
                                repeat(newlineIdx + 1) { binaryReceiveBuffer.removeAt(0) }
                                val line = lineBytes.decodeToString().trim()
                                LoRaLogger.d(LoRaTags.RADIO, "Bridge text: $line")
                            } else {
                                break
                            }
                        } else {
                            // Unknown binary byte, drop one and continue scanning for frame boundary.
                            binaryReceiveBuffer.removeAt(0)
                        }
                    }
                }
            }

            override fun onRunError(e: Exception) {
                LoRaLogger.e(LoRaTags.RADIO, "Serial IO error", e)
                emitEvent(LoRaEvent.Error("Serial IO error: ${e.message}"))
            }
        }

        ioManager = SerialInputOutputManager(port, listener).also {
            executor.submit(it)
        }
    }

    /**
     * Data listener for transparent mode.
     * In transparent mode, raw bytes come in directly without any framing.
     * We treat each chunk as a packet (simple approach).
     */
    private fun setupTransparentDataListener(port: UsbSerialPort) {
        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                if (data.isEmpty()) return

                val hexPreview = data.take(20).joinToString(" ") { "%02X".format(it) }
                LoRaLogger.d(LoRaTags.RADIO, "Transparent RX: ${data.size} bytes - $hexPreview...")

                // In transparent mode, each received chunk is a packet
                // RSSI/SNR not available in this mode
                emitEvent(LoRaEvent.PacketReceived(data.copyOf(), rssi = 0, snr = 0f))
            }

            override fun onRunError(e: Exception) {
                LoRaLogger.e(LoRaTags.RADIO, "Serial IO error", e)
                emitEvent(LoRaEvent.Error("Serial IO error: ${e.message}"))
            }
        }

        ioManager = SerialInputOutputManager(port, listener).also {
            executor.submit(it)
        }
    }

    private fun setupDataListener(port: UsbSerialPort) {
        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val text = String(data)
                receiveBuffer.append(text)

                val buffer = receiveBuffer.toString()
                val rcvMatch = Regex("\\+RCV=(\\d+),(-?\\d+),(-?\\d+),(.+?)\\r?\\n").find(buffer)

                if (rcvMatch != null) {
                    val rssi = rcvMatch.groupValues[2].toInt()
                    val snr = rcvMatch.groupValues[3].toFloatOrNull() ?: 0f
                    val hexData = rcvMatch.groupValues[4]

                    val packetData = hexData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

                    LoRaLogger.d(LoRaTags.RADIO, "Received ${packetData.size} bytes, RSSI=$rssi, SNR=$snr")
                    emitEvent(LoRaEvent.PacketReceived(packetData, rssi, snr))

                    receiveBuffer.clear()
                    receiveBuffer.append(buffer.substring(rcvMatch.range.last + 1))
                }
            }

            override fun onRunError(e: Exception) {
                LoRaLogger.e(LoRaTags.RADIO, "Serial IO error", e)
                emitEvent(LoRaEvent.Error("Serial IO error: ${e.message}"))
            }
        }

        ioManager = SerialInputOutputManager(port, listener).also {
            executor.submit(it)
        }
    }

    private fun emitEvent(event: LoRaEvent) {
        scope.launch {
            _events.emit(event)
        }
    }
}
