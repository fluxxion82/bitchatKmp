# Meshtastic Runbook (Orange Pi Zero 3 + BitChat KMP)

> **Historical record — corrected September 10, 2026.** These are observations and commands from the earlier bring-up, not current installation instructions. Use [the current PCB profile](ORANGEPI_ZERO3_PCB.md) and the current [MeshCore](../../../docs/meshcore-orangepi-setup.md) / [Meshtastic](../../../docs/meshtastic-orangepi-setup.md) guides. The earlier claim “GPIO 71 = physical pin 7” is incorrect: GPIO 71 is header 22; header 7 is PC9/GPIO 73, shared with the PMIC interrupt. Current software omits radio reset and must not unexport, pulse, or take over either line. Both daemon configurations use SPI1.1. GPIO 71 in the historical software configuration does not establish a physical change to the custom PCB. The February Meshtastic recovery used an Adafruit breakout; the older measurements and outcomes below have not been reinterpreted as current PCB tests.

The app now owns protocol startup from its saved settings. Historical commands below that start a daemon, send messages, or change RF settings require a separate maintenance context; they are not the current installation workflow. The retained reset hook is now an explicit no-op, and the default diagnostic is read-only.

This runbook documents what we did to get Meshtastic working end-to-end (direct daemon sends and BitChat app sends).

## Scope

- Platform: Orange Pi Zero 3
- LoRa class: SX1276/RFM95-compatible
- App: `bitchatKmp` embedded target
- Meshtastic daemon: `meshtasticd` from firmware source build

## Repository and Build Layout

On the Pi:

- Firmware source repo: `~/firmware` (`fluxxion82/firmware`, branch `orangepi-rfm95w`)
- Build venv/tooling: `~/meshtastic-build`
- Runtime binary: `/usr/bin/meshtasticd`

Use the fork branch to reproduce the exact Orange Pi behavior:
```bash
git clone -b orangepi-rfm95w --recursive https://github.com/fluxxion82/firmware.git
```

The fork also tracks Pi-only runtime/dependency patch snapshots under:
- `orangepi/runtime-captures/LinuxGPIOPin.cpp.patched`
- `orangepi/runtime-captures/SX127x.cpp.patched`

## Known-Good Hardware/Runtime Baseline

- `spidev: spidev1.1`
- IRQ: line 70
- RESET: line 71 (pin 7)
- `spiSpeed: 500000`
- Working test board during recovery: Adafruit RFM9X breakout

## 1. Prepare Meshtastic Firmware Build

```bash
cd ~
# clone if missing
# git clone -b orangepi-rfm95w --recursive https://github.com/fluxxion82/firmware.git

python3 -m venv ~/meshtastic-build
source ~/meshtastic-build/bin/activate
pip install -U pip platformio
```

Build:

```bash
cd ~/firmware
source ~/meshtastic-build/bin/activate
pio run -e native
```

## 2. Install/Reinstall meshtasticd

```bash
cd ~/firmware
sudo systemctl stop meshtasticd || true
sudo cp .pio/build/native/meshtasticd /usr/bin/meshtasticd
sudo chmod +x /usr/bin/meshtasticd
```

## 3. Configure LoRa Pins and SPI

Create/update `/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml`:

```yaml
Lora:
  Module: RF95
  spidev: spidev1.1
  spiSpeed: 500000
  gpiochip: 1
  IRQ:
    pin: 70
    gpiochip: 1
    line: 70
  Reset:
    pin: 71
    gpiochip: 1
    line: 71
```

## 4. Reset Script (Important)

During recovery we hit GPIO line-ownership issues that caused daemon aborts.

For stabilization testing, we used a no-op prestart script:

```bash
#!/bin/bash
set -euo pipefail
echo "LoRa reset skipped (stabilization mode)"
```

Path: `/usr/local/bin/reset-lora.sh`

This avoids libgpiod contention while isolating RF behavior.

## 5. Start and Validate Service

```bash
sudo systemctl reset-failed meshtasticd
sudo systemctl start meshtasticd
sudo systemctl status meshtasticd --no-pager -l
sudo journalctl -u meshtasticd -n 120 --no-pager
```

Healthy indicators:

- `RF95 init success`
- `API server listen on TCP port 4403`

## 6. Direct Meshtastic TCP Tests

Script path used:

- `~/meshtastic_send.py`

Examples:

```bash
python3 ~/meshtastic_send.py --host 127.0.0.1 --port 4403 --info
python3 ~/meshtastic_send.py --host 127.0.0.1 --port 4403 --channels
python3 ~/meshtastic_send.py --host 127.0.0.1 --port 4403 "bikTwo-direct-test"
```

## 7. Frequency Slot and HackRF

Yes: `lora.channel_num` changes RF frequency.

Set slot 20 (US band => ~906.875 MHz):

```bash
~/meshtastic-build/bin/meshtastic --host 127.0.0.1 --set lora.channel_num 20
~/meshtastic-build/bin/meshtastic --host 127.0.0.1 --get lora.channel_num
```

Verify in logs:

- `Radio freq=906.875`
- `channel_num: 20`

## 8. BitChat Integration Notes

BitChat Meshtastic path uses channel config from `MeshtasticProtocol` constants.

Current hardcoded values in app code include channel name/PSK (for example `bikTwo`).
If you change name/PSK in app code, all peers must match name/PSK and LoRa params.

## 9. Repro Checklist for New Device

1. Wire SX1276/RFM95-class board to SPI1 + DIO0 + RESET.
2. Apply overlays and confirm `spidev1.1` exists.
3. Build/install `meshtasticd` from firmware repo.
4. Apply LoRa config (IRQ 70, RESET 71, spidev1.1).
5. Start daemon and confirm `RF95 init success` + TCP 4403.
6. Run direct TCP send test via `meshtastic_send.py`.
7. Set slot 20 if you want 906.875 MHz, verify in logs.
8. Test BitChat app send/receive.

## 10. If It Fails Again

Quick triage commands:

```bash
sudo systemctl status meshtasticd --no-pager -l
sudo journalctl -u meshtasticd -n 200 --no-pager
gpioinfo | grep -E 'line\s+70:|line\s+71:'
```

Common symptoms seen:

- `RF95 begin ... -2` / `No RF95 radio` -> SPI/radio comm issue.
- `line request is null` / `resource busy` -> reset GPIO ownership conflict.
