#!/usr/bin/env python3
"""
RFM95W LoRa Hardware Test Script for Orange Pi Zero 3

Tests SPI communication with RFM95W (SX1276 chip) to verify hardware wiring.
Run on Orange Pi Zero 3 with RFM95W connected via SPI0.

Hardware Setup:
  - SPI0: MOSI→19, MISO→21, SCK→23, CS→24 → /dev/spidev0.0
  - RESET: Pin 7 (PC10, GPIO ~74)
  - DIO0: Pin 11 (PC11, GPIO ~75) [optional for this test]

Dependencies:
  sudo apt install python3-spidev python3-rpi.gpio libgpiod-utils
  # Or: pip3 install spidev RPi.GPIO

Usage:
  sudo python3 lora_test.py
  sudo python3 lora_test.py --transmit  # Send test packet
  sudo python3 lora_test.py --gpio-test # Only test GPIO toggle
  sudo python3 lora_test.py --skip-gpio # Skip GPIO reset (for testing)
"""

import argparse
import sys
import time
from typing import Optional

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

# GPIO Pin Configuration for Orange Pi Zero 3
# NOTE: These may need adjustment based on actual hardware mapping
# Use `gpioinfo` or `cat /sys/kernel/debug/gpio` to verify
RESET_PIN = 74  # Physical Pin 7 → PC10 → likely gpiochip1 offset 10 → GPIO 74
DIO0_PIN = 75   # Physical Pin 11 → PC11 → likely gpiochip1 offset 11 → GPIO 75

# Alternative GPIO numbers if the above don't work
# The Orange Pi Zero 3 uses Allwinner H618, GPIO calculation:
# PC0 = base + 64 (GPIOC base), so PC10 = 64 + 10 = 74
ALT_RESET_PIN_OPTIONS = [74, 10, 266, 267]  # Try these if 74 doesn't work


