# Bluetooth Low Energy on linuxArm64

This document covers building and using BLE support for the linuxArm64 embedded app. BLE enables mesh networking with iOS and Android bitchat clients.

## Overview

The embedded app uses BlueZ (the official Linux Bluetooth stack) via two C libraries:

| Library | Role | Capabilities |
|---------|------|--------------|
| **GattLib** | Central role (client) | Scanning, connecting, GATT read/write, notifications |
| **D-Bus** | Peripheral role (server) | Advertising, GATT server, accepting connections |

GattLib only supports Central role operations, so we use D-Bus directly for Peripheral role (advertising and hosting GATT services).

## Quick Start

### 1. Build Native Libraries

The master build script handles everything:

```bash
./scripts/build-native-linux-arm64.sh
```

This builds:
- libsodium, secp256k1, noise-c (crypto)
- GattLib (BLE client library)

### 2. Build the Embedded App

```bash
./gradlew :apps:embedded:linkDebugExecutableLinuxArm64
```

### 3. Deploy and Run

```bash
# Copy to device (replace with your device's IP/hostname)
scp apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe user@device:/tmp/

# Run (requires root for Bluetooth D-Bus access)
ssh user@device 'sudo /tmp/bitchat-embedded.kexe'
```

## Board-Specific Setup

Different ARM64 boards have different Bluetooth chips that require specific initialization steps.

### Orange Pi Zero3 (Unisoc UWE5622)

The Orange Pi Zero3 uses an AW859A module with Unisoc UWE5622 WiFi/BT combo chip. This chip requires a kernel module to create the serial device before BlueZ can use it.

**Quick Setup:**
```bash
# Load the Bluetooth serial driver (creates /dev/ttyBT0)
sudo modprobe sprdbt_tty

# Make it persistent across reboots
echo "sprdbt_tty" | sudo tee /etc/modules-load.d/sprdbt.conf

# Restart the Orange Pi Bluetooth service (initializes chip via /dev/ttyBT0)
sudo systemctl restart aw859a-bluetooth

# Restart main Bluetooth service
sudo systemctl restart bluetooth

# Verify Bluetooth is working
hciconfig
# Should show: hci0: Type: Primary  Bus: UART  ... UP RUNNING
```

**One-liner for fresh device:**
```bash
sudo modprobe sprdbt_tty && \
echo "sprdbt_tty" | sudo tee /etc/modules-load.d/sprdbt.conf && \
sudo systemctl restart aw859a-bluetooth && \
sudo systemctl restart bluetooth && \
hciconfig
```

**Troubleshooting Orange Pi:**
- If `hciconfig` shows nothing, check `/dev/ttyBT0` exists: `ls -la /dev/ttyBT0`
- If `/dev/ttyBT0` doesn't exist, the `sprdbt_tty` module isn't loaded
- Check `aw859a-bluetooth` service: `sudo systemctl status aw859a-bluetooth`

### Raspberry Pi (Built-in or USB Bluetooth)

Raspberry Pi typically has Bluetooth working out of the box. If not:

```bash
# For Pi 3/4/5 with built-in Bluetooth
sudo systemctl enable hciuart
sudo systemctl start hciuart
sudo systemctl restart bluetooth
```

---

## Prerequisites (All Boards)

### Install BlueZ and Runtime Libraries

```bash
sudo apt update
sudo apt install -y \
    bluez \
    bluetooth \
    libbluetooth3 \
    libglib2.0-0 \
    libdbus-1-3

# Enable and start Bluetooth service
sudo systemctl enable bluetooth
sudo systemctl start bluetooth
```

### Configure BlueZ for BLE

Edit `/etc/bluetooth/main.conf`:

```ini
[General]
# Enable BLE mode (or "dual" for both BR/EDR and LE)
ControllerMode = le

# Allow experimental features (GATT server registration)
Experimental = true

# Device name shown during advertising
Name = bitchat-pi
```

Restart Bluetooth:
```bash
sudo systemctl restart bluetooth
```

### Verify BLE is Working

```bash
# Check adapter
hciconfig

# Should show UP RUNNING with LE features
# Look for: "LE" in features list

# Test scanning
sudo bluetoothctl
[bluetooth]# scan le
# You should see nearby BLE devices
```

### User Permissions

By default, Bluetooth requires root. For non-root access:

```bash
# Add user to bluetooth group
sudo usermod -aG bluetooth $USER

# Re-login for group changes to take effect
```

Alternatively, grant capabilities:
```bash
sudo setcap 'cap_net_raw,cap_net_admin+eip' /path/to/bitchat-embedded.kexe
```

## Build Details

### Docker Image

The cross-compilation Docker image (`docker/Dockerfile.linux-arm64-cross`) includes:

- GLib 2.68.4 headers
- D-Bus 1.12.20 headers
- BlueZ 5.66 headers

These are extracted into `/opt/arm64-sysroot/usr/include/` for cross-compilation.

### Building GattLib

GattLib is included as a git submodule:

```bash
# Initialize submodule (if not already done)
git submodule update --init data/remote/transport/bluetooth/native/gattlib
```

The build script cross-compiles GattLib:

```bash
docker run --platform linux/amd64 --rm \
    -v "$(pwd)/data/remote/transport/bluetooth/native:/build" \
    bitchat-linux-arm64-cross \
    bash /build/build-gattlib-linux-arm64.sh
```

**Output:** `data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib/libgattlib.a`

