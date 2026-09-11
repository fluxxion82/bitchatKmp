# Meshtastic Recovery Notes (2026-02-17)

> **Historical record — corrected September 10, 2026.** These are observations and commands from the earlier bring-up, not current installation instructions. Use [the current PCB profile](ORANGEPI_ZERO3_PCB.md) and the current [MeshCore](../../../docs/meshcore-orangepi-setup.md) / [Meshtastic](../../../docs/meshtastic-orangepi-setup.md) guides. The earlier claim “GPIO 71 = physical pin 7” is incorrect: GPIO 71 is header 22; header 7 is PC9/GPIO 73, shared with the PMIC interrupt. Current software omits radio reset and must not unexport, pulse, or take over either line. Both daemon configurations use SPI1.1. GPIO 71 in the historical software configuration does not establish a physical change to the custom PCB. The February Meshtastic recovery used an Adafruit breakout; the older measurements and outcomes below have not been reinterpreted as current PCB tests.

The app now owns protocol startup from its saved settings. Historical commands below that start a daemon, send messages, or change RF settings require a separate maintenance context; they are not the current installation workflow. The retained reset hook is now an explicit no-op, and the default diagnostic is read-only.

Goal: restore reliable Meshtastic send/receive on Orange Pi Zero 3, then validate BitChat KMP integration.

## Locked baseline used

- Reset line: `gpiochip1 line 71` (physical pin 7)
- IRQ line: `gpiochip1 line 70`
- Active source repo: `~/firmware`
- Build tooling/venv: `~/meshtastic-build`

## Code changes in `bitchatKmp`

- `data/remote/transport/lora/meshtastic/src/linuxArm64Main/kotlin/com/bitchat/lora/meshtastic/MeshtasticdService.kt`
  - Improved `systemctl` command execution and surfaced explicit start/stop/status errors.
- `data/repo/src/commonMain/kotlin/com/bitchat/repo/repositories/ChatRepo.kt`
  - `switchLoRaProtocol()` now handles `MESHCORE` explicitly.
- `scripts/lora_test.py`
  - Fixed Orange Pi fallback path so reset uses the selected backend deterministically when `RPi.GPIO` is unavailable.
- `apps/embedded/docs/LORA_SETUP.md`
  - Updated reset baseline to line 71 and added sysfs-unexport caveat.

## Pi-side Meshtastic source/config changes

- Kept defensive RF95 changes in:
  - `~/firmware/src/mesh/RF95Interface.cpp`
- Removed verbose debug instrumentation from:
  - `~/firmware/src/mesh/RadioLibInterface.cpp` (restored to upstream file)
- Removed stale artifact:
  - `~/firmware/src/mesh/RadioLibInterface.cpp.bak`
- Runtime config aligned:
  - `/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml` (reset line 71, IRQ 70, spidev1.1)
  - `/usr/local/bin/reset-lora.sh` now:
    - unexports stale sysfs claims (`71`, `73`)
    - pulses reset on line `71`
  - `/etc/systemd/system/meshtasticd.service.d/reset-radio.conf`:
    - `ExecStartPre=/usr/local/bin/reset-lora.sh`

## Extra runtime patch applied (outside firmware repo)

File:
- `~/.platformio/packages/framework-portduino/cores/portduino/linux/gpio/LinuxGPIOPin.cpp`

Changes:
- Avoided double-close pattern for gpiod v2 chip handle.
- Added null guards in destructor.
- Added line-request retry/errno diagnostics.

Reason:
- `meshtasticd` was aborting with `free(): invalid size` and later `Assertion 'request' failed` in libgpiod path.

## Additional runtime workaround patches applied

These were needed to get daemon startup stable enough for TCP/API validation:

- `~/firmware/.pio/libdeps/native/RadioLib/src/modules/SX127x/SX127x.cpp`
  - Added step logging in `SX127x::begin()`.
  - On portduino only, tolerate `RADIOLIB_ERR_SPI_WRITE_FAILED` from `invertIQ(false)` and continue.

- `~/firmware/src/mesh/RadioLibRF95.cpp`
  - Added step logging in `RadioLibRF95::begin()`.
  - On portduino only, tolerate `RADIOLIB_ERR_SPI_WRITE_FAILED` for post-begin tuning writes (`setBandwidth`, `setSpreadingFactor`, `setCodingRate`, etc.).

- `~/firmware/src/mesh/RF95Interface.cpp`
  - Added explicit `setCRC` result logging.
  - On portduino only, tolerate `RADIOLIB_ERR_SPI_WRITE_FAILED` from `setCRC` so init can finish.

## Validation performed

- Rebuilt and reinstalled `meshtasticd` multiple times from `~/firmware`.
- Rebooted Pi to re-test from clean state.
- Copied scripts to Pi and executed remotely:
  - `~/lora_test.py`
  - `~/meshtastic_send.py`
  - `~/dio0_test.py`
- Confirmed:
  - crash path (`ABRT`) can be eliminated when line-claim conflict is removed
  - service still not stable due RF95 init failures

## Current blocker

`meshtasticd` now starts and listens on TCP `4403`, and `meshtastic_send.py` can connect and send packets through API.

However, runtime still shows repeated RF95 SPI write errors (`-16`) during standby/receive transitions. This is still a hardware/runtime signal-integrity issue, currently mitigated by software tolerance rather than fully solved at root.

## Repro commands used most

```bash
# Build native
cd ~/firmware
~/meshtastic-build/bin/pio run -e native

# Install binary
sudo install -m 755 ~/firmware/.pio/build/native/meshtasticd /usr/bin/meshtasticd

# Service checks
sudo systemctl status meshtasticd --no-pager -l
sudo journalctl -u meshtasticd -n 120 --no-pager

# Line ownership check
gpioinfo | grep -E 'line\\s+70:|line\\s+71:|line\\s+73:'
```
