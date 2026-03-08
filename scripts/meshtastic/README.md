# Meshtastic Runtime Files (Pi)

This folder stores runtime files copied from the working Orange Pi setup.

Canonical setup guide:
- [`../../docs/meshtastic-orangepi-setup.md`](../../docs/meshtastic-orangepi-setup.md)

## Files

- `reset-lora.sh`
  - Installed on Pi at: `/usr/local/bin/reset-lora.sh`
  - Used by `meshtasticd` as `ExecStartPre`.

- `lora-rfm95w-opi3.yaml`
  - Installed on Pi at: `/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml`
  - Contains LoRa hardware mapping (`spidev`, IRQ, reset lines, speed).

## Install on a Pi

```bash
sudo install -m 755 reset-lora.sh /usr/local/bin/reset-lora.sh
sudo install -m 644 lora-rfm95w-opi3.yaml /etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml
sudo systemctl daemon-reload
sudo systemctl restart meshtasticd
```

## Notes

- These are runtime deployment files, not firmware source files.
- Keep this folder in sync whenever you change Pi-side runtime config.
