# LoRa Hardware Testing Guide

Scripts for testing RFM95W LoRa module on Orange Pi Zero 3.

## Quick Start

### 1. Copy scripts to Orange Pi

```bash
scp lora_test.py gpio_discover.sh sterling@192.168.6.210:~/
```

### 2. SSH to Orange Pi

```bash
ssh sterling@192.168.6.210
```

### 3. Install dependencies

```bash
sudo apt update
sudo apt install python3-spidev python3-rpi.gpio gpiod libgpiod-utils
```

### 4. Stop touch daemon (if running)

The XPT2046 touch daemon uses spidev1.0. Stop it to avoid conflicts:

```bash
sudo pkill -f xpt2046_touch.py
```

### 5. Run the test

```bash
chmod +x lora_test.py

# Basic test (verify SPI and chip) - uses spidev1.1 by default
sudo python3 lora_test.py --skip-gpio

# Full test with transmission
sudo python3 lora_test.py --skip-gpio --transmit
```

### 6. Restart touch daemon

```bash
nohup python3 -u ~/xpt2046_touch.py > /tmp/xpt2046.log 2>&1 &
```

## Expected Output

### Success
```
[OK] SPI device exists: /dev/spidev0.0
[OK] SPI device is readable/writable
[OK] GPIO initialized (reset pin: 74)
[OK] Reset toggled (pin 74)
[OK] SPI opened: bus=0, device=0, speed=500kHz
[OK] Chip version: 0x12 (SX1276/RFM95W detected)
[OK] Configured for 915 MHz (SF7, BW125, CR4/5, +17dBm)
[OK] TX complete! (took 45.3ms)
```

### Common Failures

| Symptom | Likely Cause | Fix |
|---------|--------------|-----|
| `Chip version: 0x00` | Module not powered or wrong wiring | Check 3.3V power, MOSI/MISO/SCK connections |
| `Chip version: 0xFF` | CS line issue or SPI disabled | Verify CS wiring, check SPI overlay enabled |
| `SPI device not found` | SPI not enabled | Enable SPI in device tree overlay |
| `Permission denied` | Need root | Run with `sudo` |
| `TX timeout` | Module stuck or antenna issue | Check antenna, try reset |

## Verify with HackRF

On a separate machine with HackRF:

```bash
# Capture raw IQ data
hackrf_transfer -r capture.raw -f 915000000 -s 2000000

# Or use gqrx
# Set frequency to 915 MHz, look for chirp spread spectrum
```

LoRa signals have a distinctive "chirp" pattern - frequency sweeps visible as diagonal lines in waterfall.

## Hardware Wiring Reference

**IMPORTANT**: The Orange Pi Zero 3 with touch screen uses:
- **spidev1.0** (CS0) → Touch screen (XPT2046)
- **spidev1.1** (CS1) → LoRa module (RFM95W)

| RFM95W Pin | Orange Pi Pin | GPIO | Function |
|------------|---------------|------|----------|
| VCC | Pin 1 (3.3V) | - | Power |
| GND | Pin 6 (GND) | - | Ground |
| MOSI | Pin 19 | PC2 | SPI1 data out |
| MISO | Pin 21 | PC0 | SPI1 data in |
| SCK | Pin 23 | PC1 | SPI1 clock |
| NSS | Pin 24 | PC3 | SPI1 CS (hardware) |
| RESET | (optional) | - | Not needed with --skip-gpio |
| DIO0 | (optional) | - | For interrupt-driven RX |

**Note**: If you have an Elecrow touch display, its T_CS uses GPIO 74 (Pin 26) via software CS.

## Troubleshooting GPIO

If the default GPIO pins don't work:

1. Run `sudo ./gpio_discover.sh` to see available GPIOs
2. Try alternative pin numbers:
   ```bash
   # Different base calculations
   sudo python3 lora_test.py --reset-pin 10   # Just the offset
   sudo python3 lora_test.py --reset-pin 266  # Alternative base
   ```

3. Test GPIO independently:
   ```bash
   sudo python3 lora_test.py --gpio-test
   ```

4. Skip GPIO entirely to test SPI:
   ```bash
   sudo python3 lora_test.py --skip-gpio
   ```

## Files

- `lora_test.py` - Main hardware test script
- `gpio_discover.sh` - GPIO mapping discovery helper
- `LORA_TESTING.md` - This documentation
