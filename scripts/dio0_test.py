#!/usr/bin/env python3
"""
DIO0 GPIO Test Script for RFM95W on Orange Pi Zero 3

Tests whether the DIO0 pin (GPIO70/PC6) toggles when TX completes.
This verifies the wiring between the RFM95W DIO0 pin and the Orange Pi.

The SX1276 DIO0 pin goes HIGH when TxDone interrupt triggers.
meshtasticd uses GPIO interrupts on this pin to detect TX completion.
If DIO0 doesn't toggle, meshtasticd will timeout with "busyTx" errors.

Hardware:
  - RFM95W DIO0 → Pin 11 (PC6, GPIO line 70)
  - Uses gpiod v2.x API for GPIO access

Usage:
  sudo systemctl stop meshtasticd  # Stop meshtasticd first!
  sudo python3 dio0_test.py

Expected output if DIO0 is working:
  DIO0 before TX: 0
  TX_DONE at Xms, IRQ=0x08, DIO0=1  ← DIO0 should be 1
  DIO0 final: 0

If DIO0 stays 0 throughout:
  - Wire not connected properly
  - Wrong GPIO pin configured
  - Try a different physical pin

Dependencies:
  sudo apt install python3-spidev python3-libgpiod
"""

import sys
import time

try:
    import gpiod
    GPIOD_V2 = hasattr(gpiod, 'request_lines')
except ImportError:
    print("ERROR: python3-libgpiod not installed")
    print("Install with: sudo apt install python3-libgpiod")
    sys.exit(1)

try:
    import spidev
except ImportError:
    print("ERROR: python3-spidev not installed")
    print("Install with: sudo apt install python3-spidev")
    sys.exit(1)

# SX1276 Register Addresses
REG_FIFO = 0x00
REG_OP_MODE = 0x01
REG_FRF_MSB = 0x06
REG_FRF_MID = 0x07
REG_FRF_LSB = 0x08
REG_PA_CONFIG = 0x09
REG_FIFO_ADDR_PTR = 0x0D
REG_FIFO_TX_BASE_ADDR = 0x0E
REG_IRQ_FLAGS = 0x12
REG_MODEM_CONFIG_1 = 0x1D
REG_MODEM_CONFIG_2 = 0x1E
REG_PAYLOAD_LENGTH = 0x22
REG_MODEM_CONFIG_3 = 0x26
REG_DIO_MAPPING_1 = 0x40
REG_VERSION = 0x42

# Operating Modes
MODE_SLEEP = 0x00
MODE_STDBY = 0x01
MODE_TX = 0x03
MODE_LORA = 0x80

# IRQ Flags
IRQ_TX_DONE = 0x08

# GPIO Configuration for Orange Pi Zero 3
# Pin 11 = PC6 = gpiochip1 line 70
GPIO_CHIP = "/dev/gpiochip1"
DIO0_LINE = 70  # PC6


def get_gpio_value_v2(chip_path, line_num):
    """Get GPIO value using gpiod v2 API."""
    with gpiod.request_lines(
        chip_path,
        consumer="dio0_test",
        config={line_num: gpiod.LineSettings(direction=gpiod.line.Direction.INPUT)}
    ) as request:
        return request.get_value(line_num)