class LoRaTest:
    """Test harness for RFM95W LoRa module."""

    def __init__(self, spi_device: str = "/dev/spidev0.0",
                 reset_pin: int = RESET_PIN,
                 skip_gpio: bool = False):
        self.spi_device = spi_device
        self.reset_pin = reset_pin
        self.skip_gpio = skip_gpio
        self.spi = None
        self.gpio_available = False
        self.gpio_backend = None

    def log(self, status: str, message: str):
        """Print status message with color coding."""
        colors = {
            "OK": "\033[92m",      # Green
            "FAIL": "\033[91m",    # Red
            "WARN": "\033[93m",    # Yellow
            "INFO": "\033[94m",    # Blue
            "DEBUG": "\033[90m",   # Gray
        }
        reset = "\033[0m"
        color = colors.get(status, "")
        print(f"{color}[{status}]{reset} {message}")

    def check_prerequisites(self) -> bool:
        """Verify SPI device and GPIO access."""
        import os

        # Check SPI device exists
        if os.path.exists(self.spi_device):
            self.log("OK", f"SPI device exists: {self.spi_device}")
        else:
            self.log("FAIL", f"SPI device not found: {self.spi_device}")
            self.log("INFO", "Ensure SPI is enabled in device tree/overlay")
            self.log("INFO", "Check: ls -la /dev/spidev*")
            return False

        # Check we can open it
        if os.access(self.spi_device, os.R_OK | os.W_OK):
            self.log("OK", f"SPI device is readable/writable")
        else:
            self.log("WARN", f"SPI device permissions issue - may need sudo")

        return True

    def setup_gpio(self) -> bool:
        """Initialize GPIO for reset pin control."""
        if self.skip_gpio:
            self.log("INFO", "Skipping GPIO setup (--skip-gpio)")
            return True

        try:
            import RPi.GPIO as GPIO
            GPIO.setmode(GPIO.BCM)
            GPIO.setwarnings(False)
            GPIO.setup(self.reset_pin, GPIO.OUT)
            self.gpio_available = True
            self.gpio_backend = "rpi"
            self.log("OK", f"GPIO initialized (reset pin: {self.reset_pin})")
            return True
        except ImportError:
            self.log("WARN", "RPi.GPIO not available, trying gpiod fallback")
            return self._setup_gpio_sysfs()
        except Exception as e:
            self.log("WARN", f"RPi.GPIO failed: {e}")
            return self._setup_gpio_sysfs()

    def _setup_gpio_sysfs(self) -> bool:
        """Fallback GPIO control via sysfs."""
        import os

        gpio_path = f"/sys/class/gpio/gpio{self.reset_pin}"
        export_path = "/sys/class/gpio/export"

        try:
            # Export GPIO if not already exported
            if not os.path.exists(gpio_path):
                with open(export_path, 'w') as f:
                    f.write(str(self.reset_pin))
                time.sleep(0.1)

            # Set direction to output
            with open(f"{gpio_path}/direction", 'w') as f:
                f.write("out")

            self.gpio_available = True
            self.gpio_backend = "sysfs"
            self.log("OK", f"GPIO via sysfs (pin {self.reset_pin})")
            return True

        except PermissionError:
            self.log("FAIL", "GPIO sysfs permission denied - run with sudo")
            return False
        except FileNotFoundError:
            self.log("FAIL", f"GPIO {self.reset_pin} not available via sysfs")
            self.log("INFO", "Try alternative pins or check GPIO chip mapping")
            self.log("INFO", "Run: cat /sys/kernel/debug/gpio")
            return False
        except Exception as e:
            self.log("FAIL", f"GPIO sysfs failed: {e}")
            return False

    def toggle_reset(self) -> bool:
        """Toggle reset pin to initialize the module."""
        if self.skip_gpio:
            self.log("INFO", "Skipping reset toggle (--skip-gpio)")
            return True

        if not self.gpio_available:
            self.log("WARN", "GPIO not available, skipping reset")
            return True  # Continue anyway

        try:
            if self.gpio_backend == "rpi":
                import RPi.GPIO as GPIO
                # Reset sequence: HIGH -> LOW -> HIGH
                GPIO.output(self.reset_pin, GPIO.HIGH)
                time.sleep(0.01)
                GPIO.output(self.reset_pin, GPIO.LOW)
                time.sleep(0.01)
                GPIO.output(self.reset_pin, GPIO.HIGH)
                time.sleep(0.05)  # Wait for module to initialize
            elif self.gpio_backend == "sysfs":
                # sysfs fallback
                gpio_path = f"/sys/class/gpio/gpio{self.reset_pin}/value"
                with open(gpio_path, 'w') as f:
                    f.write("1")
                time.sleep(0.01)
                with open(gpio_path, 'w') as f:
                    f.write("0")
                time.sleep(0.01)
                with open(gpio_path, 'w') as f:
                    f.write("1")
                time.sleep(0.05)
            else:
                self.log("WARN", "Unknown GPIO backend, skipping reset")
                return True

            self.log("OK", f"Reset toggled (pin {self.reset_pin})")
            return True

        except Exception as e:
            self.log("FAIL", f"Reset toggle failed: {e}")
            return False

    def open_spi(self) -> bool:
        """Open SPI device."""
        try:
            import spidev
            self.spi = spidev.SpiDev()

            # Parse device path to get bus and device numbers
            # e.g., /dev/spidev0.0 -> bus=0, device=0
            parts = self.spi_device.split('spidev')[-1].split('.')
            bus = int(parts[0])
            device = int(parts[1])

            self.spi.open(bus, device)
            self.spi.max_speed_hz = 500000  # 500 kHz
            self.spi.mode = 0b00  # SPI mode 0
            self.spi.bits_per_word = 8

            self.log("OK", f"SPI opened: bus={bus}, device={device}, speed=500kHz")
            return True

        except ImportError:
            self.log("FAIL", "spidev module not available")
            self.log("INFO", "Install: sudo apt install python3-spidev")
            return False
        except Exception as e:
            self.log("FAIL", f"SPI open failed: {e}")
            return False

    def read_register(self, register: int) -> int:
        """Read a single register from the SX1276."""
        # SPI read: address with bit 7 clear, then dummy byte
        response = self.spi.xfer2([register & 0x7F, 0x00])
        return response[1]

    def write_register(self, register: int, value: int):
        """Write a single register to the SX1276."""
        # SPI write: address with bit 7 set, then value
        self.spi.xfer2([(register | 0x80), value & 0xFF])

    def check_chip_version(self) -> bool:
        """Read and verify chip version register."""
        version = self.read_register(REG_VERSION)

        if version == 0x12:
            self.log("OK", f"Chip version: 0x{version:02X} (SX1276/RFM95W detected)")
            return True
        elif version == 0x00:
            self.log("FAIL", f"Chip version: 0x{version:02X} (module not responding)")
            self.log("INFO", "Check: Power supply, MOSI/MISO wiring, CS line")
            return False
        elif version == 0xFF:
            self.log("FAIL", f"Chip version: 0x{version:02X} (bus held high)")
            self.log("INFO", "Check: CS line not connected or SPI not enabled")
            return False
        else:
            self.log("WARN", f"Chip version: 0x{version:02X} (unexpected, expected 0x12)")
            self.log("INFO", "May be different chip variant or wiring issue")
            return False

    def configure_915mhz(self) -> bool:
        """Configure module for 915 MHz US ISM band transmission."""
        try:
            # Set sleep mode first
            self.write_register(REG_OP_MODE, MODE_SLEEP)
            time.sleep(0.01)

            # Enable LoRa mode (must be done in sleep mode)
            self.write_register(REG_OP_MODE, MODE_SLEEP | MODE_LORA)
            time.sleep(0.01)

            # Verify LoRa mode is set
            mode = self.read_register(REG_OP_MODE)
            if not (mode & MODE_LORA):
                self.log("FAIL", f"Failed to set LoRa mode (got 0x{mode:02X})")
                return False

            # Set frequency to 915.0 MHz
            # FRF = (Freq * 2^19) / 32 MHz
            freq_hz = 915_000_000
            frf = int((freq_hz * (1 << 19)) / 32_000_000)
            self.write_register(REG_FRF_MSB, (frf >> 16) & 0xFF)
            self.write_register(REG_FRF_MID, (frf >> 8) & 0xFF)
            self.write_register(REG_FRF_LSB, frf & 0xFF)

            # Verify frequency
            frf_read = (self.read_register(REG_FRF_MSB) << 16 |
                       self.read_register(REG_FRF_MID) << 8 |
                       self.read_register(REG_FRF_LSB))
            freq_read = (frf_read * 32_000_000) / (1 << 19)
            self.log("DEBUG", f"Frequency set: {freq_read/1e6:.3f} MHz (FRF=0x{frf_read:06X})")

            # Set TX power (PA_BOOST pin, +17 dBm)
            # PA_CONFIG = 0x8F: PA_BOOST=1, MaxPower=7, OutputPower=15 → +17dBm
            self.write_register(REG_PA_CONFIG, 0x8F)

            # Enable PA_DAC for +20 dBm (optional, more power)
            self.write_register(REG_PA_DAC, 0x87)  # High power mode

            # Set OCP to 240mA
            self.write_register(REG_OCP, 0x3B)

            # Set spreading factor 7, BW 125kHz, CR 4/5
            # MODEM_CONFIG_1: BW=7 (125kHz), CR=1 (4/5), ImplicitHeader=0
            self.write_register(REG_MODEM_CONFIG_1, 0x72)  # BW=125kHz, CR=4/5

            # MODEM_CONFIG_2: SF=7, TxContinuous=0, CRC=on
            self.write_register(REG_MODEM_CONFIG_2, 0x74)  # SF7, CRC on

            # MODEM_CONFIG_3: LowDataRateOptimize=off, AGC=on
            self.write_register(REG_MODEM_CONFIG_3, 0x04)

            # Set sync word (0x12 for private networks, 0x34 for LoRaWAN)
            self.write_register(REG_SYNC_WORD, 0x12)

            # Set preamble length (8 symbols)
            self.write_register(REG_PREAMBLE_MSB, 0x00)
            self.write_register(REG_PREAMBLE_LSB, 0x08)

            # Detection optimize for SF7-12
            self.write_register(REG_DETECTION_OPTIMIZE, 0x03)
            self.write_register(REG_DETECTION_THRESHOLD, 0x0A)

            # Set FIFO base addresses
            self.write_register(REG_FIFO_TX_BASE_ADDR, 0x00)
            self.write_register(REG_FIFO_RX_BASE_ADDR, 0x00)

            # Go to standby mode
            self.write_register(REG_OP_MODE, MODE_STDBY | MODE_LORA)
            time.sleep(0.01)

            self.log("OK", "Configured for 915 MHz (SF7, BW125, CR4/5, +17dBm)")
            return True

        except Exception as e:
            self.log("FAIL", f"Configuration failed: {e}")
            return False

    def transmit_packet(self, data: bytes = b"BITCHAT_TEST") -> bool:
        """Transmit a test packet."""
        try:
            # Ensure we're in standby mode
            self.write_register(REG_OP_MODE, MODE_STDBY | MODE_LORA)
            time.sleep(0.001)

            # Clear IRQ flags
            self.write_register(REG_IRQ_FLAGS, 0xFF)

            # Set FIFO pointer to TX base
            self.write_register(REG_FIFO_ADDR_PTR, 0x00)

            # Write data to FIFO
            for byte in data:
                self.write_register(REG_FIFO, byte)

            # Set payload length
            self.write_register(REG_PAYLOAD_LENGTH, len(data))

            self.log("INFO", f"Sending {len(data)} bytes: {data}")

            # Switch to TX mode
            self.write_register(REG_OP_MODE, MODE_TX | MODE_LORA)

            # Wait for TX done (poll IRQ flags)
            start_time = time.time()
            timeout = 5.0  # 5 second timeout

            while (time.time() - start_time) < timeout:
                irq_flags = self.read_register(REG_IRQ_FLAGS)

                if irq_flags & IRQ_TX_DONE:
                    # Clear the TX done flag
                    self.write_register(REG_IRQ_FLAGS, IRQ_TX_DONE)
                    elapsed = (time.time() - start_time) * 1000
                    self.log("OK", f"TX complete! (took {elapsed:.1f}ms)")

                    # Return to standby
                    self.write_register(REG_OP_MODE, MODE_STDBY | MODE_LORA)
                    return True

                time.sleep(0.001)  # 1ms poll interval

            self.log("FAIL", "TX timeout (5 seconds)")
            self.log("DEBUG", f"IRQ flags at timeout: 0x{self.read_register(REG_IRQ_FLAGS):02X}")
            self.log("DEBUG", f"OP mode at timeout: 0x{self.read_register(REG_OP_MODE):02X}")
            return False

        except Exception as e:
            self.log("FAIL", f"Transmit failed: {e}")
            return False

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
        """Clean up resources."""
        if self.spi:
            try:
                # Put module in sleep mode
                self.write_register(REG_OP_MODE, MODE_SLEEP | MODE_LORA)
            except:
                pass
            self.spi.close()

        if self.gpio_available and not self.skip_gpio:
            try:
                import RPi.GPIO as GPIO
                GPIO.cleanup()
            except:
                pass

    def run_test(self, transmit: bool = False, dump: bool = False) -> bool:
        """Run the full test sequence."""
        print("=" * 60)
        print("RFM95W LoRa Hardware Test")
        print("=" * 60)
        print()

        success = True

        # Step 1: Check prerequisites
        print("--- Step 1: Prerequisites ---")
        if not self.check_prerequisites():
            return False
        print()

        # Step 2: Setup GPIO
        print("--- Step 2: GPIO Setup ---")
        self.setup_gpio()  # Continue even if this fails
        print()

        # Step 3: Toggle reset
        print("--- Step 3: Reset Module ---")
        self.toggle_reset()  # Continue even if this fails
        print()

        # Step 4: Open SPI
        print("--- Step 4: Open SPI ---")
        if not self.open_spi():
            return False
        print()

        # Step 5: Check chip version
        print("--- Step 5: Verify Chip ---")
        if not self.check_chip_version():
            success = False
            # Continue to try other tests
        print()

        # Step 6: Configure for 915 MHz
        print("--- Step 6: Configure 915 MHz ---")
        if not self.configure_915mhz():
            success = False
        print()

        # Optional: Register dump
        if dump:
            self.dump_registers()
            print()

        # Step 7: Transmit (optional)
        if transmit and success:
            print("--- Step 7: Transmit Test ---")
            if not self.transmit_packet():
                success = False
            print()

        # Summary
        print("=" * 60)
        if success:
            self.log("OK", "All tests passed!")
            if not transmit:
                print()
                print("To transmit a test packet, run:")
                print("  sudo python3 lora_test.py --transmit")
                print()
                print("To verify with HackRF (on another machine):")
                print("  hackrf_transfer -r capture.raw -f 915000000 -s 2000000")
        else:
            self.log("FAIL", "Some tests failed - see above for details")
        print("=" * 60)

        return success


