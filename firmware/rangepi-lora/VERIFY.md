# RangePi Verification Guide (868.125 Bring-Up)

## 0) One-Time E22 Truth Probe (Required first)

Run `e22_truth_probe.py` on RangePi (as `main.py` in BOOTSEL mode), then capture serial output.

Pass criteria:
- final line is:
  - `{"event":"result","status":"SUCCESS","reason":"verified"}`
- includes selected tuple:
  - `{"event":"selected_tuple","m1":...,"m0":...,"baud":...,"read_len":...}`
- includes at least one `write_verify` event with:
  - `"verify_ok":true`

Fail criteria:
- final line contains `"status":"FAIL"`.
- if fail: do not trust beacon/bridge RF behavior yet.

## 1) Golden Profile Capture (lock known-good config)

From the successful truth-probe run, record here:

- Working config mode pins (M1/M0): `TBD`
- Working config UART baud: `TBD`
- Working read command length (`C1 00 00 xx`): `TBD`
- Expected readback response length: `TBD`
- Golden readback hex: `TBD`
- Confirmed channel byte value: `18`
- Confirmed crypto key bytes: `0000`

## 2) Firmware Boot Validation (RangePi)

Flash firmware via:

```bash
cd bitchatKmp/firmware/rangepi-lora
pio run -t upload
pio device monitor -b 115200
```

Pass criteria:
- startup log should not remain at `#E22_CONFIGURED=NO` after successful truth-probe provisioning.
- beacon mode should transmit only when config is verified (default safety guardrail).

## 3) RF Beacon Validation (HackRF, 868.125)

With beacon mode enabled:
- capture near `868125000`.
- TX cadence should be ~5s.

Example:

```bash
hackrf_transfer -r capture_868.raw -f 868125000 -s 2000000
```

Pass criteria:
- periodic LoRa activity aligns with RangePi TX logs.

## 4) Embedded Interop Validation (Primary success metric)

Start embedded app with probe:

```bash
ssh -t sterling@192.168.6.210 'BITCHAT_LORA_PROBE=1 /tmp/bitchat-embedded.kexe'
```

Pass criteria:
- logs show readable beacon payload / `BeaconProbeReceived` at ~5s cadence.
- no sustained fragment-assembly churn for beacon packets.

## 5) Bridge and Recovery Validation

After beacon interop:
- verify bridge ping/pong and host TX path.
- unplug/replug USB and verify reconnect + stable behavior.
