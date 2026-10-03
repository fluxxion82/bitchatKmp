# RFM95W diagnostics on Orange Pi Zero 3

Use [the current PCB profile](../apps/embedded/docs/ORANGEPI_ZERO3_PCB.md): **SPI1.1**, IRQ GPIO 70/header 11, software reset disabled. The header SPI pins are PH7/MOSI (19), PH8/MISO (21), PH6/SCK (23), and PH9/NSS (24). The same applies to the Adafruit RFM9x breakout now fitted on both boards, whose RST pin is unconnected: the probe never needs a reset line.

The September 9 instructions assigning these pins to SPI0 were incorrect. The SPI0 flash-unbinding overlay has been removed. Enable SPI1 with the `spi1-cs1-lora` overlay on a fresh board, or keep `spi1-enable` + `spi1-cs1-touch` on the first board; either supplies LoRa CS1 even with USB touch (see the PCB profile). RESET reaches header 7/GPIO 73, shared with the PMIC interrupt. Neither that line nor unrelated GPIO 71 is a diagnostic reset output.

## Prepare without competing radio owners

Run on the Pi from the repository root. Install the Python SPI binding if needed:

```bash
sudo apt install python3-spidev
sudo systemctl stop bitchat.service
sudo systemctl stop meshtasticd.service meshcored.service
systemctl show meshtasticd.service meshcored.service -p ActiveState -p SubState -p Job
pgrep -x meshtasticd
pgrep -x meshcored
```

The service jobs must be gone and no manual daemon may remain before probing. A `pgrep` with no matches exits 1; that is expected. The obsolete Python `meshcore.service` must also be stopped if present. With the old resistive screen, stop `xpt2046-touch.service` for an isolated SPI test; the HDMI/USB touchscreen does not use SPI.

## Default: identity only, no radio changes

```bash
sudo python3 scripts/lora_test.py
sudo python3 scripts/lora_test.py --dump
```

Both commands use `/dev/spidev1.1`, mode 0, 500 kHz. They read `RegVersion` ten times; all ten values must be `0x12`. `--dump` reads a small set of non-FIFO registers, even after failed identification. Neither command requests GPIO, writes registers, configures RF settings, nor changes mode during cleanup. `--skip-gpio` remains accepted as a compatibility no-op.

Exit 0 means the requested checks passed; exit 1 means missing SPI, failed/inconsistent identification, transfer error, or failed explicit configuration/TX. Exit 2 means invalid CLI arguments. Old `--gpio-test` and `--reset-pin` options are rejected before opening SPI.

| Result | Meaning |
|---|---|
| Ten `0x12` samples | Consistent SX1276 register response; protocol/OTA unverified |
| `0x00` samples | No valid identity; not proof that the chip is damaged |
| `0xff` samples | Bus reads high; no valid identity |
| Other or inconsistent samples | Identification fails; configuration and TX are blocked |
| SPI node missing | Inspect existing SPI1 overlays and device permissions |

## Explicit write and transmit tests

These operations replace volatile radio settings with a diagnostic profile: 915 MHz, SF7, BW125 kHz, CR4/5, CRC on, sync word `0x12`, and +17 dBm. This profile is separate from MeshCore or Meshtastic network settings. Use only where that frequency and power are appropriate, with an antenna attached for transmission.

```bash
# Configure and verify register readback; no transmission
sudo python3 scripts/lora_test.py --configure

# Configure, verify, then send the raw payload BITCHAT_TEST once
sudo python3 scripts/lora_test.py --transmit
```

Failed identification prevents **all** register writes, including cleanup. Failed configuration readback prevents TX. FIFO and write-one-to-clear IRQ accesses have protocol-specific behavior rather than ordinary equality readback. A TX-done flag confirms only the radio's local transmission completion, not delivery to another device; this raw payload is not a MeshCore or Meshtastic message.

After diagnostics, restart the app so it initializes the saved protocol and restores that protocol's radio configuration:

```bash
sudo systemctl start bitchat.service
```

No software reset can be requested with the current PCB profile. Power-on reset remains available; a Pi reboot does not necessarily power-cycle the radio.

## Offline behavior tests

```bash
python3 -m unittest discover -s scripts/tests -p 'test_lora_test.py' -v
```

These run the real CLI and SPI transfer code with a fake SPI device, covering identity failures, no default GPIO/write access, explicit configuration/TX, readback failures, and descriptor cleanup. They do not test PCB continuity or OTA messaging.
