# MeshCore on Orange Pi Zero 3 — Setup Guide

This documents how to set up an Orange Pi Zero 3 as a MeshCore companion radio node running the bitchatKmp embedded app. The Orange Pi connects an SX1276/RFM95W LoRa radio via SPI and runs `meshcored` (the MeshCore C++ companion daemon). The Kotlin embedded app connects to meshcored over TCP (port 5000) and handles messaging logic.

See also:
- Meshtastic alternative path: [`meshtastic-orangepi-setup.md`](meshtastic-orangepi-setup.md)

## Hardware

- **Orange Pi Zero 3** (Allwinner H618, aarch64)
- **RFM95W / SX1276** LoRa radio module (915 MHz band)
- Wiring: RFM95W connected to SPI1.1 with DIO0 on GPIO 70 and RESET on GPIO 71 (gpiochip1)

### SPI Wiring (Orange Pi Zero 3 to RFM95W)

| RFM95W Pin | Orange Pi Pin | Notes |
|------------|---------------|-------|
| MOSI       | SPI1 MOSI     | |
| MISO       | SPI1 MISO     | |
| SCK        | SPI1 SCLK     | |
| NSS/CS     | SPI1 CS1      | Directly mapped to `/dev/spidev1.1` |
| DIO0 (IRQ) | gpiochip1 pin 70 | Interrupt pin for RX/TX complete |
| RESET      | gpiochip1 pin 71 | Radio hardware reset |
| VCC        | 3.3V          | |
| GND        | GND           | |

## OS Setup

The Orange Pi runs Armbian (Debian-based, aarch64):
```
Armbian_community 26.2.0-trunk.332 forky
Linux 6.12.x-current-sunxi64
```

### Enable SPI

Ensure SPI1 is enabled in the device tree / Armbian config. Verify:
```bash
ls /dev/spidev1.*
# Should show: /dev/spidev1.0  /dev/spidev1.1
```

### Install build dependencies

```bash
sudo apt-get install -y libbluetooth-dev libgpiod-dev openssl libssl-dev \
    libusb-1.0-0-dev libi2c-dev libuv1-dev
```

### Install PlatformIO

```bash
python3 -m venv ~/.local/venv
source ~/.local/venv/bin/activate
pip install --no-cache-dir -U platformio
# Or install to ~/.local/bin:
pip install --user platformio
```

Ensure `pio` is in your PATH:
```bash
export PATH=$HOME/.local/bin:$PATH
```

## Resource Contention: meshcored vs meshtasticd

> **meshcored and meshtasticd cannot run simultaneously.** Both daemons use the same SPI device (`/dev/spidev1.1`) and GPIO lines (70, 71). If one is running, the other will fail to claim the GPIO lines and crash-loop.

Always stop one before starting the other:

```bash
# Switch to meshcored
sudo systemctl stop meshtasticd && sudo systemctl start meshcored

# Switch to meshtasticd
sudo systemctl stop meshcored && sudo systemctl start meshtasticd
```

If either daemon is crash-looping, check that the other is stopped:
```bash
sudo systemctl status meshtasticd meshcored
```

## Source Code

meshcored is built from a fork of the MeshCore project with patches for Orange Pi Zero 3 + SX1276:

```bash
git clone -b orangepi-zero3-sx1276 https://github.com/fluxxion82/MeshCore.git meshcore-linux
cd meshcore-linux
```

> **Note:** The upstream MeshCore project does not support SX1276 on Orange Pi out of the box.
> Use the `orangepi-zero3-sx1276` fork branch above for the full Orange Pi patchset.

### Orange Pi + SX1276 patchset (already in fork branch)

The fork branch already includes the Orange Pi Zero 3 + SX1276 changes below. You do not need to re-apply them manually if you clone the branch above:

| File | Change | Why |
|------|--------|-----|
| `variants/linux/LinuxBoard.cpp` | `gpioInit(256)` instead of default 64 | Default GPIO range too small for pins 70/71 on gpiochip1 |
| `variants/linux/LinuxBoard.h` | Added `gpio_chip` field (default `"gpiochip0"`) | Allows runtime gpiochip selection via ini config |
| `variants/linux/platformio.ini` | New `[env:linux_companion_sx1276]` target | SX1276-specific build environment |
| `variants/linux/target.cpp` | `resetAGC()` call after radio param changes | Fixes radio stuck in STANDBY after `CMD_SET_RADIO_PARAMS` (see Troubleshooting) |
| `variants/linux/target.h` | Conditional includes for `LinuxSX1276Wrapper` | Build support for SX1276 radio class |
| `examples/companion_radio/main.cpp` | `PORTDUINO_PLATFORM` support with `LinuxTcpInterface` | Enables TCP companion interface on Linux |
| `examples/companion_radio/DataStore.cpp` | Compilation fix for portduino | Resolves build error on aarch64 |