def test_gpio_only(reset_pin: int):
    """Test GPIO functionality only."""
    print("=" * 60)
    print("GPIO Toggle Test")
    print("=" * 60)
    print()

    print(f"Testing GPIO pin: {reset_pin}")
    print("This will toggle the pin HIGH -> LOW -> HIGH")
    print()

    try:
        import RPi.GPIO as GPIO
        GPIO.setmode(GPIO.BCM)
        GPIO.setwarnings(False)
        GPIO.setup(reset_pin, GPIO.OUT)

        for i in range(3):
            print(f"Cycle {i+1}: HIGH...", end=" ", flush=True)
            GPIO.output(reset_pin, GPIO.HIGH)
            time.sleep(0.5)
            print("LOW...", end=" ", flush=True)
            GPIO.output(reset_pin, GPIO.LOW)
            time.sleep(0.5)
            print("HIGH")
            GPIO.output(reset_pin, GPIO.HIGH)
            time.sleep(0.5)

        GPIO.cleanup()
        print()
        print("[OK] GPIO test completed - verify with multimeter/LED")

    except ImportError:
        print("[FAIL] RPi.GPIO not available")
        print("Install: sudo apt install python3-rpi.gpio")
    except Exception as e:
        print(f"[FAIL] GPIO test failed: {e}")


