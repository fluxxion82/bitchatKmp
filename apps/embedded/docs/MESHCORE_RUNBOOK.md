# MeshCore Runbook (Orange Pi Zero 3 + BitChat KMP)

> **Historical record — corrected September 10, 2026.** These are observations and commands from the earlier bring-up, not current installation instructions. Use [the current PCB profile](ORANGEPI_ZERO3_PCB.md) and the current [MeshCore](../../../docs/meshcore-orangepi-setup.md) / [Meshtastic](../../../docs/meshtastic-orangepi-setup.md) guides. The earlier claim “GPIO 71 = physical pin 7” is incorrect: GPIO 71 is header 22; header 7 is PC9/GPIO 73, shared with the PMIC interrupt. Current software omits radio reset and must not unexport, pulse, or take over either line. Both daemon configurations use SPI1.1. GPIO 71 in the historical software configuration does not establish a physical change to the custom PCB. The February Meshtastic recovery used an Adafruit breakout; the older measurements and outcomes below have not been reinterpreted as current PCB tests.

The app now owns protocol startup from its saved settings. Historical commands below that start a daemon, send messages, or change RF settings require a separate maintenance context; they are not the current installation workflow. The retained reset hook is now an explicit no-op, and the default diagnostic is read-only.

This runbook documents what we did to get MeshCore working end-to-end on the Orange Pi Zero 3 with an SX1276/RFM95W radio and the bitchatKmp embedded app.

## Scope

- Platform: Orange Pi Zero 3 (Allwinner H618, aarch64)
- LoRa class: SX1276/RFM95W
- App: `bitchatKmp` embedded target
- Daemon: `meshcored` (MeshCore companion radio daemon)

## Repository and Build Layout

On the Pi:

- Source repo: `~/meshcore-linux` (forked to `fluxxion82/MeshCore`, branch `orangepi-zero3-sx1276`)
- Runtime binary: `/usr/local/bin/meshcored`
- Runtime config: `/etc/meshcored/meshcored.ini`

Clone:
```bash
git clone -b orangepi-zero3-sx1276 https://github.com/fluxxion82/MeshCore.git meshcore-linux
```

## Known-Good Hardware/Runtime Baseline

- `spidev: /dev/spidev1.1`
- `gpio_chip: gpiochip1`
- IRQ (DIO0): pin 70
- RESET: pin 71
- Radio params: 910.525 MHz, BW=62.5 kHz, SF=7, CR=5, TX power=10

## Custom Patches Applied

The upstream `linux` branch does not support SX1276 on Orange Pi out of the box. The following patches are now tracked in `fluxxion82/MeshCore` branch `orangepi-zero3-sx1276`:

### 1. GPIO range expansion (`LinuxBoard.cpp`)

**Problem:** Default `gpioInit(64)` is too small for gpiochip1 pins 70/71.

**Fix:** Changed to `gpioInit(256)`.

### 2. Configurable gpio_chip (`LinuxBoard.h`, `LinuxBoard.cpp`)

**Problem:** Hardcoded `gpiochip0` doesn't work on Orange Pi Zero 3 where the LoRa pins are on `gpiochip1`.

**Fix:** Added `gpio_chip` field (default `"gpiochip0"`) configurable via `meshcored.ini`.

### 3. SX1276 build target (`platformio.ini`)

**Problem:** No PlatformIO environment for SX1276 companion radio.

**Fix:** Added `[env:linux_companion_sx1276]` with `RADIO_CLASS=LinuxSX1276` and `WRAPPER_CLASS=LinuxSX1276Wrapper`.

### 4. Radio standby fix (`target.cpp`)

**Problem:** After `CMD_SET_RADIO_PARAMS`, RadioLib's setter functions (`setFrequency`, `setBandwidth`, `setSpreadingFactor`) put the radio into STANDBY mode internally. The `RadioLibWrapper` state machine still thinks it's in RX mode, so `recvRaw()` never calls `startReceive()` again. Result: radio goes deaf after any param change.

**Fix:** Added `resetAGC()` call after setting params in `target.cpp`. This resets the wrapper state to IDLE. On the next `recvRaw()` call, the wrapper sees `state != STATE_RX` and restarts receive mode.

**Note:** `RadioLibWrapper::idle()` does the same thing but is `protected` — `resetAGC()` is the public equivalent.

### 5. Conditional includes (`target.h`)

**Fix:** Added `#ifdef` guards and includes for `LinuxSX1276Wrapper` so the SX1276 target compiles.

