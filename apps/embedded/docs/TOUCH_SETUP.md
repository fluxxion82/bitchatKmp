# Touch Input Setup for Orange Pi Zero 3 + Elecrow 5" HDMI Display

This document explains how touch input was configured for the embedded Bitchat app running on an Orange Pi Zero 3 with an Elecrow 5" 800x480 HDMI display with resistive touchscreen.

Navigation:
- embedded app overview: [`../README.md`](../README.md)

## Hardware Overview

- **SBC**: Orange Pi Zero 3 (Allwinner H618 SoC)
- **Display**: Elecrow 5" HDMI 800x480 with XPT2046 resistive touch controller
- **Touch Interface**: SPI (not USB)

## The Problem

The Elecrow display uses an **XPT2046** (ADS7846-compatible) SPI-based resistive touch controller. Unlike USB touchscreens that appear as `/dev/input/eventX` automatically, SPI touch requires:

1. SPI bus enabled on the SBC
2. Correct pin wiring between display and SBC
3. A driver or daemon to read SPI and create an input device

### Issue 1: SPI Not Enabled

The Orange Pi Zero 3's default device tree had SPI1 disabled. We had to **manually create** a custom device tree overlay to enable it.

**Solution**: Create the overlay file from source.

**Step 1**: Create the device tree source file:

```bash
sudo nano /tmp/spi1-enable.dts
```

Paste this content:

```dts
/dts-v1/;
/plugin/;

/ {
    compatible = "allwinner,sun50i-h618";

    fragment@0 {
        target-path = "/soc/spi@5011000";

        __overlay__ {
            status = "okay";
            #address-cells = <1>;
            #size-cells = <0>;

            spidev@0 {
                compatible = "rohm,dh2228fv";
                reg = <0>;
                spi-max-frequency = <1000000>;
            };
        };
    };
};
```

**Step 2**: Compile the overlay:

```bash
# Install device tree compiler if needed
sudo apt install device-tree-compiler

# Compile .dts to .dtbo
sudo dtc -@ -I dts -O dtb -o /boot/dtb/allwinner/overlay/sun50i-h616-spi1-enable.dtbo /tmp/spi1-enable.dts
```

**Important**: The filename must start with `sun50i-h616-` to match the `overlay_prefix` in `/boot/armbianEnv.txt`. Even though the chip is H618, Armbian uses `sun50i-h616` as the prefix.

**Step 3**: Enable the overlay in `/boot/armbianEnv.txt`:

```bash
sudo nano /boot/armbianEnv.txt
```

Add or modify the overlays line:

```
overlays=spi1-enable
```

**Step 4**: Reboot:

```bash
sudo reboot
```

**Step 5**: Verify SPI is available:

```bash
ls /dev/spidev*
# Should show: /dev/spidev1.0
```

### Issue 2: Chip Select (CS) Pin Mismatch

**This was the critical issue.**

The Elecrow display expects the touch CS (chip select) pin on **physical pin 26** of the GPIO header. However, the Orange Pi Zero 3's hardware SPI1 CS is on **physical pin 24**.

| Signal | Elecrow Expects | OPi Zero 3 SPI1 |
|--------|-----------------|-----------------|
| T_CS   | Pin 26 (PC10)   | Pin 24 (PC3)    |
| T_CLK  | Pin 23          | Pin 23 ✓        |
| T_DIN  | Pin 19          | Pin 19 ✓        |
| T_DO   | Pin 21          | Pin 21 ✓        |

**Hardware Fix Option**: Solder a jumper wire from pin 24 to pin 26.

**Software Fix (what we used)**: Use GPIO 74 (PC10, pin 26) as a software-controlled chip select instead of the hardware SPI CS.

### Issue 3: SPI Mode

The XPT2046 can work with different SPI modes. After testing:

- **Mode 2**: Returns data for Y and Z channels, but X always 0
- **Mode 0**: Returns valid data for X, Y, and Z channels ✓

We use **SPI Mode 0** for reliable operation.

## The Software Solution

Since there's no kernel driver loaded for the XPT2046, we created a **Python daemon** that:

1. Reads touch coordinates via SPI (with software CS)
2. Creates a virtual input device via uinput
3. Emits standard evdev touch events

The Kotlin/Native app then reads these events via the standard evdev interface.

### Architecture

