package com.bitchat.lora.bitchat.radio

import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import com.bitchat.lora.radio.LoRaConfig
import com.bitchat.lora.radio.LoRaEvent
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.posix.*

/**
 * Linux ARM64 LoRa radio implementation for Orange Pi Zero 3 / Raspberry Pi
 * with SPI-connected LoRa modules (SX1276/RFM95W).
 *
 * Hardware Requirements:
 * - SPI device (default: /dev/spidev1.1 for Orange Pi Zero 3)
 * - RFM95W/SX1276 module connected via SPI
 *
 * Wiring for Orange Pi Zero 3:
 * - MOSI: Pin 19 (PC2)
 * - MISO: Pin 21 (PC0)
 * - SCK:  Pin 23 (PC1)
 * - CS:   Pin 24 (PC3) - hardware SPI1_CS1
 *
 * Note: GPIO reset is not required - module initializes correctly on power-up.
 */
@OptIn(ExperimentalForeignApi::class)
actual class LoRaRadio(
    private val spiDevice: String = DEFAULT_SPI_DEVICE
) {
    private var spiFd: Int = -1
    private var currentConfig: LoRaConfig? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _events = MutableSharedFlow<LoRaEvent>(extraBufferCapacity = 64)
    actual val events: Flow<LoRaEvent> = _events.asSharedFlow()

    actual val isReady: Boolean
        get() = spiFd >= 0 && currentConfig != null

    private var receiving = false
    private var receiveJob: Job? = null
    private var rxPollCount = 0L  // Debug counter for RX poll checks
    private var lastRxDoneMs = 0L
    private var lastProfileSwitchMs = 0L
    private var rxProbeProfileIndex = 0
    private val probeRxScanEnabled = isProbeRxScanEnabled()

    actual fun configure(config: LoRaConfig): Boolean {
        LoRaLogger.i(LoRaTags.RADIO, "Configuring LoRa radio via SPI: $config")
        LoRaLogger.d(LoRaTags.SPI, "Using SPI device: $spiDevice")

        // Stop any conflicting services (like meshtasticd) that may be using the SPI radio
        if (!LoRaServiceManager.ensureNoConflictingServices()) {
            LoRaLogger.e(
                LoRaTags.SPI,
                "Conflicting LoRa service is still active; refusing to continue to avoid TX/RX timeouts"
            )
            emitEvent(LoRaEvent.Error("Conflicting LoRa service active (meshtasticd)"))
            return false
        }

        // Open SPI device
        spiFd = open(spiDevice, O_RDWR)
        if (spiFd < 0) {
            val error = strerror(errno)?.toKString() ?: "unknown error"
            LoRaLogger.e(LoRaTags.SPI, "Failed to open SPI device '$spiDevice': $error")
            emitEvent(LoRaEvent.Error("Failed to open SPI device: $error"))
            return false
        }

        // Configure SPI mode and speed
        if (!configureSpi()) {
            close()
            return false
        }

        // Initialize the SX1276
        if (!initializeRadio(config)) {
            close()
            return false
        }

        currentConfig = config
        LoRaLogger.i(LoRaTags.RADIO, "LoRa radio configured successfully via SPI")
        emitEvent(LoRaEvent.RadioReady(config))
        return true
    }

    actual fun send(data: ByteArray): Boolean {
        if (spiFd < 0) {
            LoRaLogger.e(LoRaTags.RADIO, "Cannot send: SPI not open")
            return false
        }

        LoRaLogger.d(LoRaTags.RADIO, "Sending ${data.size} bytes")

        // Set to standby mode
        writeRegister(REG_OP_MODE, MODE_STDBY or MODE_LORA)

        // Reset FIFO pointer to TX base
        writeRegister(REG_FIFO_ADDR_PTR, 0x00)
        writeRegister(REG_FIFO_TX_BASE_ADDR, 0x00)

        // Write data to FIFO
        for (byte in data) {
            writeRegister(REG_FIFO, byte.toInt() and 0xFF)
        }
        writeRegister(REG_PAYLOAD_LENGTH, data.size)

        // Clear all IRQ flags before TX
        writeRegister(REG_IRQ_FLAGS, 0xFF)

        // Switch to TX mode
        writeRegister(REG_OP_MODE, MODE_TX or MODE_LORA)

        // Wait for TX done (poll IRQ flags)
        // TX time depends on SF and payload size; SF7 with 12 bytes ~45ms
        val startTime = getTimeMillis()
        val timeoutMs = 5000L // 5 second timeout for worst case

        while ((getTimeMillis() - startTime) < timeoutMs) {
            val irqFlags = readRegister(REG_IRQ_FLAGS)
            if ((irqFlags and IRQ_TX_DONE) != 0) {
                // Clear TX done IRQ
                writeRegister(REG_IRQ_FLAGS, IRQ_TX_DONE)
                val elapsed = getTimeMillis() - startTime
                LoRaLogger.v(LoRaTags.RADIO, "TX complete in ${elapsed}ms")

                // Return to RX mode if we were receiving
                if (receiving) {
                    writeRegister(REG_FIFO_ADDR_PTR, 0x00)
                    writeRegister(REG_IRQ_FLAGS, 0xFF)
                    writeRegister(REG_OP_MODE, MODE_RX_CONTINUOUS or MODE_LORA)
                    LoRaLogger.v(LoRaTags.RADIO, "Returned to RX mode")
                } else {
                    writeRegister(REG_OP_MODE, MODE_STDBY or MODE_LORA)
                }
                return true
            }
            usleep(1_000u) // 1ms poll interval
        }

        LoRaLogger.e(LoRaTags.RADIO, "TX timeout after ${timeoutMs}ms")
        // Return to RX mode if we were receiving, otherwise standby
        if (receiving) {
            writeRegister(REG_OP_MODE, MODE_RX_CONTINUOUS or MODE_LORA)
        } else {
            writeRegister(REG_OP_MODE, MODE_STDBY or MODE_LORA)
        }
        return false
    }

    actual fun startReceiving() {
        if (spiFd < 0) return

        LoRaLogger.i(LoRaTags.RADIO, "Starting receive mode")
        receiving = true
        rxPollCount = 0  // Reset debug counter
        lastRxDoneMs = getTimeMillis()
        lastProfileSwitchMs = lastRxDoneMs
        rxProbeProfileIndex = 0

        // Reset FIFO pointer to RX base
        writeRegister(REG_FIFO_ADDR_PTR, 0x00)
        writeRegister(REG_FIFO_RX_BASE_ADDR, 0x00)

        // Clear all IRQ flags
        writeRegister(REG_IRQ_FLAGS, 0xFF)

        // Set to continuous RX mode
        writeRegister(REG_OP_MODE, MODE_RX_CONTINUOUS or MODE_LORA)

        if (probeRxScanEnabled && isProbeScanFrequency()) {
            LoRaLogger.i(
                LoRaTags.RADIO,
                "Probe RX scan enabled at 868 MHz: cycling LoRa profiles (sync/CRC/SF) until packets are seen"
            )
            applyProbeProfile(PROBE_RX_PROFILES[0])
            lastProfileSwitchMs = getTimeMillis()
        } else {
            LoRaLogger.i(LoRaTags.RADIO, "Frequency: 868.125 MHz (confirmed), using configured LoRa profile")
        }

        // Start polling for received packets
        receiveJob = scope.launch {
            while (receiving && isActive) {
                checkForReceivedPacket()
                delay(20) // 20ms poll interval (~50Hz)
            }
        }
    }

    private fun setFrequency(freq: Long) {
        val frf = ((freq.toDouble() * (1 shl 19)) / 32_000_000.0).toLong()
        writeRegister(REG_FRF_MSB, ((frf shr 16) and 0xFF).toInt())
        writeRegister(REG_FRF_MID, ((frf shr 8) and 0xFF).toInt())
        writeRegister(REG_FRF_LSB, (frf and 0xFF).toInt())
    }

    actual fun stopReceiving() {
        LoRaLogger.i(LoRaTags.RADIO, "Stopping receive mode")
        receiving = false
        receiveJob?.cancel()
        receiveJob = null

        if (spiFd >= 0) {
            writeRegister(REG_OP_MODE, MODE_STDBY or MODE_LORA)
        }
    }

    actual fun close() {
        LoRaLogger.i(LoRaTags.RADIO, "Closing LoRa radio")
        stopReceiving()

        if (spiFd >= 0) {
            // Put module in sleep mode to save power
            writeRegister(REG_OP_MODE, MODE_SLEEP or MODE_LORA)
            platform.posix.close(spiFd)
            spiFd = -1
        }
        currentConfig = null
        scope.cancel()
        LoRaServiceManager.restoreConflictingServices()
        emitEvent(LoRaEvent.Disconnected)
    }

    private fun configureSpi(): Boolean {
        memScoped {
            val mode = alloc<UByteVar>()
            mode.value = 0u // SPI_MODE_0

            // Set SPI mode
            if (ioctl(spiFd, SPI_IOC_WR_MODE, mode.ptr) < 0) {
                LoRaLogger.e(LoRaTags.SPI, "Failed to set SPI mode: ${strerror(errno)?.toKString()}")
                return false
            }

            // Set bits per word
            val bits = alloc<UByteVar>()
            bits.value = 8u
            if (ioctl(spiFd, SPI_IOC_WR_BITS_PER_WORD, bits.ptr) < 0) {
                LoRaLogger.e(LoRaTags.SPI, "Failed to set bits per word")
                return false
            }

            // Set max speed (1 MHz - SX1276 supports up to 10MHz)
            val speed = alloc<UIntVar>()
            speed.value = SPI_SPEED_HZ.toUInt()
            if (ioctl(spiFd, SPI_IOC_WR_MAX_SPEED_HZ, speed.ptr) < 0) {
                LoRaLogger.e(LoRaTags.SPI, "Failed to set SPI speed")
                return false
            }
        }

        LoRaLogger.d(LoRaTags.SPI, "SPI configured: mode=0, bits=8, speed=${SPI_SPEED_HZ}Hz")
        return true
    }

    private fun initializeRadio(config: LoRaConfig): Boolean {
        // Check chip version
        val version = readRegister(REG_VERSION)
        if (version != 0x12) {
            LoRaLogger.e(
                LoRaTags.RADIO,
                "Invalid chip version: 0x${version.toString(16)}, expected 0x12 (SX1276)"
            )
            emitEvent(LoRaEvent.Error("Invalid chip version: 0x${version.toString(16)}"))
            return false
        }
        LoRaLogger.d(LoRaTags.RADIO, "SX1276 detected (version 0x12)")

        // Set sleep mode first (required to switch to LoRa mode)
        writeRegister(REG_OP_MODE, MODE_SLEEP)
        usleep(10_000u)

        // Switch to LoRa mode (must be done in sleep mode)
        writeRegister(REG_OP_MODE, MODE_SLEEP or MODE_LORA)
        usleep(10_000u)

        // Verify LoRa mode is active
        val opMode = readRegister(REG_OP_MODE)
        if ((opMode and MODE_LORA) == 0) {
            LoRaLogger.e(LoRaTags.RADIO, "Failed to switch to LoRa mode")
            return false
        }

        // Set frequency
        // FRF = (Freq * 2^19) / 32_000_000
        val frf = ((config.frequency.toDouble() * (1 shl 19)) / 32_000_000.0).toLong()
        writeRegister(REG_FRF_MSB, ((frf shr 16) and 0xFF).toInt())
        writeRegister(REG_FRF_MID, ((frf shr 8) and 0xFF).toInt())
        writeRegister(REG_FRF_LSB, (frf and 0xFF).toInt())

        // Verify frequency was set
        val frfRead = (readRegister(REG_FRF_MSB).toLong() shl 16) or
                (readRegister(REG_FRF_MID).toLong() shl 8) or
                readRegister(REG_FRF_LSB).toLong()
        val freqRead = (frfRead * 32_000_000.0) / (1 shl 19)
        LoRaLogger.d(LoRaTags.RADIO, "Frequency set: ${freqRead / 1_000_000.0} MHz")

        // Set TX power using PA_BOOST pin
        // PA_CONFIG: PA_BOOST=1 (bit 7), MaxPower=7 (bits 4-6), OutputPower (bits 0-3)
        // OutputPower = txPower - 2 for PA_BOOST
        val outputPower = (config.txPower - 2).coerceIn(0, 15)
        writeRegister(REG_PA_CONFIG, 0x80 or 0x70 or outputPower)

        // Enable high power mode for +20dBm if requested
        if (config.txPower >= 20) {
            writeRegister(REG_PA_DAC, 0x87) // High power mode
            writeRegister(REG_OCP, 0x3B)    // Set OCP to 240mA
        } else {
            writeRegister(REG_PA_DAC, 0x84) // Default power mode
        }

        // Set spreading factor
        val sf = config.spreadingFactor.coerceIn(6, 12)
        val mc2 = (sf shl 4) or 0x04 // CRC enabled
        writeRegister(REG_MODEM_CONFIG_2, mc2)

        // Set bandwidth and coding rate
        val bw = when (config.bandwidth) {
            7_800L -> 0
            10_400L -> 1
            15_600L -> 2
            20_800L -> 3
            31_250L -> 4
            41_700L -> 5
            62_500L -> 6
            125_000L -> 7
            250_000L -> 8
            500_000L -> 9
            else -> 7 // Default to 125kHz
        }
        val cr = (config.codingRate - 4).coerceIn(1, 4)
        writeRegister(REG_MODEM_CONFIG_1, (bw shl 4) or (cr shl 1))

        // MODEM_CONFIG_3: LowDataRateOptimize (for SF11/12 with BW125), AGC auto
        val lowDataRateOptimize = if (sf >= 11 && config.bandwidth <= 125_000L) 0x08 else 0x00
        writeRegister(REG_MODEM_CONFIG_3, lowDataRateOptimize or 0x04) // AGC on

        // Set sync word
        writeRegister(REG_SYNC_WORD, config.syncWord)
        writeRegister(REG_INVERTIQ, INVERTIQ_NORMAL_REG33)
        writeRegister(REG_INVERTIQ2, INVERTIQ_NORMAL_REG3B)

        // Set preamble length
        writeRegister(REG_PREAMBLE_MSB, (config.preambleLength shr 8) and 0xFF)
        writeRegister(REG_PREAMBLE_LSB, config.preambleLength and 0xFF)

        // Set FIFO base addresses
        writeRegister(REG_FIFO_TX_BASE_ADDR, 0x00)
        writeRegister(REG_FIFO_RX_BASE_ADDR, 0x00)

        // Detection optimization for SF6-12
        if (sf == 6) {
            writeRegister(REG_DETECTION_OPTIMIZE, 0x05)
            writeRegister(REG_DETECTION_THRESHOLD, 0x0C)
        } else {
            writeRegister(REG_DETECTION_OPTIMIZE, 0x03)
            writeRegister(REG_DETECTION_THRESHOLD, 0x0A)
        }

        // Set to standby
        writeRegister(REG_OP_MODE, MODE_STDBY or MODE_LORA)

        LoRaLogger.i(
            LoRaTags.RADIO,
            "Radio configured: SF$sf, BW${config.bandwidth / 1000}kHz, CR4/${config.codingRate}, ${config.txPower}dBm"
        )
        logConfiguredRegisters()

        return true
    }

    private var maxRssiSeen = -200
    private var lastRssiLogTime = 0L

    private fun checkForReceivedPacket() {
        maybeCycleProbeProfile()

        val irqFlags = readRegister(REG_IRQ_FLAGS)
        rxPollCount++

        // Track max RSSI (log periodically, not on every spike)
        val rssiValue = readRegister(REG_RSSI_VALUE)
        val rssi = rssiValue - 157
        if (rssi > maxRssiSeen) {
            maxRssiSeen = rssi
        }

        // Debug: Log every 30 seconds (reduced from 5 seconds)
        if (rxPollCount % 1500 == 0L) {
            LoRaLogger.d(
                LoRaTags.RADIO,
                "RX poll #$rxPollCount: maxRSSI=${maxRssiSeen}dBm in last 30s"
            )
            maxRssiSeen = -200 // Reset max for next period
        }

        // Only process when RX_DONE is set
        if ((irqFlags and IRQ_RX_DONE) != 0) {
            lastRxDoneMs = getTimeMillis()
            // Clear RX done IRQ
            writeRegister(REG_IRQ_FLAGS, IRQ_RX_DONE)

            if ((irqFlags and IRQ_PAYLOAD_CRC_ERROR) != 0) {
                writeRegister(REG_IRQ_FLAGS, IRQ_PAYLOAD_CRC_ERROR)
                LoRaLogger.w(LoRaTags.RADIO, "CRC error in received packet")
                return
            }

            // Read packet length
            val packetLength = readRegister(REG_RX_NB_BYTES)
            if (packetLength <= 0 || packetLength > 255) {
                LoRaLogger.w(LoRaTags.RADIO, "Invalid packet length: $packetLength")
                return
            }

            // Get FIFO address of last packet
            val fifoAddr = readRegister(REG_FIFO_RX_CURRENT_ADDR)
            writeRegister(REG_FIFO_ADDR_PTR, fifoAddr)

            // Read payload from FIFO
            val data = ByteArray(packetLength)
            for (i in 0 until packetLength) {
                data[i] = readRegister(REG_FIFO).toByte()
            }

            // Read RSSI (packet RSSI = -157 + RegPktRssiValue for LF, -164 for HF)
            // Using -157 for 915 MHz (HF band starts at 862MHz on SX1276)
            val rssiRaw = readRegister(REG_PKT_RSSI_VALUE)
            val rssi = rssiRaw - 157

            // Read SNR (signed, in 0.25dB steps)
            val snrRaw = readRegister(REG_PKT_SNR_VALUE).toByte()
            val snr = snrRaw / 4.0f

            // Filter out noise - SF7 requires SNR > -7.5dB for reliable reception
            if (snr < -8.0f) {
                LoRaLogger.v(LoRaTags.RADIO, "Dropped noise packet: SNR=$snr dB (below -8dB threshold)")
                return
            }

            // Log with hex dump of first 32 bytes to see actual content
            val hexDump = data.take(32).joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            val asciiDump = data.take(32).map { b ->
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() || c == '_' || c == ' ') c else '.'
            }.joinToString("")

            LoRaLogger.i(LoRaTags.RADIO, "RX ${data.size}B RSSI=$rssi SNR=$snr | $hexDump")
            LoRaLogger.i(LoRaTags.RADIO, "ASCII: $asciiDump")

            emitEvent(LoRaEvent.PacketReceived(data, rssi, snr))
        }
    }

    private fun logConfiguredRegisters() {
        val modemConfig1 = readRegister(REG_MODEM_CONFIG_1)
        val modemConfig2 = readRegister(REG_MODEM_CONFIG_2)
        val modemConfig3 = readRegister(REG_MODEM_CONFIG_3)
        val syncWord = readRegister(REG_SYNC_WORD)
        val preambleMsb = readRegister(REG_PREAMBLE_MSB)
        val preambleLsb = readRegister(REG_PREAMBLE_LSB)
        val payloadLength = readRegister(REG_PAYLOAD_LENGTH)
        val frfMsb = readRegister(REG_FRF_MSB)
        val frfMid = readRegister(REG_FRF_MID)
        val frfLsb = readRegister(REG_FRF_LSB)
        val frfRead = (frfMsb.toLong() shl 16) or (frfMid.toLong() shl 8) or frfLsb.toLong()
        val freqRead = (frfRead * 32_000_000.0) / (1 shl 19)

        LoRaLogger.i(
            LoRaTags.RADIO,
            "Register dump: MC1=0x${modemConfig1.toHex()}, MC2=0x${modemConfig2.toHex()}, MC3=0x${modemConfig3.toHex()}, " +
                "SYNC=0x${syncWord.toHex()}, PREAMBLE=${(preambleMsb shl 8) or preambleLsb}, PAYLOAD_LEN=$payloadLength"
        )
        LoRaLogger.i(
            LoRaTags.RADIO,
            "Register dump: FRF_MSB=0x${frfMsb.toHex()} FRF_MID=0x${frfMid.toHex()} FRF_LSB=0x${frfLsb.toHex()} " +
                "(${freqRead / 1_000_000.0} MHz)"
        )
    }

    private fun Int.toHex(): String = this.toString(16).padStart(2, '0')

    private fun maybeCycleProbeProfile() {
        if (!probeRxScanEnabled || !isProbeScanFrequency()) return
        val now = getTimeMillis()
        if (now - lastProfileSwitchMs < PROBE_PROFILE_SWITCH_MS) return

        rxProbeProfileIndex = (rxProbeProfileIndex + 1) % PROBE_RX_PROFILES.size
        applyProbeProfile(PROBE_RX_PROFILES[rxProbeProfileIndex])
        lastProfileSwitchMs = now
    }

    private fun applyProbeProfile(profile: RxProbeProfile) {
        // Place modem in standby while changing PHY registers.
        writeRegister(REG_OP_MODE, MODE_STDBY or MODE_LORA)

        val bwBits = when (profile.bandwidthHz) {
            62_500L -> 6
            125_000L -> 7
            250_000L -> 8
            else -> 7
        }
        val crBits = 1 // 4/5
        val implicitBit = if (profile.implicitHeader) 0x01 else 0x00
        writeRegister(REG_MODEM_CONFIG_1, (bwBits shl 4) or (crBits shl 1) or implicitBit)
        writeRegister(REG_MODEM_CONFIG_2, (profile.sf shl 4) or if (profile.crcOn) 0x04 else 0x00)
        val lowDataRateOptimize = if (profile.sf >= 11 && profile.bandwidthHz <= 125_000L) 0x08 else 0x00
        writeRegister(REG_MODEM_CONFIG_3, lowDataRateOptimize or 0x04) // LDO + AGC as needed
        writeRegister(REG_SYNC_WORD, profile.syncWord)
        if (profile.invertIq) {
            writeRegister(REG_INVERTIQ, INVERTIQ_INVERTED_REG33)
            writeRegister(REG_INVERTIQ2, INVERTIQ_INVERTED_REG3B)
        } else {
            writeRegister(REG_INVERTIQ, INVERTIQ_NORMAL_REG33)
            writeRegister(REG_INVERTIQ2, INVERTIQ_NORMAL_REG3B)
        }
        if (profile.implicitHeader) {
            writeRegister(REG_PAYLOAD_LENGTH, profile.implicitPayloadLength)
        }
        writeRegister(REG_DETECTION_OPTIMIZE, 0x03)
        writeRegister(REG_DETECTION_THRESHOLD, 0x0A)

        // Clear pending IRQ and return to continuous RX.
        writeRegister(REG_IRQ_FLAGS, 0xFF)
        writeRegister(REG_OP_MODE, MODE_RX_CONTINUOUS or MODE_LORA)

        LoRaLogger.i(
            LoRaTags.RADIO,
            "Probe RX profile ${rxProbeProfileIndex + 1}/${PROBE_RX_PROFILES.size} -> " +
                "SF${profile.sf}, BW=${profile.bandwidthHz / 1000}k, sync=0x${profile.syncWord.toHex()}, " +
                "crc=${if (profile.crcOn) "on" else "off"}, " +
                "header=${if (profile.implicitHeader) "implicit" else "explicit"}, " +
                "plen=${if (profile.implicitHeader) profile.implicitPayloadLength.toString() else "var"}, " +
                "iq=${if (profile.invertIq) "inverted" else "normal"}"
        )
    }

    private fun isProbeScanFrequency(): Boolean {
        return currentConfig?.frequency == 868_125_000L
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun isProbeRxScanEnabled(): Boolean {
        val raw = getenv("BITCHAT_LORA_PROBE")?.toKString()?.trim()?.lowercase() ?: return false
        return raw == "1" || raw == "true" || raw == "yes"
    }

    /**
     * Write a value to an SX1276 register via SPI.
     * Uses full-duplex SPI transfer for reliable communication.
     */
    private fun writeRegister(register: Int, value: Int) {
        memScoped {
            val txBuf = allocArray<UByteVar>(2)
            val rxBuf = allocArray<UByteVar>(2)

            txBuf[0] = (register or 0x80).toUByte() // Write bit (MSB=1)
            txBuf[1] = value.toUByte()

            spiTransfer(txBuf, rxBuf, 2)
        }
    }

    /**
     * Read a value from an SX1276 register via SPI.
     * Uses full-duplex SPI transfer for reliable communication.
     */
    private fun readRegister(register: Int): Int {
        memScoped {
            val txBuf = allocArray<UByteVar>(2)
            val rxBuf = allocArray<UByteVar>(2)

            txBuf[0] = (register and 0x7F).toUByte() // Read bit (MSB=0)
            txBuf[1] = 0u // Dummy byte to clock out response

            spiTransfer(txBuf, rxBuf, 2)

            return rxBuf[1].toInt() and 0xFF
        }
    }

    /**
     * Perform a full-duplex SPI transfer using ioctl(SPI_IOC_MESSAGE).
     * This is the proper way to do SPI on Linux - simultaneous read/write.
     */
    private fun MemScope.spiTransfer(
        txBuf: CArrayPointer<UByteVar>,
        rxBuf: CArrayPointer<UByteVar>,
        len: Int
    ) {
        // Allocate spi_ioc_transfer structure
        // struct spi_ioc_transfer {
        //     __u64 tx_buf;           // offset 0
        //     __u64 rx_buf;           // offset 8
        //     __u32 len;              // offset 16
        //     __u32 speed_hz;         // offset 20
        //     __u16 delay_usecs;      // offset 24
        //     __u8  bits_per_word;    // offset 26
        //     __u8  cs_change;        // offset 27
        //     __u8  tx_nbits;         // offset 28
        //     __u8  rx_nbits;         // offset 29
        //     __u8  word_delay_usecs; // offset 30
        //     __u8  pad;              // offset 31
        // }; // Total: 32 bytes

        val transfer = allocArray<UByteVar>(32)

        // Zero out the structure
        for (i in 0 until 32) {
            transfer[i] = 0u
        }

        // Set tx_buf pointer (offset 0, 8 bytes)
        val txPtr = txBuf.toLong().toULong()
        for (i in 0 until 8) {
            transfer[i] = ((txPtr shr (i * 8)) and 0xFFu).toUByte()
        }

        // Set rx_buf pointer (offset 8, 8 bytes)
        val rxPtr = rxBuf.toLong().toULong()
        for (i in 0 until 8) {
            transfer[8 + i] = ((rxPtr shr (i * 8)) and 0xFFu).toUByte()
        }

        // Set len (offset 16, 4 bytes)
        transfer[16] = (len and 0xFF).toUByte()
        transfer[17] = ((len shr 8) and 0xFF).toUByte()
        transfer[18] = ((len shr 16) and 0xFF).toUByte()
        transfer[19] = ((len shr 24) and 0xFF).toUByte()

        // Set speed_hz (offset 20, 4 bytes)
        val speed = SPI_SPEED_HZ
        transfer[20] = (speed and 0xFF).toUByte()
        transfer[21] = ((speed shr 8) and 0xFF).toUByte()
        transfer[22] = ((speed shr 16) and 0xFF).toUByte()
        transfer[23] = ((speed shr 24) and 0xFF).toUByte()

        // Set bits_per_word (offset 26)
        transfer[26] = 8u

        // Perform the SPI transfer
        val result = ioctl(spiFd, SPI_IOC_MESSAGE_1, transfer)
        if (result < 0) {
            LoRaLogger.e(LoRaTags.SPI, "SPI transfer failed: ${strerror(errno)?.toKString()}")
        }
    }

    /**
     * Get current time in milliseconds (for timeout calculations).
     */
    private fun getTimeMillis(): Long {
        memScoped {
            val tv = alloc<timeval>()
            gettimeofday(tv.ptr, null)
            return tv.tv_sec * 1000L + tv.tv_usec / 1000L
        }
    }

    private fun emitEvent(event: LoRaEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    companion object {
        // Default SPI device for Orange Pi Zero 3
        // spidev1.1 = SPI1 with CS1 (hardware chip select on pin 24)
        // Note: spidev1.0 is used by the XPT2046 touch screen
        const val DEFAULT_SPI_DEVICE = "/dev/spidev1.1"

        // SPI configuration
        private const val SPI_SPEED_HZ = 1_000_000 // 1 MHz

        // SPI ioctl constants (from linux/spi/spidev.h)
        // _IOW('k', 0, sizeof(spi_ioc_transfer)) for 1 transfer
        // = 0x40 (write) | (32 << 16) | ('k' << 8) | 0
        // = 0x40206B00
        private const val SPI_IOC_MESSAGE_1: ULong = 0x40206B00uL
        private const val SPI_IOC_WR_MODE: ULong = 0x40016B01uL
        private const val SPI_IOC_WR_BITS_PER_WORD: ULong = 0x40016B03uL
        private const val SPI_IOC_WR_MAX_SPEED_HZ: ULong = 0x40046B04uL

        // SX1276 registers
        private const val REG_FIFO = 0x00
        private const val REG_OP_MODE = 0x01
        private const val REG_FRF_MSB = 0x06
        private const val REG_FRF_MID = 0x07
        private const val REG_FRF_LSB = 0x08
        private const val REG_PA_CONFIG = 0x09
        private const val REG_OCP = 0x0B
        private const val REG_LNA = 0x0C
        private const val REG_FIFO_ADDR_PTR = 0x0D
        private const val REG_FIFO_TX_BASE_ADDR = 0x0E
        private const val REG_FIFO_RX_BASE_ADDR = 0x0F
        private const val REG_FIFO_RX_CURRENT_ADDR = 0x10
        private const val REG_IRQ_FLAGS = 0x12
        private const val REG_RX_NB_BYTES = 0x13
        private const val REG_MODEM_STAT = 0x18   // Modem status (signal detected, etc.)
        private const val REG_PKT_SNR_VALUE = 0x19
        private const val REG_PKT_RSSI_VALUE = 0x1A
        private const val REG_RSSI_VALUE = 0x1B   // Current RSSI value
        private const val REG_MODEM_CONFIG_1 = 0x1D
        private const val REG_MODEM_CONFIG_2 = 0x1E
        private const val REG_PREAMBLE_MSB = 0x20
        private const val REG_PREAMBLE_LSB = 0x21
        private const val REG_PAYLOAD_LENGTH = 0x22
        private const val REG_MODEM_CONFIG_3 = 0x26
        private const val REG_DETECTION_OPTIMIZE = 0x31
        private const val REG_INVERTIQ = 0x33
        private const val REG_DETECTION_THRESHOLD = 0x37
        private const val REG_SYNC_WORD = 0x39
        private const val REG_INVERTIQ2 = 0x3B
        private const val REG_VERSION = 0x42
        private const val REG_PA_DAC = 0x4D

        // Operating modes
        private const val MODE_SLEEP = 0x00
        private const val MODE_STDBY = 0x01
        private const val MODE_TX = 0x03
        private const val MODE_RX_CONTINUOUS = 0x05
        private const val MODE_LORA = 0x80

        // IRQ flags
        private const val IRQ_TX_DONE = 0x08
        private const val IRQ_RX_DONE = 0x40
        private const val IRQ_PAYLOAD_CRC_ERROR = 0x20

        private const val PROBE_PROFILE_SWITCH_MS = 8_000L
        private const val INVERTIQ_NORMAL_REG33 = 0x27
        private const val INVERTIQ_NORMAL_REG3B = 0x1D
        private const val INVERTIQ_INVERTED_REG33 = 0x67
        private const val INVERTIQ_INVERTED_REG3B = 0x19

        private data class RxProbeProfile(
            val sf: Int,
            val syncWord: Int,
            val crcOn: Boolean,
            val bandwidthHz: Long = 125_000L,
            val implicitHeader: Boolean = false,
            val implicitPayloadLength: Int = 0,
            val invertIq: Boolean = false
        )

        private val PROBE_RX_PROFILES = listOf(
            // Explicit header profiles (most likely interop modes)
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = false, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 7, syncWord = 0x12, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 7, syncWord = 0x34, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 10, syncWord = 0x12, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 10, syncWord = 0x34, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 11, syncWord = 0x12, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 11, syncWord = 0x34, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 12, syncWord = 0x12, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 12, syncWord = 0x34, crcOn = true, bandwidthHz = 125_000L),
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = true, bandwidthHz = 250_000L),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = true, bandwidthHz = 250_000L),
            RxProbeProfile(sf = 10, syncWord = 0x12, crcOn = true, bandwidthHz = 62_500L),
            RxProbeProfile(sf = 10, syncWord = 0x34, crcOn = true, bandwidthHz = 62_500L),
            // Implicit header probes for fixed-size beacon payloads (53/54 bytes seen on RangePi)
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 53),
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 54),
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 55),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = false, implicitHeader = true, implicitPayloadLength = 53),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = false, implicitHeader = true, implicitPayloadLength = 54),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = false, implicitHeader = true, implicitPayloadLength = 55),
            RxProbeProfile(sf = 7, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 53),
            RxProbeProfile(sf = 7, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 54),
            RxProbeProfile(sf = 7, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 55),
            // Explicit+IQ inversion profiles to test opposite IQ with variable length headers.
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = true, bandwidthHz = 125_000L, invertIq = true),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = true, bandwidthHz = 125_000L, invertIq = true),
            RxProbeProfile(sf = 10, syncWord = 0x12, crcOn = true, bandwidthHz = 125_000L, invertIq = true),
            RxProbeProfile(sf = 10, syncWord = 0x34, crcOn = true, bandwidthHz = 125_000L, invertIq = true),
            // IQ inversion variants for interop with radios that invert IQ on TX.
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 53, invertIq = true),
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 54, invertIq = true),
            RxProbeProfile(sf = 9, syncWord = 0x12, crcOn = false, implicitHeader = true, implicitPayloadLength = 55, invertIq = true),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = false, implicitHeader = true, implicitPayloadLength = 53, invertIq = true),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = false, implicitHeader = true, implicitPayloadLength = 54, invertIq = true),
            RxProbeProfile(sf = 9, syncWord = 0x34, crcOn = false, implicitHeader = true, implicitPayloadLength = 55, invertIq = true)
        )
    }
}
