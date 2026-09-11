#!/usr/bin/env python3
"""Read-only RFM95W identity probe for the Orange Pi Zero 3 PCB.

SPI1.1 uses header 19/21/23/24 (PH7/PH8/PH6/PH9). DIO0 is header
11 / GPIO 70. RESET is header 7 / GPIO 73, shared with the PMIC interrupt:
this tool never requests GPIO or resets the module. Stop all radio owners
before probing. --configure and --transmit explicitly allow register writes.

Dependency: sudo apt install python3-spidev
See apps/embedded/docs/ORANGEPI_ZERO3_PCB.md and scripts/LORA_TESTING.md.
"""

import argparse
import os
import re
import sys
import time

# SX1276 Register Addresses
REG_FIFO = 0x00
REG_OP_MODE = 0x01
REG_FRF_MSB = 0x06
REG_FRF_MID = 0x07
REG_FRF_LSB = 0x08
REG_PA_CONFIG = 0x09
REG_OCP = 0x0B
REG_LNA = 0x0C
REG_FIFO_ADDR_PTR = 0x0D
REG_FIFO_TX_BASE_ADDR = 0x0E
REG_FIFO_RX_BASE_ADDR = 0x0F
REG_FIFO_RX_CURRENT_ADDR = 0x10
REG_IRQ_FLAGS_MASK = 0x11
REG_IRQ_FLAGS = 0x12
REG_RX_NB_BYTES = 0x13
REG_MODEM_STAT = 0x18
REG_PKT_SNR_VALUE = 0x19
REG_PKT_RSSI_VALUE = 0x1A
REG_MODEM_CONFIG_1 = 0x1D
REG_MODEM_CONFIG_2 = 0x1E
REG_PREAMBLE_MSB = 0x20
REG_PREAMBLE_LSB = 0x21
REG_PAYLOAD_LENGTH = 0x22
REG_MODEM_CONFIG_3 = 0x26
REG_DETECTION_OPTIMIZE = 0x31
REG_DETECTION_THRESHOLD = 0x37
REG_SYNC_WORD = 0x39
REG_DIO_MAPPING_1 = 0x40
REG_DIO_MAPPING_2 = 0x41
REG_VERSION = 0x42
REG_PA_DAC = 0x4D

# Operating Modes
MODE_SLEEP = 0x00
MODE_STDBY = 0x01
MODE_FSTX = 0x02
MODE_TX = 0x03
MODE_FSRX = 0x04
MODE_RX_CONTINUOUS = 0x05
MODE_RX_SINGLE = 0x06
MODE_CAD = 0x07
MODE_LORA = 0x80  # LoRa mode bit

# IRQ Flags
IRQ_CAD_DETECTED = 0x01
IRQ_FHSS_CHANGE = 0x02
IRQ_CAD_DONE = 0x04
IRQ_TX_DONE = 0x08
IRQ_VALID_HEADER = 0x10
IRQ_PAYLOAD_CRC_ERROR = 0x20
IRQ_RX_DONE = 0x40
IRQ_RX_TIMEOUT = 0x80


