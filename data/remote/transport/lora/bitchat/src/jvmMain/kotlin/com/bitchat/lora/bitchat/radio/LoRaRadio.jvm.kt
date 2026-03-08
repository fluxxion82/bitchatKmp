package com.bitchat.lora.bitchat.radio

import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import com.fazecast.jSerialComm.SerialPort
import com.fazecast.jSerialComm.SerialPortDataListener
import com.fazecast.jSerialComm.SerialPortEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * JVM LoRa radio implementation using jSerialComm for USB serial dongles.
 *
 * Supports AT command-based LoRa modules like DSD Tech SX1276 dongles.
 */
actual class LoRaRadio {
    private var serialPort: SerialPort? = null
    private var currentConfig: LoRaConfig? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<LoRaEvent>(extraBufferCapacity = 64)
    actual val events: Flow<LoRaEvent> = _events.asSharedFlow()

    actual val isReady: Boolean
        get() = serialPort?.isOpen == true && currentConfig != null

    private val receiveBuffer = StringBuilder()
    private var receiving = false

    actual fun configure(config: LoRaConfig): Boolean {
        LoRaLogger.i(LoRaTags.RADIO, "Configuring LoRa radio: $config")

        val port = findLoRaPort()
        if (port == null) {
            LoRaLogger.e(LoRaTags.RADIO, "No LoRa serial port found")
            emitEvent(LoRaEvent.Error("No LoRa serial port found"))
            return false
        }

        serialPort = port

        if (!port.openPort()) {
            LoRaLogger.e(LoRaTags.RADIO, "Failed to open serial port: ${port.systemPortName}")
            emitEvent(LoRaEvent.Error("Failed to open serial port"))
            return false
        }

        port.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0)

        // Configure via AT commands
        if (!sendATCommands(config)) {
            LoRaLogger.e(LoRaTags.RADIO, "Failed to configure radio via AT commands")
            port.closePort()
            serialPort = null
            return false
        }

        currentConfig = config
        setupDataListener()

        LoRaLogger.i(LoRaTags.RADIO, "LoRa radio configured successfully on ${port.systemPortName}")
        emitEvent(LoRaEvent.RadioReady(config))
        return true
    }

    actual fun send(data: ByteArray): Boolean {
        val port = serialPort
        if (port == null || !port.isOpen) {
            LoRaLogger.e(LoRaTags.RADIO, "Cannot send: port not open")
            return false
        }

        LoRaLogger.d(LoRaTags.RADIO, "Sending ${data.size} bytes")

        // AT+SEND=<length>
        val sendCmd = "AT+SEND=${data.size}\r\n"
        port.writeBytes(sendCmd.toByteArray(), sendCmd.length)

        // Wait for ">" prompt then send data
        Thread.sleep(50)
        val written = port.writeBytes(data, data.size)

        if (written == data.size) {
            LoRaLogger.v(LoRaTags.RADIO, "Sent $written bytes successfully")
            return true
        } else {
            LoRaLogger.e(LoRaTags.RADIO, "Send failed: wrote $written of ${data.size} bytes")
            return false
        }
    }

    actual fun startReceiving() {
        LoRaLogger.i(LoRaTags.RADIO, "Starting receive mode")
        receiving = true
        // AT+RX puts module in continuous receive mode
        sendATCommand("AT+RX")
    }

    actual fun stopReceiving() {
        LoRaLogger.i(LoRaTags.RADIO, "Stopping receive mode")
        receiving = false
        // AT+IDLE returns to idle mode
        sendATCommand("AT+IDLE")
    }

    actual fun close() {
        LoRaLogger.i(LoRaTags.RADIO, "Closing LoRa radio")
        receiving = false
        serialPort?.closePort()
        serialPort = null
        currentConfig = null
        scope.cancel()
        emitEvent(LoRaEvent.Disconnected)
    }

    private fun findLoRaPort(): SerialPort? {
        val ports = SerialPort.getCommPorts()
        LoRaLogger.d(LoRaTags.RADIO, "Available serial ports: ${ports.map { it.systemPortName }}")

        // Look for common LoRa USB adapter patterns
        return ports.firstOrNull { port ->
            val name = port.systemPortName.lowercase()
            val desc = port.portDescription.lowercase()
            name.contains("usb") ||
                desc.contains("ch340") ||
                desc.contains("cp210") ||
                desc.contains("ftdi") ||
                desc.contains("lora")
        }
    }

    private fun sendATCommands(config: LoRaConfig): Boolean {
        // Test connection
        if (!sendATCommand("AT", expectOk = true)) return false

        // Set frequency
        if (!sendATCommand("AT+FREQ=${config.frequency}", expectOk = true)) return false

        // Set spreading factor
        if (!sendATCommand("AT+SF=${config.spreadingFactor}", expectOk = true)) return false

        // Set bandwidth
        val bwKhz = config.bandwidth / 1000
        if (!sendATCommand("AT+BW=$bwKhz", expectOk = true)) return false

        // Set coding rate
        if (!sendATCommand("AT+CR=${config.codingRate}", expectOk = true)) return false

        // Set TX power
        if (!sendATCommand("AT+PWR=${config.txPower}", expectOk = true)) return false

        // Set sync word
        if (!sendATCommand("AT+SYNC=${config.syncWord}", expectOk = true)) return false

        return true
    }

    private fun sendATCommand(command: String, expectOk: Boolean = false): Boolean {
        val port = serialPort ?: return false

        LoRaLogger.v(LoRaTags.AT_CMD, ">> $command")
        val cmd = "$command\r\n"
        port.writeBytes(cmd.toByteArray(), cmd.length)

        if (expectOk) {
            Thread.sleep(100)
            val response = readResponse()
            LoRaLogger.v(LoRaTags.AT_CMD, "<< $response")
            return response.contains("OK")
        }
        return true
    }

    private fun readResponse(): String {
        val port = serialPort ?: return ""
        val buffer = ByteArray(256)
        val read = port.readBytes(buffer, buffer.size)
        return if (read > 0) String(buffer, 0, read).trim() else ""
    }

    private fun setupDataListener() {
        serialPort?.addDataListener(object : SerialPortDataListener {
            override fun getListeningEvents(): Int = SerialPort.LISTENING_EVENT_DATA_RECEIVED

            override fun serialEvent(event: SerialPortEvent) {
                if (event.eventType != SerialPort.LISTENING_EVENT_DATA_RECEIVED) return

                val data = event.receivedData
                if (data == null || data.isEmpty()) return

                // Parse received data for +RCV messages
                val text = String(data)
                receiveBuffer.append(text)

                // Check for complete +RCV message
                // Format: +RCV=<length>,<rssi>,<snr>,<data>
                val buffer = receiveBuffer.toString()
                val rcvMatch = Regex("\\+RCV=(\\d+),(-?\\d+),(-?\\d+),(.+?)\\r?\\n").find(buffer)

                if (rcvMatch != null) {
                    val length = rcvMatch.groupValues[1].toInt()
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
        })
    }

    private fun emitEvent(event: LoRaEvent) {
        scope.launch {
            _events.emit(event)
        }
    }
}