### Cinterop Definitions

The Kotlin/Native bindings are defined in:

| File | Purpose |
|------|---------|
| `data/remote/transport/bluetooth/src/nativeInterop/cinterop/gattlib.def` | GattLib GATT client API |
| `data/remote/transport/bluetooth/src/nativeInterop/cinterop/dbus.def` | D-Bus IPC for GATT server |

## Architecture

### Service UUIDs

Compatible with iOS/Android bitchat clients:

```kotlin
const val SERVICE_UUID = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
```

### Chunking Protocol

BLE has limited MTU (~512 bytes). Large messages are chunked:

| Chunk Type | Byte Value | Format |
|------------|------------|--------|
| CHUNK_START | 0xFC (-4) | `[type][totalSize:4-BE][payload]` |
| CHUNK_CONTINUE | 0xFD (-3) | `[type][payload]` |
| CHUNK_END | 0xFE (-2) | `[type][payload]` |

Max payload per chunk: 499 bytes
Delay between chunks: 25ms

### Kotlin Service Classes

```
BlueZManager            -- Shared adapter and D-Bus connection
    |
    +-- BlueZScanningService      -- CentralScanningService (GattLib)
    +-- BlueZGattClientService    -- GattClientService (GattLib)
    +-- BlueZGattServerService    -- GattServerService (D-Bus)
    +-- BlueZAdvertisingService   -- AdvertisingService (D-Bus)
    |
BlueZConnectionService  -- BluetoothConnectionService (orchestrator)
```

## D-Bus GATT Server

Since GattLib only supports Central role, we implement GATT server using BlueZ's D-Bus API directly.

### D-Bus Object Tree

```
/org/bitchat/gatt
    └── service0                     (org.bluez.GattService1)
        └── char0                    (org.bluez.GattCharacteristic1)
```

### Required D-Bus Interfaces

**org.bluez.GattApplication1:**
- `GetManagedObjects()` - Returns object tree

**org.bluez.GattService1:**
- UUID property
- Primary property

**org.bluez.GattCharacteristic1:**
- UUID property
- Service property (path to parent service)
- Flags property (`["read", "write", "notify"]`)
- `ReadValue(options)` method
- `WriteValue(value, options)` method
- `StartNotify()` method
- `StopNotify()` method

### Registration Flow

1. Create D-Bus objects with introspection XML
2. Register method handlers for ReadValue/WriteValue
3. Call `org.bluez.GattManager1.RegisterApplication()`
4. Handle incoming connections via method calls

### Advertising (LEAdvertisingManager1)

```
/org/bitchat/advertisement0        (org.bluez.LEAdvertisement1)
    Type: "peripheral"
    LocalName: "bitchat"
    ServiceUUIDs: ["F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"]
```

Register with:
```
org.bluez.LEAdvertisingManager1.RegisterAdvertisement(path, options)
```

## Troubleshooting

### "Adapter not found"

```bash
# Check if adapter is present
hciconfig -a

# If down, bring it up
sudo hciconfig hci0 up
```

### "Permission denied"

```bash
# Run as root
sudo /tmp/bitchat-embedded.kexe

# Or add user to bluetooth group
sudo usermod -aG bluetooth $USER
```

### "D-Bus connection failed"

```bash
# Check D-Bus system bus is running
systemctl status dbus

# Check BlueZ is running
systemctl status bluetooth
```

### "Advertising failed"

```bash
# Check if advertising is supported
sudo btmgmt info

# Look for "le" and "advertising" in supported features
```

### "GATT server registration failed"

```bash
# Enable experimental features in BlueZ
sudo nano /etc/bluetooth/main.conf
# Set: Experimental = true

sudo systemctl restart bluetooth
```

### Debugging with bluetoothctl

```bash
sudo bluetoothctl

# Power on adapter
power on

# Enable scanning
scan le

# Show discovered devices
devices

# Connect to device
connect XX:XX:XX:XX:XX:XX

# Show GATT services
menu gatt
list-attributes
```

### Debugging with D-Bus

```bash
# Watch BlueZ D-Bus traffic
sudo dbus-monitor --system "sender='org.bluez'"

# Introspect BlueZ
dbus-send --system --print-reply \
    --dest=org.bluez /org/bluez \
    org.freedesktop.DBus.Introspectable.Introspect
```

## Output Locations

| Item | Path |
|------|------|
| GattLib static library | `data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib/libgattlib.a` |
| GattLib headers | `data/remote/transport/bluetooth/native/gattlib/include/gattlib.h` |
| Cinterop definitions | `data/remote/transport/bluetooth/src/nativeInterop/cinterop/*.def` |
| Kotlin BLE services | `data/remote/transport/bluetooth/src/linuxMain/kotlin/com/bitchat/bluetooth/service/BlueZ*.kt` |

## References

- [BlueZ D-Bus GATT API](https://git.kernel.org/pub/scm/bluetooth/bluez.git/tree/doc/gatt-api.txt)
- [BlueZ D-Bus Advertising API](https://git.kernel.org/pub/scm/bluetooth/bluez.git/tree/doc/advertising-api.txt)
- [GattLib GitHub](https://github.com/labapart/gattlib)
- [gobbledegook (C++ GATT server example)](https://github.com/nettlep/gobbledegook)
- [Punchthrough BLE Tutorial](https://punchthrough.com/creating-a-ble-peripheral-with-bluez/)
