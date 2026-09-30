# Orange Pi Zero 3 PCB profile

This is the current hardware mapping for the RFM95W/SX1276 radio and CardKB PCB, including the HDMI/USB capacitive touchscreen setup. Assume the PCB traces are connected as designed; continuity and voltage measurements are separate hardware diagnostics.

| Signal | Radio pad | Physical header pin | SoC pin / Linux GPIO |
|---|---:|---:|---|
| 3.3 V | 13 | 1 | 3.3 V supply |
| GND | 8 | 6 | Ground |
| MOSI | 3 | 19 | PH7 / 231 |
| MISO | 2 | 21 | PH8 / 232 |
| SCK | 4 | 23 | PH6 / 230 |
| NSS | 5 | 24 | PH9 / 233 / SPI1 CS1 |
| DIO0 / IRQ | 14 | 11 | PC6 / 70 |
| RESET | 6 | 7 | PC9 / 73 — shared with the PMIC interrupt |

Physical header numbers, SoC names, and GPIO line offsets are different numbering systems. On the investigated Pi the main GPIO controller is `gpiochip1`, and DIO0 is offset 70 on that chip. Verify the chip enumeration on other kernel/device-tree builds; `gpiochip1` is not a universal Linux guarantee. Physical header pin 22 is PC7/GPIO 71. It is not the PCB RESET connection.

## SPI and reset policy

The radio uses **`/dev/spidev1.1`**, SPI mode 0, 500 kHz in the diagnostic/Meshtastic profile. The relevant controller is `5011000.spi`, using the PH-bank header pins. Do not replace the SPI0 flash binding or install an SPI0 LoRa overlay.

**Enabling SPI1 on a board.** Use the overlay in [`apps/embedded/overlays/spi1-cs1-lora.dts`](../overlays/spi1-cs1-lora.dts). It enables SPI1, muxes SCK/MOSI/MISO (PH6/PH7/PH8) and CS1 (PH9), and creates `/dev/spidev1.1`. Armbian's stock `spidev1_1` overlay is not enough: it adds the spidev node but no pinctrl, and on current Armbian (kernel 6.18, verified 2026-09-30) the base device tree does not mux SPI1's pins, so the bus would reach nothing and read `0x00`/`0xff`. On the Pi:

```bash
dtc -@ -I dts -O dtb -o /tmp/spi1-cs1-lora.dtbo apps/embedded/overlays/spi1-cs1-lora.dts
sudo mkdir -p /boot/overlay-user && sudo cp /tmp/spi1-cs1-lora.dtbo /boot/overlay-user/
echo 'user_overlays=spi1-cs1-lora' | sudo tee -a /boot/armbianEnv.txt   # or append to an existing user_overlays line
echo 'SUBSYSTEM=="spidev", KERNEL=="spidev1.1", GROUP="dialout", MODE="0660"' | sudo tee /etc/udev/rules.d/60-bitchat-lora.rules
sudo apt-get install -y python3-spidev
sudo reboot
```

After the reboot `dmesg` shows `sun6i-spi 5011000.spi: registered child spi1.1`, `/dev/spidev1.1` is `root:dialout 0660`, and the read-only probe below should report `RegVersion = 0x12` without sudo. The first board was set up with hand-built `spi1-enable` and `spi1-cs1-touch` overlays whose source was never kept; if a board still has them, leave them in place (`spi1-cs1-touch` supplies the same CS1) rather than stacking this overlay on top. `/dev/gpiochip1` (DIO0) stays root-only unless a matching udev rule is added; MeshCore and Meshtastic run as root.

Software reset is **disabled** for this PCB. Do not assign either GPIO 71 or GPIO 73 as a radio output, pulse RESET, or unbind the PMIC driver. Omitting reset control does not electrically disconnect the PCB's RESET net from PC9. The radio still has power-on reset; a Pi reboot may not remove power from the module.

