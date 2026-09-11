package com.bitchat.lora.bitchat.radio

import com.bitchat.lora.bitchat.logging.LoRaLogger
import com.bitchat.lora.bitchat.logging.LoRaTags
import kotlinx.cinterop.*
import platform.posix.*

/** SPI descriptor boundary used by the native radio and hardware-free lifecycle tests. */
interface SpiPort {
    fun open(device: String): Int
    fun configure(fd: Int, speed: Int): Boolean
    fun transfer(fd: Int, data: ByteArray): ByteArray
    fun close(fd: Int)
}

@OptIn(ExperimentalForeignApi::class)
internal object PosixSpiPort : SpiPort {
    override fun open(device: String): Int = platform.posix.open(device, O_RDWR)
    override fun close(fd: Int) { platform.posix.close(fd) }

    override fun configure(fd: Int, speedHz: Int): Boolean {
        memScoped {
            val mode = alloc<UByteVar>()
            mode.value = 0u // SPI_MODE_0

            // Set SPI mode
            if (ioctl(fd, SPI_IOC_WR_MODE, mode.ptr) < 0) {
                LoRaLogger.e(LoRaTags.SPI, "Failed to set SPI mode: ${strerror(errno)?.toKString()}")
                return false
            }

            // Set bits per word
            val bits = alloc<UByteVar>()
            bits.value = 8u
            if (ioctl(fd, SPI_IOC_WR_BITS_PER_WORD, bits.ptr) < 0) {
                LoRaLogger.e(LoRaTags.SPI, "Failed to set bits per word")
                return false
            }

            // Set max speed (1 MHz - SX1276 supports up to 10MHz)
            val speed = alloc<UIntVar>()
            speed.value = speedHz.toUInt()
            if (ioctl(fd, SPI_IOC_WR_MAX_SPEED_HZ, speed.ptr) < 0) {
                LoRaLogger.e(LoRaTags.SPI, "Failed to set SPI speed")
                return false
            }
        }

        LoRaLogger.d(LoRaTags.SPI, "SPI configured: mode=0, bits=8, speed=${speedHz}Hz")
        return true
    }

    override fun transfer(fd: Int, data: ByteArray): ByteArray = memScoped {
        val txBuf = allocArray<UByteVar>(data.size)
        val rxBuf = allocArray<UByteVar>(data.size)
        for (index in data.indices) {
            txBuf[index] = data[index].toUByte()
            rxBuf[index] = 0u
        }
        spiTransfer(fd, txBuf, rxBuf, data.size)
        ByteArray(data.size) { rxBuf[it].toByte() }
    }

    private fun MemScope.spiTransfer(
        fd: Int,
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
        val result = ioctl(fd, SPI_IOC_MESSAGE_1, transfer)
        if (result < 0) {
            LoRaLogger.e(LoRaTags.SPI, "SPI transfer failed: ${strerror(errno)?.toKString()}")
        }
    }


    private const val SPI_SPEED_HZ = 1_000_000
    private const val SPI_IOC_MESSAGE_1: ULong = 0x40206B00uL
    private const val SPI_IOC_WR_MODE: ULong = 0x40016B01uL
    private const val SPI_IOC_WR_BITS_PER_WORD: ULong = 0x40016B03uL
    private const val SPI_IOC_WR_MAX_SPEED_HZ: ULong = 0x40046B04uL
}
