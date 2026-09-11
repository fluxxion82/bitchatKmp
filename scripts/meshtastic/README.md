# Meshtastic runtime files for the Orange Pi PCB

These files implement the [current PCB profile](../../apps/embedded/docs/ORANGEPI_ZERO3_PCB.md). They are deployment inputs, not evidence of successful radio initialization.

- `lora-rfm95w-opi3.yaml` supplies SPI1.1, 500 kHz, and IRQ GPIO 70. It intentionally omits reset control.
- `reset-lora.sh`, installed at `/usr/local/bin/reset-lora.sh` for an existing `ExecStartPre` hook, is an explicit no-op. The PCB RESET net shares the PMIC interrupt. It must not pulse GPIO 71 or 73.

From the repository root use `scripts/configure-pi-lora.sh --help` for the audit/explicit-apply workflow. Review all effective Meshtastic YAML files for stale `Lora.Reset` fields before applying; simply installing this fragment does not erase assignments from another file. Omit the field rather than supplying `Reset: -1`.

Preserve Meshtastic node identity, channels, keys, and RF settings. Keep its service installed and disabled at boot; the app starts it when the saved protocol is Meshtastic. Do not blindly restart it during installation while another protocol owns SPI.

See the [Meshtastic guide](../../docs/meshtastic-orangepi-setup.md) for build and readiness checks and [LoRa testing](../LORA_TESTING.md) for a read-only probe.
