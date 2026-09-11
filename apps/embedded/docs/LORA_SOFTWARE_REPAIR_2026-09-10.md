# Orange Pi LoRa software repair — September 10, 2026

This result supersedes the earlier software state in `docs/reviews/2026-09-10-orangepi-lora-diagnosis.md`, which retains the original investigation as historical evidence. The owner authorized software fixes against the current PCB and deferred continuity/voltage measurements.

### Changes

- Standardized on SPI1.1, IRQ gpiochip1/70. Removed software reset assignments from both loaded Meshtastic YAML files and the MeshCore INI; no output was requested on GPIO 71 or PMIC GPIO 73. Retained the SPI1 overlays and the PMIC binding.
- Shared daemon controller verifies service state, queued jobs, and exact-name processes before another owner starts. Native commands have deadlines; ambiguous start failures are cleaned up. Explicit retries clear failed state when needed. Missing installed units fail clearly, without background daemon launches.
- Manager serializes start/switch/stop/send, waits for protocol readiness, preserves local identity, and forwards through stable peer/message flows. Failed/cancelled starts tear down partial sessions. Stop failures block the next owner.
- Session scopes and readers can restart. Final shutdown joins readers before releasing descriptors; socket reconnect no longer restarts daemons. BitChat close no longer restores Meshtastic.
- Settings show operation progress, failed initialization, and retry. A saved selection is not reported as a live connection. Unsupported generic RF changes for MeshCore/Meshtastic report that their daemon settings must be configured separately. Startup and explicit switching use saved BitChat RF settings, including after disabled bootstrap.
- Bootstrap attempts once per app lifetime when the user first becomes Active and LoRa is enabled; ordinary Settings/Chat navigation does not retry failed hardware.
- MeshCore Linux reports configuration/GPIO/RadioLib initialization failures and exits 78. A tracked Portduino patch propagates failed GPIO requests. It was applied to an isolated dependency copy, preserving the earlier Pi patches. The live Meshtastic dependency source was not replaced.
- Disabled boot autostart for meshtasticd, meshcored, legacy meshcore, and obsolete xpt2046-touch. The enabled app selects the owner. Input startup accepts readable CardKB and QDtech USB touch while preserving the target/CardKB boot ordering.
- The diagnostic script defaults to read-only identity checks. All ten RegVersion reads must be 0x12 before explicit configuration/TX is permitted. Old SPI0 instructions were corrected and the erroneous overlay removed.

### Source and verification

KMP implementation branch: `fix/orangepi-lora-software`, based on `10b3aad`:

- `a5807e0` — PCB profile, diagnostics, runtime setup, USB touch, documentation.
- `293c308` — lifecycle, daemon control, settings feedback, bootstrap, saved RF settings.

MeshCore branch: `codex/orangepi-lora-startup`, commit `cb9499e`, based on `559c0b7`. Build provenance and exact dependency patch instructions are in `docs/orangepi-startup-repair-build.md` and `patches/README.md` in that branch. No push was performed.

| Verification | Result |
|---|---|
| Python diagnostic/runtime/input tests | 37 passed |
| Base LoRa JVM tests | 27 passed |
| BitChat JVM tests | 42 passed |
| MeshCore socket JVM tests | 2 passed |
| Meshtastic JVM target | compiled; no test sources |
| Repository desktop tests | 37 passed |
| Domain JVM tests | 36 passed, 2 pre-existing skips |
| Settings/viewmodel JVM tests | 29 passed |
| Design/settings screens desktop compilation | passed |
| Final embedded ARM64 app and native tests cross-compilation | passed |
| Native fake-SPI/protocol lifecycle tests executed on Pi | 4 passed |
| Installed MeshCore process failure tests on Pi | 4 passed in 1.095 s; no physical device access |

