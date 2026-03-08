# RangePi LoRa Bridge Firmware

Firmware for RangePi (RP2040 + EBYTE E22-900T22S) with:
- LCD-first diagnostics (no host logs required)
- Android-compatible USB binary bridge protocol
- Optional RF beacon mode for HackRF verification

> **Status:** WIP — the RangePi transmits data but received packets cannot be decoded on the other end. And I can only get it to transmit at 868MHz

## Build and Flash

```bash
cd bitchatKmp/firmware/rangepi-lora
pio run
pio run -t upload
```

## Scripts

- `rangepi_configuration.py`: MicroPython helper to configure E22 channel/settings.
- `rangepi_beacon_test.py`: Deterministic beacon sender (`RPIB1|...|crc=`) for embedded RX bring-up.
- `e22_truth_probe.py`: One-time authoritative E22 config recovery + verification script (JSON logs).
- `legacy/rangepi_configuration_lcd.py`: Older LCD-oriented config script retained for reference.

## USB Bridge Protocol

- TX (Host -> Device): `0x01 <len_lo> <len_hi> <payload...>`
- RX (Device -> Host): `0x02 <len_lo> <len_hi> <rssi> <snr> <payload...>`
- Status: `0x03 <status>`
- Ping: `0x04`
- Pong: `0x05 <status>`

Status codes:
- `0x00`: OK
- `0x01`: packet too long
- `0x02`: timeout
- `0x03`: TX failed

## Radio Defaults

At boot, firmware attempts to set E22 configuration to:
- Channel: 18
- Frequency: 868.125 MHz (`850.125 + channel`)
- UART: 9600 8N1 to E22

## One-Time E22 Recovery (Recommended)

Use this once to force a known E22 state before beacon/bridge testing.

1. Copy `e22_truth_probe.py` to RangePi as `main.py` in BOOTSEL mode.
2. Reboot RangePi and capture serial logs.
3. Confirm final log line:
   - `{"event":"result","status":"SUCCESS","reason":"verified"}`
4. Confirm chosen tuple line is present:
   - `{"event":"selected_tuple", ... }`
5. Save the successful readback bytes into `VERIFY.md` golden profile section.

The script performs:
- mode/baud/read-length discovery (`C1 ... 08/09/0A`)
- full persistent write (`C0`)
- strict readback verify for critical fields:
  - channel 18
  - baseline transparent profile bytes
  - crypto key bytes `0x0000`

If it ends with `FAIL`, do not trust beacon RF behavior yet.

### PlatformIO-only truth probe (same flow you already use)

If you prefer not to use MicroPython, the firmware includes a temporary probe mode.

Build with:

```ini
build_flags =
  -D TRUTH_PROBE_MODE=1
```

Then:

```bash
cd bitchatKmp/firmware/rangepi-lora
pio run -t upload
pio device monitor -b 115200
```

You will see JSON-style serial events:
- `probe`
- `selected_tuple`
- `write_verify`
- `result` (`SUCCESS` or `FAIL`)

After finishing, remove `TRUTH_PROBE_MODE=1`, rebuild, and reflash normal firmware.

## LCD Diagnostics

LCD is the primary debug surface.

Top area:
- `MODE` (`BRIDGE` / `BEACON` / `IDLE`)
- `CH` and frequency
- `USB` and `E22` health
- `TX`, `RX`, `PING`, `CFG`, `ERR` counters
- last error code/text

Bottom area:
- rolling event logs (boot/config/ping/TX/RX/errors)

## Beacon Test Mode

`ENABLE_BEACON_TEST_MODE` is enabled by default.
- Sends periodic `RPIB1|seq=<uint>|mode=BEACON|chan=<int>|hz=<int>|crc=<hex4>` payload every 5s
- Auto-disables once host starts bridge traffic (ping or TX)
- Beacon `seq` is bounded to `0..99` for deterministic payload length (`53/54` bytes)
- By default beacon TX is blocked when E22 config is unverified (`E22_CONFIGURED=NO`)

To disable beacon mode at build time, add to `platformio.ini`:

```ini
build_flags =
  -D ENABLE_BEACON_TEST_MODE=0
```

To allow unsafe beacon TX while config is unverified (debug only):

```ini
build_flags =
  -D ALLOW_UNVERIFIED_BEACON_TX=1
```

## Verification

Use `VERIFY.md` for exact RF + bridge verification steps.