class LoRaTest:
    """Probe identity, or explicitly configure/transmit with verified register writes."""

    def __init__(self, spi_device="/dev/spidev1.1", skip_gpio=True):
        self.spi_device = spi_device
        # Kept for callers using the former --skip-gpio option; reset is always disabled.
        self.spi = None
        self.identified = False
        self.configured = False

    def log(self, status, message):
        print(f"[{status}] {message}")

    def check_prerequisites(self):
        if not os.path.exists(self.spi_device):
            self.log("FAIL", f"SPI device not found: {self.spi_device}")
            self.log("INFO", "Check SPI1 overlays; do not replace the SPI0 flash binding.")
            return False
        return True

    def open_spi(self):
        try:
            import spidev
            match = re.fullmatch(r"/dev/spidev(\d+)\.(\d+)", self.spi_device)
            if match is None:
                raise ValueError("Expected a device path such as /dev/spidev1.1")
            self.spi = spidev.SpiDev()
            self.spi.open(int(match[1]), int(match[2]))
            self.spi.max_speed_hz = 500000
            self.spi.mode = 0
            self.spi.bits_per_word = 8
            self.log("OK", f"SPI opened: {self.spi_device}, mode 0, 500 kHz")
            return True
        except (ImportError, OSError, ValueError) as error:
            self.log("FAIL", f"SPI open failed: {error}")
            return False

    def read_register(self, register):
        return self.spi.xfer2([register & 0x7F, 0])[1]

    def write_register(self, register, value):
        if not self.identified:
            raise RuntimeError("Refusing register write before successful identification")
        self.spi.xfer2([register | 0x80, value & 0xFF])

    def write_verified(self, register, value, mask=0xFF):
        self.write_register(register, value)
        actual = self.read_register(register)
        if (actual & mask) != (value & mask):
            raise RuntimeError(f"Register 0x{register:02X} readback 0x{actual:02X}, expected 0x{value:02X} (mask 0x{mask:02X})")

    def check_chip_version(self):
        self.identified = False
        versions = [self.read_register(REG_VERSION) for _ in range(10)]
        self.log("INFO", "RegVersion samples: " + " ".join(f"0x{v:02X}" for v in versions))
        self.identified = all(value == 0x12 for value in versions)
        if self.identified:
            self.log("OK", "Consistent SX1276 identity (10/10 reads); this does not verify OTA messaging")
        else:
            self.log("FAIL", "Expected ten 0x12 samples; configuration and TX are disabled")
        return self.identified

    def configure_915mhz(self):
        """Explicit test profile: 915 MHz, SF7, BW125, CR4/5, +17 dBm."""
        self.configured = False
        try:
            # LongRangeMode may only be changed while asleep. Preserve the old mode
            # selection for the first write, then select LoRa in sleep.
            self.write_verified(REG_OP_MODE, self.read_register(REG_OP_MODE) & 0xF8)
            time.sleep(0.01)
            self.write_verified(REG_OP_MODE, MODE_LORA | MODE_SLEEP)
            time.sleep(0.01)
            frf = int(915_000_000 * (1 << 19) / 32_000_000)
            values = [
                (REG_FRF_MSB, (frf >> 16) & 0xFF),
                (REG_FRF_MID, (frf >> 8) & 0xFF), (REG_FRF_LSB, frf & 0xFF),
                (REG_PA_CONFIG, 0x8F), (REG_PA_DAC, 0x84), (REG_OCP, 0x2B),
                (REG_MODEM_CONFIG_1, 0x72), (REG_MODEM_CONFIG_2, 0x74),
                (REG_MODEM_CONFIG_3, 0x04), (REG_SYNC_WORD, 0x12),
                (REG_PREAMBLE_MSB, 0x00), (REG_PREAMBLE_LSB, 0x08),
                (REG_DETECTION_THRESHOLD, 0x0A),
                (REG_FIFO_TX_BASE_ADDR, 0), (REG_FIFO_RX_BASE_ADDR, 0),
            ]
            for register, value in values:
                self.write_verified(register, value)
            # Preserve reserved bits in RegDetectOptimize.
            detect = (self.read_register(REG_DETECTION_OPTIMIZE) & 0xF8) | 0x03
            self.write_verified(REG_DETECTION_OPTIMIZE, detect)
            self.write_verified(REG_OP_MODE, MODE_STDBY | MODE_LORA)
            self.configured = True
            self.log("OK", "Configuration readback verified: 915 MHz, SF7, BW125, CR4/5, +17 dBm")
            return True
        except Exception as error:
            self.log("FAIL", f"Configuration failed: {error}")
            return False

    def transmit_packet(self, data=b"BITCHAT_TEST"):
        if not self.configured:
            self.log("FAIL", "TX requires successful configuration readback")
            return False
        try:
            self.write_verified(REG_OP_MODE, MODE_STDBY | MODE_LORA)
            self.write_register(REG_IRQ_FLAGS, 0xFF)  # write-one-to-clear, not a normal readback register
            self.write_verified(REG_FIFO_ADDR_PTR, 0)
            for byte in data:
                self.write_register(REG_FIFO, byte)  # FIFO advances on access
            self.write_verified(REG_PAYLOAD_LENGTH, len(data))
            self.log("INFO", f"Sending {len(data)} bytes at 915 MHz: {data!r}")
            self.write_register(REG_OP_MODE, MODE_TX | MODE_LORA)
            start = time.monotonic()
            while time.monotonic() - start < 5:
                if self.read_register(REG_IRQ_FLAGS) & IRQ_TX_DONE:
                    self.write_register(REG_IRQ_FLAGS, IRQ_TX_DONE)
                    self.log("OK", "Radio reported TX done; receiver delivery is not verified")
                    return True
                time.sleep(0.001)
            self.log("FAIL", "TX timeout (5 seconds)")
            return False
        except Exception as error:
            self.log("FAIL", f"Transmit failed: {error}")
            return False
        finally:
            # Explicit TX may leave the radio transmitting on failure; stop it.
            self.write_register(REG_OP_MODE, MODE_STDBY | MODE_LORA)

    def dump_registers(self):
        """Dump key registers for debugging."""
        print("\n=== Register Dump ===")
        regs = [
            ("OP_MODE", REG_OP_MODE),
            ("FRF_MSB", REG_FRF_MSB),
            ("FRF_MID", REG_FRF_MID),
            ("FRF_LSB", REG_FRF_LSB),
            ("PA_CONFIG", REG_PA_CONFIG),
            ("MODEM_CONFIG_1", REG_MODEM_CONFIG_1),
            ("MODEM_CONFIG_2", REG_MODEM_CONFIG_2),
            ("MODEM_CONFIG_3", REG_MODEM_CONFIG_3),
            ("IRQ_FLAGS", REG_IRQ_FLAGS),
            ("SYNC_WORD", REG_SYNC_WORD),
            ("VERSION", REG_VERSION),
        ]
        for name, addr in regs:
            val = self.read_register(addr)
            print(f"  {name:20s} (0x{addr:02X}): 0x{val:02X}")

    def cleanup(self):
        """Close the descriptor without changing radio state."""
        if self.spi is not None:
            spi, self.spi = self.spi, None
            spi.close()

    def run_test(self, transmit=False, dump=False, configure=False):
        self.log("INFO", "Software reset disabled for the current PCB; no GPIO access")
        try:
            if not self.check_prerequisites() or not self.open_spi():
                return False
            identified = self.check_chip_version()
            if dump:
                self.dump_registers()
            if not identified:
                return False
            if configure or transmit:
                if not self.configure_915mhz():
                    return False
            return self.transmit_packet() if transmit else True
        except Exception as error:
            self.log("FAIL", f"Diagnostic failed: {error}")
            return False
        finally:
            self.cleanup()


def main():
    parser = argparse.ArgumentParser(description="Read-only RFM95W probe for the Orange Pi Zero 3 PCB. Stop all radio owners before use.")
    parser.add_argument("--spi-device", default="/dev/spidev1.1", help="SPI device path (default: /dev/spidev1.1)")
    parser.add_argument("--configure", action="store_true", help="Explicitly write and verify the 915 MHz test configuration; does not transmit")
    parser.add_argument("--transmit", "-t", action="store_true", help="Explicitly configure and transmit BITCHAT_TEST at 915 MHz (+17 dBm); requires a suitable antenna")
    parser.add_argument("--dump", action="store_true", help="Read key registers without configuring the radio")
    parser.add_argument("--skip-gpio", action="store_true", help="Compatibility no-op: GPIO reset is always disabled")
    parser.add_argument("--gpio-test", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--reset-pin", type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.gpio_test or args.reset_pin is not None:
        parser.error("GPIO reset testing is unavailable for this PCB: RESET shares the PMIC interrupt. No GPIO pins will be driven.")
    tester = LoRaTest(spi_device=args.spi_device)
    return 0 if tester.run_test(transmit=args.transmit, dump=args.dump, configure=args.configure) else 1


if __name__ == "__main__":
    sys.exit(main())