```
┌─────────────────────┐
│   XPT2046 Touch     │
│   Controller        │
└─────────┬───────────┘
          │ SPI (Mode 0)
          │ Software CS on GPIO 74
          ▼
┌─────────────────────┐
│  Python Daemon      │
│  xpt2046_touch.py   │
│  - Reads SPI        │
│  - Creates uinput   │
└─────────┬───────────┘
          │ /dev/input/event0
          ▼
┌─────────────────────┐
│  Kotlin/Native App  │
│  TouchInput.kt      │
│  - Reads evdev      │
│  - Sends to Compose │
└─────────────────────┘
```

## Setup Instructions

### 1. Enable SPI1 (one-time setup)

```bash
# Check if SPI is available
ls /dev/spidev*

# If not, add overlay to /boot/armbianEnv.txt:
# overlays=spi1-enable

# Reboot
sudo reboot
```

### 2. Set Up GPIO for Software Chip Select

```bash
# Export GPIO 74 (PC10 = pin 26)
echo 74 | sudo tee /sys/class/gpio/export

# Set as output
echo out | sudo tee /sys/class/gpio/gpio74/direction

# Set high (CS inactive)
echo 1 | sudo tee /sys/class/gpio/gpio74/value
```

To make this persistent, add to `/etc/rc.local` or create a systemd service.

### 3. Install Python Dependencies

```bash
sudo apt install python3-spidev python3-evdev
```

### 4. Create the Touch Daemon

Save this as `~/xpt2046_touch.py`:

```python
#!/usr/bin/env python3
"""
XPT2046 SPI Touch to uinput daemon.
Reads touch events from XPT2046 via SPI and creates a virtual input device.
"""

import spidev
import time
from evdev import UInput, AbsInfo, ecodes

# Screen dimensions
SCREEN_WIDTH = 800
SCREEN_HEIGHT = 480

# Calibration values - adjust based on actual touch readings
X_MIN = 200
X_MAX = 3800
Y_MIN = 200
Y_MAX = 3800

# Touch detection thresholds for SPI Mode 0
Z_TOUCH_MIN = 100
Z_TOUCH_MAX = 2500

# GPIO chip select
GPIO_CS = "/sys/class/gpio/gpio74/value"

def cs_low():
    with open(GPIO_CS, "w") as f:
        f.write("0")
    time.sleep(0.0001)

def cs_high():
    time.sleep(0.0001)
    with open(GPIO_CS, "w") as f:
        f.write("1")

def map_value(value, in_min, in_max, out_min, out_max):
    if value < in_min:
        value = in_min
    if value > in_max:
        value = in_max
    return int((value - in_min) * (out_max - out_min) / (in_max - in_min) + out_min)

def main():
    print("[XPT2046] Starting touch daemon...")

    cs_high()
    time.sleep(0.01)

    spi = spidev.SpiDev()
    spi.open(1, 0)
    spi.max_speed_hz = 500000
    spi.mode = 0  # Mode 0 works for this setup

    capabilities = {
        ecodes.EV_ABS: [
            (ecodes.ABS_X, AbsInfo(value=0, min=0, max=SCREEN_WIDTH-1, fuzz=0, flat=0, resolution=0)),
            (ecodes.ABS_Y, AbsInfo(value=0, min=0, max=SCREEN_HEIGHT-1, fuzz=0, flat=0, resolution=0)),
            (ecodes.ABS_PRESSURE, AbsInfo(value=0, min=0, max=4095, fuzz=0, flat=0, resolution=0)),
        ],
        ecodes.EV_KEY: [ecodes.BTN_TOUCH],
    }

    ui = UInput(capabilities, name="XPT2046 Touchscreen", vendor=0x1234, product=0x5678)
    print(f"[XPT2046] Created uinput device: {ui.device.path}")

    was_touching = False

    try:
        while True:
            cs_low()
            x_resp = spi.xfer2([0xD0, 0x00, 0x00])
            y_resp = spi.xfer2([0x90, 0x00, 0x00])
            z_resp = spi.xfer2([0xB0, 0x00, 0x00])
            cs_high()

            raw_x = ((x_resp[1] << 8) | x_resp[2]) >> 3
            raw_y = ((y_resp[1] << 8) | y_resp[2]) >> 3
            raw_z = ((z_resp[1] << 8) | z_resp[2]) >> 3

            is_touching = (Z_TOUCH_MIN < raw_z < Z_TOUCH_MAX and
                           raw_x > 50 and raw_y < 4000)

            if is_touching:
                screen_x = map_value(raw_x, X_MIN, X_MAX, 0, SCREEN_WIDTH - 1)
                screen_y = map_value(raw_y, Y_MIN, Y_MAX, 0, SCREEN_HEIGHT - 1)

                if not was_touching:
                    ui.write(ecodes.EV_KEY, ecodes.BTN_TOUCH, 1)

                ui.write(ecodes.EV_ABS, ecodes.ABS_X, screen_x)
                ui.write(ecodes.EV_ABS, ecodes.ABS_Y, screen_y)
                ui.write(ecodes.EV_ABS, ecodes.ABS_PRESSURE, raw_z)
                ui.syn()
                was_touching = True

            elif was_touching:
                ui.write(ecodes.EV_KEY, ecodes.BTN_TOUCH, 0)
                ui.write(ecodes.EV_ABS, ecodes.ABS_PRESSURE, 0)
                ui.syn()
                was_touching = False

            time.sleep(0.01)  # ~100Hz polling

    except KeyboardInterrupt:
        pass
    finally:
        ui.close()
        spi.close()

if __name__ == "__main__":
    main()
```