def main():
    parser = argparse.ArgumentParser(
        description="Test RFM95W LoRa module on Orange Pi Zero 3"
    )
    parser.add_argument(
        "--spi-device",
        default="/dev/spidev1.1",
        help="SPI device path (default: /dev/spidev1.1 for Orange Pi Zero 3)"
    )
    parser.add_argument(
        "--reset-pin",
        type=int,
        default=RESET_PIN,
        help=f"GPIO pin for reset (default: {RESET_PIN})"
    )
    parser.add_argument(
        "--transmit", "-t",
        action="store_true",
        help="Transmit a test packet after configuration"
    )
    parser.add_argument(
        "--gpio-test",
        action="store_true",
        help="Only test GPIO toggle (for debugging)"
    )
    parser.add_argument(
        "--skip-gpio",
        action="store_true",
        help="Skip GPIO reset (for testing SPI only)"
    )
    parser.add_argument(
        "--dump",
        action="store_true",
        help="Dump register values after configuration"
    )

    args = parser.parse_args()

    # GPIO-only test mode
    if args.gpio_test:
        test_gpio_only(args.reset_pin)
        return 0

    # Run full test
    tester = LoRaTest(
        spi_device=args.spi_device,
        reset_pin=args.reset_pin,
        skip_gpio=args.skip_gpio
    )

    try:
        success = tester.run_test(transmit=args.transmit, dump=args.dump)
        return 0 if success else 1
    finally:
        tester.cleanup()


if __name__ == "__main__":
    sys.exit(main())