The native service controller has a maximum configured start budget of 28 seconds across inspection, conflict cleanup, startup and failure cleanup, excluding OS scheduling delays. The manager's coroutine readiness timeout does not preempt a synchronous native call; those commands enforce their own deadlines. Tests cover all six switch directions, cancellation, failed stops, explicit retries, changing configuration, and persistent subscriptions. This is separate from proving live radio communication.

### Pi deployment and rollback

Target: Orange Pi Zero 3 at `sterling@192.168.4.26`, kernel `6.12.67-current-sunxi64`.

- Runtime package: `/home/sterling/bitchat-lora-runtime-20260910`.
- Private backup/restore manifest: `/var/backups/bitchat-lora/20260911T015700.872069Z/manifest.json`. UTC date is September 11; local implementation date is September 10.
- Previous MeshCore binary: `.../meshcored.before` in that backup directory.
- Previous app release: `/opt/bitchat/releases/4c1cc3c29d77-debug-4269e4dc`.
- Final app release: `/opt/bitchat/releases/293c3087ea27-debug-e283ca09` (source `293c3087ea27`, clean debug build).
- Final app SHA-256: `231a7b3c16aa6a553d324304c7d5a2e5807b7f2c70a15982922929a2121bee9b`.
- New MeshCore SHA-256: `bf736c09651e6ee880689030f1fda1c20f347ae4398bc1344ab1a334ff68f7c3`.
- Unchanged Meshtastic SHA-256: `de0ff4b3fbe5e68c43b982c3d4bd27139b2ff654ad597f41fd9da3af28cc5192`.

To restore, stop the app and both radio owners first. Follow the manifest's per-file original/absent status and recorded modes/owners; restore daemon enable states explicitly and run daemon-reload. Restore the old MeshCore binary only if the new binary causes a regression. The previous immutable app release remains available for an atomic current-symlink rollback. Do not revert the correct SPI1.1 profile simply because radio identification still fails.

### Live app startup matrix

Started the final app with each saved protocol (BitChat, Meshtastic, MeshCore), stopping the app and owners between cases. Each reported the selected protocol, input readiness after 0 seconds, and an explicit LoRa bootstrap failure; the app stayed active with NRestarts=0. No concurrent active radio daemons remained. The original preference file was backed up and restored byte-for-byte; MeshCore remains selected. All six in-process switch directions and rapid/concurrent changes were exercised with fake protocol backends, not through physical radio messaging.

### Reboot verification

Verified a real reboot with the original MeshCore selection, boot ID `87d18e30-3d66-4fbd-8961-6d9ea455067f`. SSH initially timed out, then returned at the same `.26` address. The final app and CardKB autostarted with zero restarts; QDtech USB touch and CardKB became readable after 3 seconds. The deployed app identity and SHA-256 matched. MeshCore was the only selected radio attempt and exited 78 with zero restarts; Meshtastic and obsolete XPT2046 remained inactive/disabled. No systemd ordering-cycle messages appeared in this boot's journal.

This verifies startup with each saved protocol plus one real reboot using MeshCore; it does not claim three separate boot tests or live OTA messaging.

### Hardware outcome

With the app and daemons stopped, the read-only SPI1.1 probe returned ten `0x00` samples; expected `0x12`. No configuration writes or radio messages were sent by the probe. The repaired MeshCore daemon then reported a radio initialization failure and exited 78 with `NRestarts=0` (about 37 ms recorded CPU), rather than remaining alive with no listener.

Both Meshtastic loaded files have `Reset_present=false`. MeshCore has no reset assignment. The live GPIO ownership record still shows only the PMIC's `gpio-73 ... interrupt ... in hi IRQ`; no LoRa claims on 71 or 73. CardKB and QDtech input nodes are present.

**Radio identification remains unresolved; OTA TX/RX is not verified.** Previous working TX/RX is accepted as established history. These software fixes do not demonstrate that the old screen was electrically required, that prior operation was luck, or that the radio is defective. Module-side voltage/continuity checks remain deferred to the owner.
