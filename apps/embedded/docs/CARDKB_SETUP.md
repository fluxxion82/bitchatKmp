# CardKb I2C Keyboard Setup for Orange Pi Zero 3

This guide explains how to set up the M5Stack CardKB (I2C keyboard) with the Orange Pi Zero 3 running Armbian.

Navigation:
- embedded app overview: [`../README.md`](../README.md)

## Overview

The CardKb is an I2C keyboard at address `0x5F`. Since Linux applications expect `/dev/input/event*` devices, we use a userspace driver that:

1. Reads key presses from CardKb via I2C
2. Creates a virtual keyboard device via Linux uinput
3. Injects key events that appear as standard input devices

This makes the keyboard work system-wide (console, apps, SSH, everything).

## Hardware Wiring

Connect the CardKb to the Orange Pi Zero 3's 40-pin header:

| CardKb Pin | Orange Pi Pin | Description |
|------------|---------------|-------------|
| VCC        | Pin 1 (3.3V)  | Power       |
| GND        | Pin 6 (GND)   | Ground      |
| SDA        | Pin 3 (GPIO 229 / PH5) | I2C Data |
| SCL        | Pin 5 (GPIO 228 / PH4) | I2C Clock |

## I2C Configuration

### Step 1: Enable I2C3-PH Overlay

The Orange Pi Zero 3's GPIO header uses I2C3 on PH pins. Edit `/boot/armbianEnv.txt`:

```bash
sudo nano /boot/armbianEnv.txt
```

Add or modify the overlays line:

```
overlay_prefix=sun50i-h616
overlays=i2c3-ph
```

If you have other overlays, append `i2c3-ph` to the list:

```
overlays=spi1-enable i2c3-ph
```

Reboot for changes to take effect:

```bash
sudo reboot
```

### Step 2: Verify I2C Device

After reboot, verify `/dev/i2c-2` exists (I2C3 becomes i2c-2 in Linux):

```bash
ls -la /dev/i2c-*
```

Expected output:
```
crw-rw---- 1 root i2c 89, 0 ... /dev/i2c-0
crw-rw---- 1 root i2c 89, 1 ... /dev/i2c-1
crw-rw---- 1 root i2c 89, 2 ... /dev/i2c-2
```

### Step 3: Scan for CardKb

```bash
sudo apt install i2c-tools
sudo i2cdetect -y 2
```

You should see `5f` at row 50, column f:

```
     0  1  2  3  4  5  6  7  8  9  a  b  c  d  e  f
50: -- -- -- -- -- -- -- -- -- -- -- -- -- -- -- 5f
```

## Driver Installation

### Quick Install

Run the installation script:

```bash
cd /path/to/bitchatKmp/scripts/cardkb
sudo ./install-cardkb.sh
```

### Manual Installation

#### 1. Install Dependencies

```bash
sudo apt update
sudo apt install python3-pip python3-smbus i2c-tools
sudo pip3 install python-uinput smbus2
```

#### 2. Load uinput Module

```bash
sudo modprobe uinput
echo "uinput" | sudo tee /etc/modules-load.d/uinput.conf
```

#### 3. Install Driver

```bash
sudo mkdir -p /opt/bitchat
sudo cp cardkb-driver.py /opt/bitchat/
sudo chmod +x /opt/bitchat/cardkb-driver.py
```

#### 4. Install and Enable Service

```bash
sudo cp cardkb.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable cardkb
sudo systemctl start cardkb
```

## Verification

### Check Service Status

```bash
sudo systemctl status cardkb
```

### Test with evtest

```bash
sudo apt install evtest
sudo evtest
```

Select the "CardKb-I2C" device and press keys on the CardKb.

### Verify Virtual Keyboard Exists

```bash
ls /dev/input/by-id/ | grep -i card
```

## Troubleshooting

### CardKb Not Detected (i2cdetect shows nothing at 0x5F)

1. Check wiring - SDA to Pin 3, SCL to Pin 5
2. Verify 3.3V power (not 5V)
3. Ensure `i2c3-ph` overlay is enabled and system rebooted

### /dev/i2c-2 Doesn't Exist

1. Verify overlay in `/boot/armbianEnv.txt`
2. Check kernel messages: `dmesg | grep i2c`
3. Reboot after adding overlay

### Driver Starts But No Key Events

1. Check driver output: `sudo journalctl -u cardkb -f`
2. Verify uinput module loaded: `lsmod | grep uinput`
3. Test I2C read manually:
   ```bash
   sudo python3 -c "import smbus2; b=smbus2.SMBus(2); print(hex(b.read_byte(0x5f)))"
   ```

### Permission Denied Errors

The service runs as root. For manual testing, use `sudo`.

## Technical Details

### I2C Bus Numbering

- I2C0: Internal system use
- I2C1: Internal (PMIC at 0x36)
- I2C2: Created by `i2c3-ph` overlay (GPIO header)

Note: Linux numbers I2C buses sequentially, so I2C3 hardware becomes `/dev/i2c-2`.

### CardKb Protocol

The CardKb sends a single byte per key press:
- `0x00`: No key pressed
- ASCII codes for letters/numbers/symbols
- Special codes: `0xB4` (Left), `0xB5` (Up), `0xB6` (Down), `0xB7` (Right)
- `0x08` (Backspace), `0x0D` (Enter), `0x1B` (Escape), `0x7F` (Delete)

### Polling Rate

The driver polls at 10ms intervals (100Hz), which provides responsive input while minimizing CPU usage.

## Files

| File | Location | Purpose |
|------|----------|---------|
| `cardkb-driver.py` | `/opt/bitchat/` | Main driver script |
| `cardkb.service` | `/etc/systemd/system/` | systemd service unit |

## References

- [Armbian Forum: I2C on Orange Pi Zero 3](https://forum.armbian.com/topic/31493-how-to-enable-i2c3-on-orange-pi-zero-3/)
- [linux-sunxi.org: Orange Pi Zero3](https://linux-sunxi.org/Xunlong_Orange_Pi_Zero3)
- [M5Stack CardKB Documentation](https://docs.m5stack.com/en/unit/cardkb)