### 5. Set Permissions

```bash
# Allow uinput access
sudo chmod 666 /dev/uinput

# Allow input device access (after daemon creates it)
sudo chmod 666 /dev/input/event0
```

### 6. Start the Daemon

```bash
# Run in foreground (for testing)
python3 ~/xpt2046_touch.py

# Run in background
nohup python3 -u ~/xpt2046_touch.py > /tmp/xpt2046.log 2>&1 &
```

### 7. Run the Bitchat App

```bash
sudo ./bitchat-embedded.kexe
```

The app will detect the touch device at `/dev/input/event0` and respond to touch input.

## Calibration

The X_MIN, X_MAX, Y_MIN, Y_MAX values in the daemon may need adjustment for your specific display. To calibrate:

1. Run the daemon with debug output
2. Touch the four corners of the screen
3. Note the raw X/Y values
4. Update the MIN/MAX values accordingly

Typical raw value ranges for XPT2046:
- X: 200-3800 (12-bit ADC, but edges are noisy)
- Y: 200-3800
- Z (pressure): 100-2500 when touching, 0-50 when not touching

## Troubleshooting

### No /dev/spidev1.0
- Check that SPI overlay is enabled in `/boot/armbianEnv.txt`
- Reboot after changes

### Touch not detected (all zeros)
- Verify GPIO 74 is set up as output
- Check SPI wiring (CLK, MOSI, MISO)
- Try different SPI modes (0, 1, 2, 3)

### Touch detected but coordinates wrong
- Swap X_MIN/X_MAX or Y_MIN/Y_MAX to flip axis
- Adjust calibration values

### App doesn't see touch device
- Ensure daemon is running: `ps aux | grep xpt2046`
- Check device exists: `ls -la /dev/input/event*`
- Check permissions on `/dev/input/event0`

## Orange Pi Zero 3 GPIO Reference

The Orange Pi Zero 3 has a 26-pin header (not 40-pin like Raspberry Pi).

Key pins for SPI1:
| Pin | Function | GPIO |
|-----|----------|------|
| 19  | SPI1_MOSI | PC2 |
| 21  | SPI1_MISO | PC0 |
| 23  | SPI1_CLK  | PC1 |
| 24  | SPI1_CS   | PC3 (hardware CS) |
| 26  | GPIO      | PC10 (software CS for Elecrow) |

GPIO number formula: `GPIO = (letter - 'A') * 32 + pin`
- PC10 = (2 * 32) + 10 = 74

## References

- [XPT2046 Datasheet](https://www.sparkfun.com/datasheets/LCD/ads7846.pdf) (ADS7846 compatible)
- [Orange Pi Zero 3 GPIO](http://www.orangepi.org/html/hardWare/computerAndMicrocontrollers/details/Orange-Pi-Zero-3.html)
- [Elecrow 5" HDMI Display](https://www.elecrow.com/5-inch-hdmi-800-x-480-capacitive-touch-lcd-display-for-raspberry-pi-pc-sony-ps4.html)