def main():
    print("=" * 60)
    print("DIO0 GPIO Test - Verifying TX Done Interrupt Wiring")
    print("=" * 60)
    print()
    print(f"Using gpiod v{gpiod.__version__} (v2 API: {GPIOD_V2})")
    print()

    # Open GPIO and get initial value
    print(f"[1] Opening GPIO: {GPIO_CHIP} line {DIO0_LINE}")
    try:
        initial_value = get_gpio_value_v2(GPIO_CHIP, DIO0_LINE)
        print(f"    GPIO opened successfully")
        print(f"    DIO0 initial value: {initial_value.value if hasattr(initial_value, 'value') else initial_value}")
    except Exception as e:
        print(f"    ERROR: Failed to open GPIO: {e}")
        print(f"    Check that {GPIO_CHIP} line {DIO0_LINE} is correct")
        print(f"    Run: gpioinfo -c gpiochip1 | grep 'line  7'")
        return 1
    print()

    # Open SPI
    print("[2] Opening SPI: /dev/spidev1.1")
    try:
        spi = spidev.SpiDev()
        spi.open(1, 1)
        spi.max_speed_hz = 500000
        spi.mode = 0
        print(f"    SPI opened successfully")
    except Exception as e:
        print(f"    ERROR: Failed to open SPI: {e}")
        return 1

    # Verify chip
    version = spi.xfer2([REG_VERSION, 0x00])[1]
    print(f"    Chip version: 0x{version:02X}", end="")
    if version == 0x12:
        print(" (SX1276/RFM95W)")
    else:
        print(" (UNEXPECTED - should be 0x12)")
        spi.close()
        return 1
    print()

    # Configure radio
    print("[3] Configuring radio for TX")

    # Set standby + LoRa mode
    spi.xfer2([REG_OP_MODE | 0x80, MODE_SLEEP | MODE_LORA])
    time.sleep(0.01)
    spi.xfer2([REG_OP_MODE | 0x80, MODE_STDBY | MODE_LORA])
    time.sleep(0.01)

    # Set 915 MHz
    spi.xfer2([REG_FRF_MSB | 0x80, 0xE4])
    spi.xfer2([REG_FRF_MID | 0x80, 0xC0])
    spi.xfer2([REG_FRF_LSB | 0x80, 0x00])

    # Set PA config (+17 dBm)
    spi.xfer2([REG_PA_CONFIG | 0x80, 0x8F])

    # Set modem config (SF7, BW125, CR4/5)
    spi.xfer2([REG_MODEM_CONFIG_1 | 0x80, 0x72])
    spi.xfer2([REG_MODEM_CONFIG_2 | 0x80, 0x74])
    spi.xfer2([REG_MODEM_CONFIG_3 | 0x80, 0x04])

    # Clear IRQ flags
    spi.xfer2([REG_IRQ_FLAGS | 0x80, 0xFF])

    # Map DIO0 to TxDone (bits 7-6 = 01)
    # DIO0 mapping: 00=RxDone, 01=TxDone, 10=CadDone
    spi.xfer2([REG_DIO_MAPPING_1 | 0x80, 0x40])

    dio_mapping = spi.xfer2([REG_DIO_MAPPING_1, 0x00])[1]
    dio0_mode = (dio_mapping >> 6) & 0x03
    print(f"    DIO Mapping 1: 0x{dio_mapping:02X} (DIO0 mode: {dio0_mode})")
    if dio0_mode != 1:
        print("    WARNING: DIO0 not mapped to TxDone!")
    print()

    # Write test data to FIFO
    print("[4] Writing test data to FIFO")
    spi.xfer2([REG_FIFO_TX_BASE_ADDR | 0x80, 0x00])
    spi.xfer2([REG_FIFO_ADDR_PTR | 0x80, 0x00])

    test_data = b"DIO0_TEST"
    for byte in test_data:
        spi.xfer2([REG_FIFO | 0x80, byte])

    spi.xfer2([REG_PAYLOAD_LENGTH | 0x80, len(test_data)])
    print(f"    Wrote {len(test_data)} bytes: {test_data}")
    print()

    # Check DIO0 before TX
    print("[5] Starting TX and monitoring DIO0")
    dio0_before = get_gpio_value_v2(GPIO_CHIP, DIO0_LINE)
    dio0_before_val = dio0_before.value if hasattr(dio0_before, 'value') else dio0_before
    print(f"    DIO0 before TX: {dio0_before_val}")

    # Start TX
    spi.xfer2([REG_OP_MODE | 0x80, MODE_TX | MODE_LORA])
    print("    TX started...")

    # Poll both GPIO and IRQ register
    tx_done = False
    dio0_at_done = None

    for i in range(100):  # 1 second max (10ms * 100)
        time.sleep(0.01)

        gpio_val = get_gpio_value_v2(GPIO_CHIP, DIO0_LINE)
        gpio_val_int = gpio_val.value if hasattr(gpio_val, 'value') else gpio_val
        irq = spi.xfer2([REG_IRQ_FLAGS, 0x00])[1]

        if irq & IRQ_TX_DONE:
            tx_done = True
            dio0_at_done = gpio_val_int
            print(f"    TX_DONE at {(i+1)*10}ms, IRQ=0x{irq:02X}, DIO0={gpio_val_int}")

            # Clear IRQ
            spi.xfer2([REG_IRQ_FLAGS | 0x80, IRQ_TX_DONE])
            break

        # Log progress every 100ms
        if (i + 1) % 10 == 0:
            print(f"      {(i+1)*10}ms: IRQ=0x{irq:02X}, DIO0={gpio_val_int}")

    if not tx_done:
        print("    TX TIMEOUT! (1 second)")
        print(f"    Final IRQ: 0x{spi.xfer2([REG_IRQ_FLAGS, 0x00])[1]:02X}")

    # Return to standby
    spi.xfer2([REG_OP_MODE | 0x80, MODE_STDBY | MODE_LORA])

    # Final GPIO check
    time.sleep(0.01)
    dio0_final = get_gpio_value_v2(GPIO_CHIP, DIO0_LINE)
    dio0_final_val = dio0_final.value if hasattr(dio0_final, 'value') else dio0_final
    print(f"    DIO0 final: {dio0_final_val}")
    print()

    # Cleanup
    spi.close()

    # Analysis
    print("=" * 60)
    print("ANALYSIS")
    print("=" * 60)

    if not tx_done:
        print("FAIL: TX did not complete")
        print("  - Check antenna connection")
        print("  - Check power supply")
        return 1
    elif dio0_at_done == 1 or dio0_at_done == gpiod.line.Value.ACTIVE:
        print("SUCCESS: DIO0 went HIGH when TX completed!")
        print("  - GPIO interrupt wiring is correct")
        print("  - meshtasticd should work with this configuration")
        return 0
    else:
        print("FAIL: DIO0 stayed LOW during TX")
        print("  - TX completed (IRQ register shows TxDone)")
        print("  - But DIO0 GPIO did not toggle")
        print()
        print("Possible causes:")
        print(f"  1. DIO0 wire not connected to Pin 11 (PC6)")
        print(f"  2. Wrong GPIO line - try different pins:")
        print(f"     Pin 12 (PC7)  = line 71")
        print(f"     Pin 13 (PC10) = line 74")
        print(f"     Pin 15 (PC11) = line 75")
        print(f"     Pin 16 (PC12) = line 76")
        print(f"  3. Physical connection issue - check soldering/jumper")
        return 1


if __name__ == "__main__":
    sys.exit(main())