## Building meshcored

On the Orange Pi (or cross-compile and copy):

```bash
cd ~/meshcore-linux
FIRMWARE_VERSION=dev ./build.sh build-firmware linux_companion_sx1276
```

Output: `./out/meshcored`

### What `linux_companion_sx1276` builds

The PlatformIO environment (`variants/linux/platformio.ini`) configures:
- `RADIO_CLASS=LinuxSX1276` — SX1276-specific RadioLib driver
- `WRAPPER_CLASS=LinuxSX1276Wrapper` — MeshCore radio wrapper
- `SKIP_CONFIG_OVERWRITE=1` — runtime ini overrides compile-time defaults
- `TCP_PORT=5000` — companion TCP port for the Kotlin app
- `MESH_DEBUG=1` — enables debug logging

Compile-time radio defaults (overridden by `meshcored.ini` at runtime):
```
LORA_FREQ=910.525  LORA_BW=62.5  LORA_SF=7  LORA_CR=5
```

## Configuring meshcored

### meshcored.ini

Create `/etc/meshcored/meshcored.ini` (or place in meshcored's working directory):

```ini
advert_name = "OrangePi Companion"
admin_password = "your_password_here"
lat = 0.0
lon = 0.0

# Orange Pi Zero 3 with RFM95W on SPI1.1
# GPIO pins are on gpiochip1 (H616/H618)
spidev = /dev/spidev1.1
gpio_chip = gpiochip1
lora_irq_pin = 70
lora_reset_pin = 71
#lora_nss_pin =     # SS handled by spidev
#lora_busy_pin =    # SX1276 has no busy pin

# Radio parameters — MUST match the MeshCore phone network
lora_freq = 910.525
lora_bw = 62.5
lora_sf = 7
lora_cr = 5
lora_tx_power = 10
```

**Critical: Radio parameters must match exactly.** The MeshCore phone network uses BW=62.5kHz, SF=7, CR=5 at 910.525 MHz. If these don't match the phones, the radio will transmit but nothing will decode on either side.

### Systemd service

Create `/etc/systemd/system/meshcored.service`:

```ini
[Unit]
Description=MeshCore Companion Radio Daemon
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/meshcored
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal

# GPIO/SPI access requires root
User=root
WorkingDirectory=/root

[Install]
WantedBy=multi-user.target
```

Install and start:
```bash
sudo cp ./out/meshcored /usr/local/bin/meshcored
sudo mkdir -p /etc/meshcored
sudo cp orangepi_zero3.ini /etc/meshcored/meshcored.ini
sudo systemctl daemon-reload
sudo systemctl enable --now meshcored
```

### Verify meshcored is running

```bash
sudo systemctl status meshcored
sudo journalctl -u meshcored --no-pager -n 50
```

Look for:
- `RadioLibWrapper: noise_floor = -10x` — radio is in RX mode, actively listening
- No GPIO claim errors

## Building the Kotlin embedded app

On macOS (cross-compiles to linuxArm64):

```bash
cd bitchatKmp
./gradlew :apps:embedded:linkReleaseExecutableLinuxArm64
```

Output: `apps/embedded/build/bin/linuxArm64/releaseExecutable/bitchat-embedded.kexe`

### Deploy to Orange Pi

```bash
scp apps/embedded/build/bin/linuxArm64/releaseExecutable/bitchat-embedded.kexe \
    user@<orangepi-ip>:~/
```

### Run

```bash
ssh user@<orangepi-ip>
./bitchat-embedded.kexe
```

The app connects to meshcored on `localhost:5000` and:
1. Sends `CMD_DEVICE_QUERY` to get device info
2. Sends `CMD_APP_START` to get self info (including current radio params)
3. Checks radio params match expected values (BW=62500Hz, SF=7)
4. Configures the channel and requests contacts
5. Begins message polling loop

On success you'll see:
```
✅ Radio params OK: 910525kHz, BW=62500Hz, SF=7, CR=5
```

## Troubleshooting

### No bidirectional communication (TX works, RX doesn't)

**Most likely cause: radio parameter mismatch.**

The Kotlin app's `correctRadioParamsIfNeeded()` in `MeshCoreProtocol.kt` has expected radio parameters. If these don't match the phones, the app will override meshcored's correct params with wrong ones, breaking communication.

Verify the expected values in `MeshCoreProtocol.kt` match your phone network:
```kotlin
// In correctRadioParamsIfNeeded():
val expectedFreqKhz = 910_525L
val expectedBwHz = 62_500L      // Must match phones
val expectedSf = 7              // Must match phones
val expectedCr = 5
```

If the app logs show `⚠️ Radio params mismatch!` followed by `Sending CMD_SET_RADIO_PARAMS`, the app is overriding meshcored's config. Fix the expected values to match your network.

### Radio enters standby after param change

When `CMD_SET_RADIO_PARAMS` is sent, RadioLib's setter functions (`setFrequency`, `setBandwidth`, etc.) put the radio into STANDBY mode internally. The MeshCore `RadioLibWrapper` state still thinks it's in RX mode, so `recvRaw()` never calls `startReceive()` again.

The fix in `target.cpp` calls `resetAGC()` after setting params, which resets the wrapper state to IDLE. On the next `recvRaw()` call, the wrapper sees `state != STATE_RX` and restarts receive mode.

Note: `RadioLibWrapper::idle()` does the same thing but is a **protected** method — you must use `resetAGC()` (public) from `target.cpp`.

### GPIO issues

```bash
# Check DIO0 (IRQ) pin state — should be 0 when idle
sudo gpioget gpiochip1 70

# Check pin allocation
sudo gpioinfo gpiochip1 | grep -E "70|71"
```

If pins show as "used" by another driver, there may be a device tree conflict.

### meshcored log spam

`PUSH_CODE_LOG_DATA` (0x88) messages appear as `❓ Unknown response: code=0x88` in the Kotlin app. This is meshcored forwarding its debug output over the companion protocol (because `MESH_DEBUG=1`). It's harmless and doesn't affect messaging.

### Checking radio params from logs

After the embedded app connects, look for either:
- `✅ Radio params OK: ...` — params match, no override sent (good)
- `⚠️ Radio params mismatch!` — app is overriding params (fix the expected values)

## Architecture Overview

```
┌──────────────────────┐     TCP:5000     ┌──────────────────────┐
│  bitchat-embedded    │ ◄──────────────► │     meshcored        │
│  (Kotlin/Native)     │   MeshCore       │   (C++ daemon)       │
│                      │   companion      │                      │
│  - Message handling  │   protocol       │  - RadioLib/SX1276   │
│  - Channel mgmt     │                  │  - LoRa TX/RX        │
│  - Contact mgmt     │                  │  - Mesh routing       │
│  - Encryption        │                  │  - Store & forward    │
└──────────────────────┘                  └──────────┬───────────┘
                                                     │ SPI1.1
                                                     │ GPIO 70 (IRQ)
                                                     │ GPIO 71 (RST)
                                                ┌────┴────┐
                                                │ RFM95W  │
                                                │ SX1276  │
                                                │ 915 MHz │
                                                └────┬────┘
                                                     │ LoRa RF
                                                     ▼
                                          ┌──────────────────┐
                                          │  MeshCore Phones  │
                                          │  BW=62.5 SF=7    │
                                          │  910.525 MHz      │
                                          └──────────────────┘
```

## Key Files

| File | Purpose |
|------|---------|
| `bitchatKmp/.../MeshCoreProtocol.kt` | Kotlin app — companion protocol, radio param validation |
| `bitchatKmp/.../MeshCoreSerial.kt` | Kotlin app — TCP serial transport, frame parsing |
| `bitchatKmp/.../MeshCoreCompanion.kt` | Kotlin app — response parser, command builder |
| `forks/meshcore-linux/variants/linux/target.cpp` | meshcored — radio init, param setter, state reset |
| `forks/meshcore-linux/variants/linux/platformio.ini` | meshcored — build config, compile-time defaults |
| `forks/meshcore-linux/variants/linux/orangepi_zero3.ini` | meshcored — Orange Pi runtime config template |
| `forks/meshcore-linux/src/helpers/radiolib/LinuxSX1276Wrapper.h` | SX1276 wrapper — RX/TX metrics and packet scoring |
| `forks/meshcore-linux/src/helpers/linux/LinuxTcpInterface.cpp` | Linux TCP transport for companion protocol (port 5000 framing) |

## Lessons Learned

1. **Radio params must match exactly across all nodes.** LoRa modulation parameters (BW, SF, CR, frequency) define the "channel" — nodes with different params can't hear each other even on the same frequency.

2. **The companion app can silently override meshcored's radio config.** The Kotlin app's `correctRadioParamsIfNeeded()` sends `CMD_SET_RADIO_PARAMS` if params don't match its hardcoded expectations. If those expectations are wrong, it breaks communication on every startup.

3. **RadioLib setters put the radio in STANDBY.** After calling `setFrequency()`, `setBandwidth()`, or `setSpreadingFactor()`, the radio is in standby mode. The `RadioLibWrapper` state machine must be reset (via `resetAGC()`) so `recvRaw()` restarts RX on the next loop iteration.

4. **`RadioLibWrapper::idle()` is protected.** If you need to reset state from outside the class (e.g., `target.cpp`), use `resetAGC()` which is public and sets state to IDLE with the same effect.