- Meshtastic: omit `Lora.Reset` from **all** effective YAML configuration sources, including `/etc/meshtasticd/config.yaml` and loaded fragments. Do not write `Reset: -1`; the parser enables explicitly supplied scalar mappings.
- MeshCore: omit `lora_reset_pin` from the INI. The Linux configuration defaults to `RADIOLIB_NC`, and GPIO initialization is guarded by that value.
- Direct BitChat: use the PCB profile's disabled-reset setting; never substitute a guessed GPIO.
- The retained `reset-lora.sh` hook is a deliberate no-op.

Meshtastic's `pinMapping` defaults to disabled/NC, and RadioLib HAL GPIO methods skip NC. These omission semantics were checked against the firmware dependencies used during the investigation. Check them again if upgrading those dependencies.

## Configuration and ownership

Hardware mapping does not prescribe a shared RF profile. Keep MeshCore's existing frequency/BW/SF/CR/power, Meshtastic channels and keys, and each device's identity and credentials. The example INI is for a fresh installation; do not copy it over a configured device.

The app's Settings → LoRa protocol selection is persisted in the app user's `~/.bitchat/settings/lora_settings.prefs`. That saved setting determines what starts after an app restart. The old `LORA_PROTOCOL` environment variable and `/opt/bitchat/lora-protocol.conf` are not active selection overrides.

Only the selected protocol may own the radio: MeshCore uses `meshcored.service`/TCP 5000, Meshtastic uses `meshtasticd.service`/TCP 4403, and BitChat uses SPI directly. The legacy Python `meshcore.service` is unrelated to the current C++ companion daemon and should remain disabled. Keep the current daemon units installed but disabled at boot; leave `bitchat.service` enabled so the app starts its saved protocol. Do not mask current daemon units.

Run the setup on the Pi from a complete repository tree (it needs `scripts/`, `apps/embedded/systemd/`, and `python3-yaml`). The command defaults to an audit; `--stage` can write reviewed files and backups to a new private directory without applying them. For the logged-in application account:

```bash
scripts/configure-pi-lora.sh --app-user "$USER"
# After reviewing the audit, apply explicitly and leave owners stopped for verification:
sudo scripts/configure-pi-lora.sh --apply --app-user "$USER" --leave-stopped
scripts/verify-pi-lora.sh
```

Apply records private backups and a manifest under `/var/backups/bitchat-lora`. Read `scripts/configure-pi-lora.sh --help` for staging/restore details. `verify-pi-lora.sh` reads service/configuration evidence without connecting a second TCP client or transmitting. Ordinary application deployment is not a substitute for reviewing radio configuration. App transitions must verify the old owner stopped, and a failure must block the new owner. A running process or open TCP port alone does not prove the protocol or radio is ready. An explicit user retry gets a new bounded attempt after failure.

## Read-only diagnosis

Stop the app first, then both radio daemons, before opening SPI with a diagnostic tool. Verify no manually launched daemon still owns the radio. From the repository root on the Pi:

```bash
sudo systemctl stop bitchat.service
sudo systemctl stop meshtasticd.service meshcored.service
sudo python3 scripts/lora_test.py --dump
```

The default probe requires ten consistent `RegVersion = 0x12` samples and never writes registers or requests GPIO, including on cleanup/failure. Zero, `0xff`, unexpected, or inconsistent samples are a failed identification; they do not alone prove a damaged module. `--configure` and `--transmit` are separate explicit operations described in [LoRa testing](../../../scripts/LORA_TESTING.md). Restart the app when finished so its saved protocol regains ownership.

Successful chip identification, successful protocol initialization, and successful over-the-air messaging are separate verification results.

## Documentation history

February recovery records described an Adafruit breakout and a software change to GPIO 71. Both February PCB exports instead route RESET to header 7/PC9. The earlier claim “GPIO 71 = physical pin 7” was wrong. The September 9 SPI0 diagnosis was also wrong. Historical records retain their original observations with correction notices; copied Obsidian notes must not be mistaken for a newer hardware baseline.

See [LoRa setup](LORA_SETUP.md), [MeshCore setup](../../../docs/meshcore-orangepi-setup.md), [Meshtastic setup](../../../docs/meshtastic-orangepi-setup.md), and [touch input](TOUCH_SETUP.md).
