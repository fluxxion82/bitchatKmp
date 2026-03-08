# Meshtastic on Orange Pi Zero 3 - Setup Guide

This is the canonical setup guide for running the `bitchatKmp` embedded app against `meshtasticd` on Orange Pi Zero 3.

For the MeshCore-based path instead, use [`meshcore-orangepi-setup.md`](meshcore-orangepi-setup.md).

## Overview

BitChat embedded supports two LoRa protocol backends:
- **BitChat/MeshCore path**: app talks to `meshcored` on TCP 5000.
- **Meshtastic path**: app talks to `meshtasticd` on TCP 4403.

Only one path can control the LoRa hardware at a time.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                     Orange Pi Zero 3                              │
│                                                                   │
│  ┌─────────────────────┐         ┌──────────────────────────────┐│
│  │ BitChat Embedded    │   TCP   │ meshtasticd                  ││
│  │ App                 │◄───────►│ (Meshtastic daemon)          ││
│  │                     │ :4403   │                              ││
│  │ MeshtasticSerial    │         │ Handles:                     ││
│  │ (TCP client)        │         │ - Channel encryption         ││
│  └─────────────────────┘         │ - NodeDB management          ││
│                                  │ - LoRa radio control         ││
│                                  └──────────────┬───────────────┘│
│                                                 │ SPI            │
│                                  ┌──────────────▼───────────────┐│
│                                  │ RFM95W LoRa Radio            ││
│                                  │ /dev/spidev1.1               ││
│                                  └──────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

## Hardware Baseline

- Orange Pi Zero 3 (Armbian, linuxArm64)
- SX1276/RFM95W-class LoRa module on `spidev1.1`
- IRQ: `gpiochip1` line `70`
- RESET: `gpiochip1` line `71`

See canonical wiring and overlay baseline:
- [`../apps/embedded/docs/LORA_SETUP.md`](../apps/embedded/docs/LORA_SETUP.md)

## 1) Prepare OS and SPI

Ensure SPI and overlays are enabled per LoRa baseline doc. Verify:

```bash
ls /dev/spidev*
# expect: /dev/spidev1.0 and /dev/spidev1.1
```

## 2) Install meshtasticd

Preferred first pass:

```bash
sudo apt update
sudo apt install meshtasticd
```

If package build fails for your board/radio behavior, use source-build fallback in the troubleshooting section.

## 3) Configure LoRa runtime pins

Create `/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml`:

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

Reference runtime files in this repo:
- [`../scripts/meshtastic/lora-rfm95w-opi3.yaml`](../scripts/meshtastic/lora-rfm95w-opi3.yaml)
- [`../scripts/meshtastic/reset-lora.sh`](../scripts/meshtastic/reset-lora.sh)

Install helper files on device:

```bash
sudo install -m 755 reset-lora.sh /usr/local/bin/reset-lora.sh
sudo install -m 644 lora-rfm95w-opi3.yaml /etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml
```

## 4) Validate daemon health

```bash
sudo systemctl restart meshtasticd
sudo systemctl status meshtasticd --no-pager -l
sudo journalctl -u meshtasticd -n 120 --no-pager
ss -tlnp | grep 4403
```

Healthy signals:
- service is active
- logs include successful RF95 init
- TCP listener active on port 4403

Optional CLI check:

```bash
meshtastic --host localhost --info
```

## 5) Build and run BitChat embedded with Meshtastic

Build from macOS host:

```bash
cd bitchatKmp
./scripts/build-all-linux.sh
./gradlew :apps:embedded:linkReleaseExecutableLinuxArm64
```

Deploy and run:

```bash
scp apps/embedded/build/bin/linuxArm64/releaseExecutable/bitchat-embedded.kexe user@<orangepi-ip>:/tmp/
ssh user@<orangepi-ip> 'LORA_PROTOCOL=MESHTASTIC /tmp/bitchat-embedded.kexe'
```

### Protocol selection

| Method | Details |
|--------|---------|
| **Settings UI** (recommended for testing) | Run `./bitchat-embedded.kexe` → Settings → LoRa → change Protocol to Meshtastic. Switches at runtime but does not persist across restarts. |
| **Environment variable** | `LORA_PROTOCOL=MESHTASTIC ./bitchat-embedded.kexe` |
| **Config file** (persistent) | `echo "MESHTASTIC" | sudo tee /opt/bitchat/lora-protocol.conf` |

BitChat handles service management automatically:

