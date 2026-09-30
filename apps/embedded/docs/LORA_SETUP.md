# LoRa setup on Orange Pi Zero 3

Use the [current PCB profile](ORANGEPI_ZERO3_PCB.md) for pin numbers and reset policy. The RFM95W radio uses SPI1.1, DIO0 on GPIO 70/header 11, and **no software reset**. Its physical RESET connection reaches header 7/GPIO 73, also the PMIC interrupt. GPIO 71 is header 22 and must not be used as a substitute reset output.

## Existing device setup

SPI1 must be enabled for the radio. A fresh board uses the `spi1-cs1-lora` overlay described in the [PCB profile](ORANGEPI_ZERO3_PCB.md#spi-and-reset-policy); the first board uses hand-built `spi1-enable` + `spi1-cs1-touch` overlays, which supply the same CS1 and should be kept there. Do not install an SPI0 overlay or disable the SPI0 flash binding.

```bash
ls /dev/spidev*
# Expected: /dev/spidev1.1 (the first board also has /dev/spidev1.0)
```

From the repository root, inspect `scripts/configure-pi-lora.sh --help`. Its default audit reports the planned profile/runtime changes; explicit apply creates backups, preserves device identity and radio settings, and lets the app own protocol startup. Do not overwrite a live INI with sample credentials or blindly enable both daemons.

## Meshtastic hardware fragment

The [deployment template](../../../scripts/meshtastic/lora-rfm95w-opi3.yaml) contains:

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
  # Omit Reset: the PCB RESET connection shares the PMIC interrupt.
```

Remove stale `Lora.Reset` assignments from all effective configuration sources, not just this fragment. Do not substitute `Reset: -1`. The [reset hook](../../../scripts/meshtastic/reset-lora.sh) does not drive any GPIO.

## MeshCore hardware fields

The [deployment template](../../../scripts/meshcore/orangepi_zero3.ini) has:

```ini
spidev = /dev/spidev1.1
gpio_chip = gpiochip1
lora_irq_pin = 70
# Omit lora_reset_pin.
```

Keep protocol-specific RF settings and credentials unchanged. See [MeshCore setup](../../../docs/meshcore-orangepi-setup.md) and [Meshtastic setup](../../../docs/meshtastic-orangepi-setup.md) for build and readiness checks.

## Probe and select

Use [the read-only diagnostic workflow](../../../scripts/LORA_TESTING.md) with the app and all radio daemons stopped. Successful `0x12` reads verify SPI identification, not messaging. Then start the app and select the protocol under Settings → LoRa. That choice persists in the app user's settings and controls startup. If the old owner cannot stop or the new protocol fails initialization, the app reports failure; an explicit retry starts a fresh bounded attempt.