### 6. TCP companion interface (`examples/companion_radio/main.cpp`)

**Fix:** Added `PORTDUINO_PLATFORM` support with `LinuxTcpInterface` for TCP companion protocol on Linux.

### 7. Portduino build fix (`examples/companion_radio/DataStore.cpp`)

**Fix:** Compilation fix for portduino on aarch64.

## Radio Parameters Bug

**Symptom:** Orange Pi could transmit (phones showed received packets) but could not receive (phone transmissions not decoded).

**Root cause:** The Kotlin app's `correctRadioParamsIfNeeded()` in `MeshCoreProtocol.kt` had hardcoded expected params that didn't match the phone network. On every startup, the app sent `CMD_SET_RADIO_PARAMS` with wrong BW/SF, overriding meshcored's correct config.

**Fix:** Updated expected values in `MeshCoreProtocol.kt` to match the phone network:
```kotlin
val expectedFreqKhz = 910_525L
val expectedBwHz = 62_500L   // Was wrong — must match phones
val expectedSf = 7            // Was wrong — must match phones
val expectedCr = 5
```

## GPIO 73 Issue

**Symptom:** Using GPIO 73 for RESET caused intermittent failures. Pin would sometimes be claimed by another kernel driver.

**Fix:** Moved RESET from pin 73 to pin 71. Updated `meshcored.ini` and all documentation. Pin 71 is reliably available on the Orange Pi Zero 3.

## Build Command

```bash
cd ~/meshcore-linux
FIRMWARE_VERSION=dev ./build.sh build-firmware linux_companion_sx1276
```

Output: `./out/meshcored`

Install:
```bash
sudo cp ./out/meshcored /usr/local/bin/meshcored
sudo chmod +x /usr/local/bin/meshcored
```

## Deploy and Validate

```bash
# Stop meshtasticd first (shares SPI/GPIO)
sudo systemctl stop meshtasticd

sudo systemctl daemon-reload
sudo systemctl restart meshcored
sudo systemctl status meshcored --no-pager -l
sudo journalctl -u meshcored -n 50 --no-pager
```

Healthy indicators:
- `RadioLibWrapper: noise_floor = -10x` — radio is in RX mode, actively listening
- No GPIO claim errors

## Known Issue: PUSH_CODE_LOG_DATA (0x88)

`PUSH_CODE_LOG_DATA` (0x88) messages appear as `Unknown response: code=0x88` in the Kotlin app logs. This is meshcored forwarding its debug output over the companion protocol (because `MESH_DEBUG=1` is set in the build config). It's harmless and doesn't affect messaging.

## Repro Checklist for New Device

1. Clone the fork: `git clone -b orangepi-zero3-sx1276 https://github.com/fluxxion82/MeshCore.git meshcore-linux`
2. Verify branch: `git -C meshcore-linux branch --show-current` should print `orangepi-zero3-sx1276`
3. Build: `FIRMWARE_VERSION=dev ./build.sh build-firmware linux_companion_sx1276`
4. Install binary to `/usr/local/bin/meshcored`
5. Create `/etc/meshcored/meshcored.ini` with correct pin/SPI config
6. Create systemd service (see setup guide)
7. Stop meshtasticd if running
8. Start meshcored, confirm `noise_floor` log line
9. Run BitChat embedded app, confirm `Radio params OK` log line
10. Test bidirectional messaging with MeshCore phones

## If It Fails Again

Quick triage:

```bash
sudo systemctl status meshcored --no-pager -l
sudo journalctl -u meshcored -n 200 --no-pager

# Check GPIO ownership
sudo gpioinfo gpiochip1 | grep -E "70|71"

# Check if meshtasticd is holding the pins
sudo systemctl status meshtasticd
```

Common symptoms:
- GPIO `resource busy` -> meshtasticd or another process holds the GPIO lines. Stop it first.
- Radio goes deaf after startup -> radio params mismatch or standby bug. Check Kotlin app logs for `Radio params mismatch`.
- `Unknown response: code=0x88` -> harmless debug log forwarding, ignore.

## See Also

- [MeshCore setup guide](../../../docs/meshcore-orangepi-setup.md) — full setup walkthrough
- [Meshtastic setup](../../../docs/meshtastic-orangepi-setup.md) — alternative protocol path
- [Meshtastic runbook](./MESHTASTIC_RUNBOOK.md) — Meshtastic bring-up runbook
