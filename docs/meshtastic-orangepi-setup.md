# Meshtastic on Orange Pi Zero 3 - Setup Guide

This is the canonical setup guide for running the `bitchatKmp` embedded app against `meshtasticd` on Orange Pi Zero 3.

For the MeshCore-based path instead, use [`meshcore-orangepi-setup.md`](meshcore-orangepi-setup.md).

## Overview

BitChat embedded supports three LoRa protocols:
- **BitChat**: the app uses SPI directly.
- **MeshCore**: the app talks to `meshcored` on TCP 5000.
- **Meshtastic**: the app talks to `meshtasticd` on TCP 4403.

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
- Software reset disabled; PCB RESET reaches header 7/GPIO 73, shared with PMIC IRQ
- Radio currently fitted on both boards: Adafruit RFM9x breakout on the same pins, RST unconnected ([details](../apps/embedded/docs/ORANGEPI_ZERO3_PCB.md#adafruit-rfm9x-breakout))

See canonical wiring and overlay baseline:
- [current PCB profile](../apps/embedded/docs/ORANGEPI_ZERO3_PCB.md)

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
  # Omit Reset: PCB RESET shares the PMIC interrupt.
```

Reference runtime files in this repo:
- [`../scripts/meshtastic/lora-rfm95w-opi3.yaml`](../scripts/meshtastic/lora-rfm95w-opi3.yaml)
- [`../scripts/meshtastic/reset-lora.sh`](../scripts/meshtastic/reset-lora.sh)

Use `scripts/configure-pi-lora.sh --help` from the KMP repository root for the audit/explicit-apply workflow. It preserves device settings and stages reviewed changes with backups. Audit `/etc/meshtasticd/config.yaml` and every loaded fragment: a stale `Lora.Reset` in another source must also be removed. Do not use `Reset: -1`, because the parser enables an explicitly supplied scalar pin mapping. The retained reset hook is a no-op.

Keep `meshtasticd.service` installed but disabled at boot, with the tracked `apps/embedded/systemd/meshtasticd.service.d/bitchat-lora.conf` drop-in for bounded failures. Keep the app enabled so it starts the saved protocol. Do not enable both radio daemons or blindly restart Meshtastic while another protocol owns SPI.

## 4) Validate daemon health

Select Meshtastic in the app first. Then inspect health without starting a competing owner:

```bash
sudo systemctl status meshtasticd --no-pager -l
sudo journalctl -u meshtasticd -n 120 --no-pager
ss -tlnp | grep 4403
```

Healthy signals:
- service is active
- logs include successful RF95 init
- TCP listener active on port 4403

An active process or open listener is not proof of radio or protocol readiness. The app must complete Meshtastic initialization without an RF error. A failed stop prevents the target from starting; explicit retry permits a new bounded attempt.

Optional CLI check during manual maintenance, with the app stopped and only Meshtastic running:

```bash
meshtastic --host localhost --info
```

## 5) Build and run BitChat embedded with Meshtastic

Build from macOS host:

```bash
cd bitchatKmp
./scripts/build-all-linux.sh
./gradlew -Pembedded.enabled=true :apps:embedded:linkReleaseExecutableLinuxArm64
```

Deploy and run:

```bash
scp apps/embedded/build/bin/linuxArm64/releaseExecutable/bitchat-embedded.kexe user@<orangepi-ip>:/tmp/
ssh user@<orangepi-ip> '/tmp/bitchat-embedded.kexe'
```

### Protocol selection

Select Meshtastic under Settings → LoRa. The app persists the choice in the app user's `~/.bitchat/settings/lora_settings.prefs` and reads it at startup. The old `LORA_PROTOCOL` environment variable and `/opt/bitchat/lora-protocol.conf` are not active overrides.

| Switching to | Action after confirmed old-owner shutdown |
|-------------|--------|
| **Meshtastic** | Starts meshtasticd, connects and initializes the protocol |
| **MeshCore** | Starts meshcored, connects and initializes the companion protocol |
| **BitChat** | Stops both daemons and opens SPI directly |

A failed stop blocks the target. Serialized transitions keep existing message/peer subscribers attached to the selected protocol. A process, socket, or selection alone is not a successful connection.

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

### Inspect application startup

```bash
journalctl -u bitchat.service -n 120 --no-pager
```

Check that the saved Meshtastic selection is used, daemon ownership is acquired, TCP connects, and the protocol completes node/config initialization. Do not treat a service-start log alone as readiness.

### Monitor traffic

```bash
meshtastic --host localhost --listen
```

## Troubleshooting

### `RF95 init` failures / no radio detected

- Check SPI1.1, IRQ 70, and omission of reset in **all** effective YAML sources.
- Verify no other process owns SPI/GPIO; stop the app before manual daemon maintenance.
- Use [`scripts/lora_test.py`](../scripts/lora_test.py) with all owners stopped, following [LoRa testing](../scripts/LORA_TESTING.md). Its default and `--dump` paths read registers only and require ten consistent `0x12` samples.
- No software should claim GPIO 71 or drive GPIO 73. GPIO 73's PMIC interrupt binding remains intact.

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
# Preserve the existing binary and Pi-local dependency patches before deployment.
# Stop the app and both radio owners before installing the verified build.
```

Use the current PCB profile for pin/runtime configuration. February runbooks describe historical recovery on an Adafruit breakout and contain correction notices.
For Pi-only dependency/runtime patches used during recovery, see `orangepi/runtime-captures/` in the same fork.

## BitChat Code Reference

| File | Purpose |
|------|---------|
| `lora/meshtastic/.../MeshtasticSerial.linuxArm64.kt` | TCP client |
| `lora/meshtastic/.../MeshtasticdService.kt` | Service management |
| `lora/meshtastic/.../MeshtasticProtocol.kt` | Protocol handler |
| `lora/bitchat/.../LoRaServiceManager.kt` | Stops both radio daemons for direct BitChat |
| `apps/embedded/.../LoRaProtocolSelector.kt` | Protocol selection |

## Files Reference

| File | Purpose |
|------|---------|
| `/etc/meshtasticd/config.yaml` | Main meshtasticd config |
| `/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml` | LoRa pin config |
| `~/.bitchat/settings/lora_settings.prefs` | Saved protocol in the app user's home |

## Related Docs

- MeshCore path: [`meshcore-orangepi-setup.md`](meshcore-orangepi-setup.md)
- LoRa hardware baseline: [current PCB profile](../apps/embedded/docs/ORANGEPI_ZERO3_PCB.md)
- Embedded app overview: [`../apps/embedded/README.md`](../apps/embedded/README.md)
- [Meshtastic Linux Native Hardware](https://meshtastic.org/docs/hardware/devices/linux-native-hardware/)
- [Meshtastic Python CLI](https://meshtastic.org/docs/development/python/library/)