| Switching to | Action |
|-------------|--------|
| **Meshtastic** | Starts meshtasticd, connects via TCP |
| **BitChat** | Stops meshtasticd, uses SPI directly |

### Channel configuration

BitChat uses meshtasticd's channel settings:
- **Default**: LongFast preset, PSK `AQ==` (0x01)
- Messages sent via `TEXT_MESSAGE_APP` port
- meshtasticd handles encryption/decryption

To change channels:
```bash
meshtastic --host localhost --set lora.channel_num 0
meshtastic --host localhost --ch-set name "MyChannel" --ch-index 0
```

## 6) End-to-end verification checklist

1. `meshtasticd` active and listening on `127.0.0.1:4403`
2. embedded app logs show Meshtastic mode selected and TCP connected
3. send message from BitChat embedded and receive on another Meshtastic node
4. send from external Meshtastic node and observe receive in BitChat

### Expected log output

```bash
LORA_PROTOCOL=MESHTASTIC ./bitchat-embedded.kexe > /tmp/bitchat.log 2>&1 &
tail -f /tmp/bitchat.log
```

Healthy startup:
```
📡 LoRa protocol from environment: MESHTASTIC
🚀 Starting meshtasticd service...
✅ meshtasticd started successfully
📡 Connecting to meshtasticd at 127.0.0.1:4403...
✅ TCP connected to meshtasticd
📱 My node: XXXXXXXX
```

### Monitor traffic

```bash
meshtastic --host localhost --listen
```

## Troubleshooting

### `RF95 init` failures / no radio detected

- Recheck SPI wiring and antenna.
- Recheck `spidev1.1`, IRQ 70, RESET 71 configuration.
- Verify no other process owns SPI/GPIO lines.
- Check GPIO state:
  ```bash
  gpioinfo | grep -E 'line\\s+70:|line\\s+71:'
  ```
- Quick SPI reliability test:
  ```bash
  python3 -c "
  import spidev
  spi = spidev.SpiDev()
  spi.open(1, 1)
  spi.max_speed_hz = 500000
  spi.mode = 0
  correct = sum(1 for _ in range(100) if spi.xfer2([0x42, 0x00])[1] == 0x12)
  print(f'SPI reliability: {correct}%')
  "
  ```
  Should be 100%. If not, check wiring (especially MISO).

### Daemon starts but unstable behavior

- Stop BitChat before starting meshtasticd (SPI contention).
- If a source build is needed, the SPI paranoid fix may help (see source build section below).
- Check `journalctl -u meshtasticd -n 120 --no-pager` for repeated RF init failures.

### Need source build for patched behavior

High-level fallback:

```bash
git clone -b orangepi-rfm95w --recursive https://github.com/fluxxion82/firmware.git
cd firmware
pip install platformio
pio run -e native
sudo cp .pio/build/native/meshtasticd /usr/bin/meshtasticd
```

Use runbook docs above for pin/runtime details from known-good recovery flows.
For Pi-only dependency/runtime patches used during recovery, see `orangepi/runtime-captures/` in the same fork.

## BitChat Code Reference

| File | Purpose |
|------|---------|
| `lora/meshtastic/.../MeshtasticSerial.linuxArm64.kt` | TCP client |
| `lora/meshtastic/.../MeshtasticdService.kt` | Service management |
| `lora/meshtastic/.../MeshtasticProtocol.kt` | Protocol handler |
| `lora/bitchat/.../LoRaServiceManager.kt` | Stops meshtasticd for BitChat |
| `apps/embedded/.../LoRaProtocolSelector.kt` | Protocol selection |

## Files Reference

| File | Purpose |
|------|---------|
| `/etc/meshtasticd/config.yaml` | Main meshtasticd config |
| `/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml` | LoRa pin config |
| `/opt/bitchat/lora-protocol.conf` | Protocol selection override |

## Related Docs

- MeshCore path: [`meshcore-orangepi-setup.md`](meshcore-orangepi-setup.md)
- LoRa hardware baseline: [`../apps/embedded/docs/LORA_SETUP.md`](../apps/embedded/docs/LORA_SETUP.md)
- Embedded app overview: [`../apps/embedded/README.md`](../apps/embedded/README.md)
- [Meshtastic Linux Native Hardware](https://meshtastic.org/docs/hardware/devices/linux-native-hardware/)
- [Meshtastic Python CLI](https://meshtastic.org/docs/development/python/library/)
