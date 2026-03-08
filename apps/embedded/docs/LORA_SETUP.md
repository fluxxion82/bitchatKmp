# LoRa Hardware Setup (Orange Pi Zero 3)

This document is the hardware baseline for LoRa on Orange Pi Zero 3 used by BitChat KMP.

Navigation:
- MeshCore setup path: [`../../../docs/meshcore-orangepi-setup.md`](../../../docs/meshcore-orangepi-setup.md)
- Meshtastic setup path: [`../../../docs/meshtastic-orangepi-setup.md`](../../../docs/meshtastic-orangepi-setup.md)

## Working Baseline

- SBC: Orange Pi Zero 3
- LoRa module family: SX1276/RFM95-class
- SPI device: `spidev1.1`
- IRQ line: `gpiochip1 line 70` (physical pin 11)
- RESET line: `gpiochip1 line 71` (physical pin 7)

Reset is on pin 7 for the current baseline. If you see older notes mentioning pin 12, treat those as historical.

## Wiring (26-pin header)

| LoRa Pin | Orange Pi Pin | Notes |
|---|---:|---|
| VCC | 1 | 3.3V only |
| GND | 6 | Ground |
| MISO | 21 | SPI1 MISO |
| MOSI | 19 | SPI1 MOSI |
| SCK | 23 | SPI1 CLK |
| NSS/CS | 24 | SPI1 CS1 (`spidev1.1`) |
| DIO0 | 11 | IRQ line 70 |
| RESET | 7 | Reset line 71 |

## SPI/Overlay Prereqs

In `/boot/armbianEnv.txt`:

```text
overlay_prefix=sun50i-h616
overlays=i2c1 i2c2 spi1-enable spi1-cs1-touch
```

After changes:

```bash
sudo reboot
```

Verify:

```bash
ls /dev/spidev*
# expect /dev/spidev1.0 and /dev/spidev1.1
```

## Meshtastic Daemon LoRa Config

`/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml`:

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

## Quick Sanity Checks

```bash
gpioinfo | grep -E 'line\s+70:|line\s+71:'
sudo systemctl status meshtasticd --no-pager -l
sudo journalctl -u meshtasticd -n 80 --no-pager
```

If `meshtasticd` repeatedly fails RF init, see the troubleshooting section in [`../../../docs/meshtastic-orangepi-setup.md`](../../../docs/meshtastic-orangepi-setup.md).
