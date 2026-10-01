# Orange Pi Zero 3 LoRa investigation — 2026-09-10

The radio is **not yet working**. Two daemon configurations pointed at a nonexistent SPI device; correcting them removed that error but did not restore radio identification. The remaining failure reproduces without the app, without either daemon, and without the Linux SPI driver. Module-side measurements are needed to distinguish reset, PCB/contact, and module faults.

The owner confirms successful LoRa transmit **and** receive before replacing the GPIO resistive touchscreen with an HDMI/USB capacitive screen. That is an important constraint: the pre-existing reset wiring problem below does not, by itself, establish what changed when the screen was replaced.

## Device and scope

- Inspected `sterling@192.168.4.26`, an Orange Pi Zero 3 running `6.12.67-current-sunxi64`.
- Running app: `/opt/bitchat/releases/2429f498b1bd-debug-13b173eb/bitchat-embedded.kexe`.
- Reviewed local KMP checkout `97c131f41d1a464e2d0f6ba26330f38788997a6f`, embedded runbooks, LoRa sources, and Pi-side configuration/Portduino sources.
- Reviewed both Gerber archives under `/Users/fluxxion/3D/models/bitchat/`: `lora_schematic v2_2026-02-16.zip` and `lora_schematic v3_2026-02-22.zip`. Their LoRa signal routes agree. These are manufacturing exports, not an editable schematic. The installed assembly still needs continuity checks against the exports.

## Expanded notes and history audit

Reviewed the embedded LoRa, Meshtastic, MeshCore, touchscreen and keyboard runbooks; the two `docs/*-orangepi-setup.md` guides; `scripts/LORA_TESTING.md`; relevant firmware-fork notes; and the Obsidian notes in `/Users/fluxxion/Documents/fluxxionsVault/Projects/bitchat/KMP Docs/`. The Obsidian CLI could not connect, so the vault was read directly without edits.

The Obsidian copies identify repository commit `88bc16c` and a September 6 copy date. After removing their frontmatter and copy notices, the five embedded LoRa/touch runbook bodies match their repository counterparts exactly. They are useful for finding the original documentation, but are not independent evidence that a stated pin mapping was tested on this PCB. The other setup-guide differences concern build/deployment instructions, not a documented screen-related rewiring.

| Date / commit | Historical evidence | What it establishes |
|---|---|---|
| February 9, `0258c85` | Commit message reports Android↔Pi LoRa send/receive. Its original `apps/embedded/docs/LORA_SETUP.md` uses SPI1.1 and explicitly leaves RESET and DIO0 unconnected. | The first working setup did not depend on a successful GPIO reset pulse. The document already contains incorrect PC-bank labels for SPI, despite the correct bus and header numbers. |
| February 18, `763a099` | Commit reports Meshtastic send/receive. The recovery/runbook documentation describes an Adafruit RFM9X breakout and reset line 71. | These notes span different physical assemblies; they do not establish continuity from GPIO 71 to the custom PCB's radio reset pad. |
| March 4, `dde978b` | Commit records working MeshCore. | MeshCore support existed before the present failure. |
| March 5 runtime captures | The Meshtastic fork preserves two Pi-local dependency patches from the working device. | Both current Pi source files still match those captures byte for byte; a missing copy of either patch is not the explanation. |
| September 9 debugging transcript | SPI1.1 already returned `0x00` before the SPI0 experiment. The experiment returned `0xff`; its overlay was later removed. | The incorrect SPI0 change added a configuration error after the original failure. Reverting it cannot, by itself, explain or repair the original failure. |

The matching runtime source SHA-256 values are:

```text
LinuxGPIOPin.cpp  e9ffd63f4d5e01fdd382d1f61eb0ca033df7cb649629d843639b9d3a4c06978b
SX127x.cpp       ed088eab2f49337dd064aacdd916ae89b80cae4ecb3f8491a4715b6c6e88582c
```

Capture provenance is documented in `../forks/meshtastic-firmware/orangepi/runtime-captures/README.md` relative to the KMP repository root. Source-file equality does not establish the exact contents of the installed executable. Installed file timestamps are February 17 for `/usr/bin/meshtasticd` and March 4 for `/usr/local/bin/meshcored`.

Also inspected `docs/pics/custom_pcb.jpg`, `custom_pcb_soldered.jpg`, and `embedded_using_pcb.jpg`. They show the custom board and the old screen's stacked-header arrangement. They cannot establish present connector contact or hidden solder continuity. No separate note was found documenting a required electrical dependency on the old screen or an actual reset-trace modification on the installed PCB.

Claude's September 9 transcript contains a later suggestion that header pin 24 is PH5/CS0 and pin 26 is PH9/CS1. That mapping is also incorrect for this Orange Pi Zero 3: the verified mapping below is pin 24 = PH9/CS1 and pin 26 = PC10/GPIO 74. Do not alternate bus defaults based on that transcript.

## Confirmed configuration regression: wrong SPI controller

Both `/etc/meshcored/meshcored.ini` and `/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml` specified `spidev0.0`. Only `/dev/spidev1.0` and `/dev/spidev1.1` existed. Meshtastic logged:

```text
Failed to open posix file /dev/spidev0.0, errno=2
No hardware spi chip found...
RF95 init result -2
No RF95 radio
```

Commit `adc8362` incorrectly equates header pins 19/21/23/24 with PC2/PC0/PC1/PC3 and proposes replacing the SPI0 flash node. The Orange Pi Zero 3 header actually uses PH7/PH8/PH6/PH9. Seeing PC3 assigned to SPI0 in pinmux does **not** establish that PC3 is header pin 24. The [manufacturer's pin table](https://www.orangepi.org/orangepiwiki/index.php/Orange_Pi_Zero_3) and the live device tree/pinmux agree on SPI1.

Verified live mapping:

```text
5011000.spi -> SPI1
PH6 -> clock, PH7 -> MOSI, PH8 -> MISO
spi1-cs1-pin -> PH9 -> header pin 24
pinctrl-0 -> spi1_pins + spi1_cs1_pin
```

Therefore this installation needs **`/dev/spidev1.1`**, including with the USB touchscreen. SPI1 is still needed for LoRa. The overlay named `spi1-cs1-touch` also provides LoRa's chip select; its name is misleading. Do not remove it just because the touchscreen changed. SPI0 belongs to a separate controller with a flash device. Do not install `apps/embedded/overlays/lora-spi0-spidev.dts` for this PCB.

Both daemon paths have been restored to SPI1.1. Original files are preserved as:

```text
/etc/meshcored/meshcored.ini.bak-codex-20260910
/etc/meshtasticd/config.d/lora-rfm95w-opi3.yaml.bak-codex-20260910
```

This is a confirmed repair to configuration, **not a successful LoRa repair**. Afterward, Meshtastic still returned radio-init error `-2`; MeshCore still never opened TCP port 5000.

## Confirmed reset wiring/configuration mismatch

The Gerber traces give this mapping (module pad numbers follow the RFM95 footprint):

| RFM95 signal | Module pad | Header pin | Actual SoC GPIO/function |
|---|---:|---:|---|
| VCC | 13 | 1 | 3.3 V |
| GND | 8 | 6 | Ground |
| MOSI | 3 | 19 | PH7, GPIO 231 |
| MISO | 2 | 21 | PH8, GPIO 232 |
| SCK | 4 | 23 | PH6, GPIO 230 |
| NSS | 5 | 24 | PH9, GPIO 233, SPI1 CS1 |
| DIO0 | 14 | 11 | PC6, GPIO 70 |
| RESET | 6 | 7 | PC9, GPIO 73 |

Trace anchors for checking the Gerber interpretation: square header pad 1 is at (34.29, 11.43) mm. The reset trace joins header pin 7 at (26.67, 11.43) to the radio reset pad at approximately (49.25, 7.16). NSS joins header pin 24 at (6.35, 8.89), through a via at (45.2628, 9.2964), to radio pad 5. MISO and MOSI reach header pins 21 and 19 respectively.

The daemons configure reset as **GPIO 71**, which is **header pin 22**, not pin 7. Changing a number in configuration did not move the PCB trace. Older notes claiming “line 71 (pin 7)” are incorrect. The existing `/usr/local/bin/reset-lora.sh` is also a no-op (`LoRa reset skipped (stabilization mode)`), although the daemon's own radio initialization still uses its configured reset GPIO.

The reason GPIO 73 cannot be used as a normal reset output is now identified. The live device tree assigns PC9 to the **AXP313A power-management interrupt**:

```text
/soc/i2c@7081400/pmic@36:
  compatible = "x-powers,axp313a"
  interrupt-parent = <&pio>
  interrupts = <2 9 8>

gpio-73 (... |interrupt) in hi IRQ
/proc/interrupts: axp313a_irq_chip
```

This assignment is also present in the [upstream Linux board definition](https://github.com/torvalds/linux/blob/v6.12/arch/arm64/boot/dts/allwinner/sun50i-h618-orangepi-zero3.dts). It is not a stale touchscreen process claiming the line. A proposed upstream patch blaming the routing was challenged by the maintainer, who confirmed PC9 in the schematic; the author acknowledged the mistaken reasoning in the [discussion](https://patchew.org/linux/20260308-rc2-boot-hang-v1-0-d792d1a78dfd%40mmpsystems.pl/).

**Do not force GPIO 73 to an output, unbind the PMIC, or disable its interrupt to obtain a radio reset.** A robust hardware repair would isolate the radio reset trace from header pin 7 and route the radio side to a verified free GPIO, such as pin 22 / GPIO 71. That requires checking the actual assembly first. No such hardware change was performed. The Pi-side PC9 level was sampled high; the actual radio reset-pad voltage has not yet been measured in this session. Hence this conflict alone does not prove that reset is currently held low or that it caused the screen-change regression.

## Isolation tests and results

All direct register tests ran with `meshtasticd`, `meshcored`, the legacy `meshcore` service, and `xpt2046-touch` stopped. The app had already failed LoRa initialization, and stayed running for its UI and other transports.

The expected SX1276 silicon revision is **0x12 at register 0x42**; see the [Semtech datasheet](https://cdn-shop.adafruit.com/product-files/3179/sx1276_77_78_79.pdf).

| Test | Result |
|---|---|
| SPI1.1, mode 0, repeated RegVersion reads | `0x00` throughout |
| SPI1.0 comparison | `0xff` throughout; this does not make CS0 the correct bus |
| SPI1.1, modes 0–3, 10 kHz / 100 kHz / 500 kHz | All version reads `0x00` |
| Other registers: OpMode, FRF bytes, PaDac | All `0x00` |
| Corrected daemon configurations | Meshtastic `-2`; MeshCore active process but no port 5000 |
| Direct GPIO SPI read, bypassing Linux SPI driver | All version reads `0x00` |

For the last test, temporarily unbound **only** controller `5011000.spi`, then used libgpiod on PH6/PH7/PH8/PH9 to issue mode-0, MSB-first reads `[0x42, 0x00]`, with 1 ms clock half-periods. No radio write or reset commands were issued. The probe left PC9 untouched. With CS high, MISO followed its weak pull-up/pull-down; when CS was asserted, the sampled response was all zeros even with a weak pull-up. This is consistent with the selected external circuit pulling MISO low. It does not prove the module receives its clock/MOSI correctly or is internally healthy.

The temporary MISO pull resistor setting was disabled after testing, and the SPI controller was rebound afterward. Both `/dev/spidev1.*` nodes returned and the same failed version read reproduced. The temporary probe is `/tmp/bitchat-lora-bitbang.py` on both the Mac and Pi; it requires the controller to be unbound and is not an ordinary app-startup script.

These results substantially narrow the remaining failure to the module/reset/physical signal path. They do **not** establish that the module is dead. A supply measurement alone cannot verify clock, data, reset, or connector continuity.

## Touchscreen state

- USB capacitive input: `QDtech MPI5001`.
- Keyboard input: `CardKb-I2C`.
- `xpt2046-touch.service` was already disabled and inactive on entry.
- Boot overlays remain `i2c1 i2c2 i2c3-ph spi1-enable spi1-cs1-touch`.
- No boot configuration, overlay, PMIC setting, touchscreen setting, or app binary was changed.
- The app's startup wait still looks for `XPT2046 Touchscreen`; this can add its bounded 20-second wait with the current USB screen. It is separate from radio identification failure.

## Protocol-switching review

The current embedded app selects the saved protocol from `~/.bitchat/settings/lora_settings.prefs`; it was `MESHCORE`. `/opt/bitchat/lora-protocol.conf` is not consulted by `LoRaProtocolSelector`. The C++ daemon the app controls is **meshcored.service**. The separately installed Python **meshcore.service** is an older implementation and was inactive.

The intended Meshtastic↔MeshCore path exists:

1. `ChatRepo.switchLoRaProtocol()` calls `LoRaProtocolManager.switchProtocol()`.
2. The manager stops the old protocol and starts the new one.
3. `MeshCoreService.start()` tries to stop meshtasticd before starting meshcored.
4. `MeshtasticdService.start()` tries to stop meshcored before starting meshtasticd.
5. Closing MeshCore's serial transport also stops meshcored.

Device logs confirm the app invoked `systemctl start meshcored` successfully using its sudo permissions. The process consumed approximately one CPU core but never opened port 5000. The MeshCore firmware's `halt()` is a tight loop, and failed radio initialization reaches it before TCP setup. This explains why systemd saying **active** is not evidence that the radio works; it is consistent with the direct register failures. The existing readiness check correctly reports the missing listener.

There are secondary lifecycle defects worth fixing after radio identification is restored:

- `MeshCoreService.stop()` returns `true` even when its final check still sees the daemon running (lines 169–175).
- Its `stopMeshtasticd()` checks only `active`, ignores `activating`/auto-restart, and does not verify stop success (lines 256–262). `MeshtasticdService.stopMeshcore()` likewise ignores the stop result. This can allow competing owners during failures.
- `meshtasticd.service` is enabled at boot although the app owns protocol selection. This introduces a startup race when MeshCore is selected. Disabling automatic daemon boot, then letting the app start the selected service, matches the existing service-manager documentation.
- The direct BitChat `LoRaServiceManager` only stops Meshtastic and may restore it on radio close; it does not independently ensure that MeshCore is absent. Normal switching calls the old protocol's stop, but failed/manual-start paths remain exposed.

None explains the failed direct GPIO register read with all daemons stopped. No application code was changed or redeployed, and no successful end-to-end protocol-switch/radio test is claimed.

## Final device state and next measurements

At the end of diagnostic tests:

- `bitchat.service` remains active; USB touchscreen and keyboard input nodes exist.
- Both LoRa daemons are stopped to avoid the MeshCore busy loop and Meshtastic restart loop. Existing enable/disable settings and the user's saved protocol are unchanged.
- Both daemon configs retain the corrected SPI1.1 path and have backups.
- SPI1 is rebound and available. No PMIC or boot changes were made.

The next useful checks are physical, in this order:

1. Measure **directly on the radio**: pad 13 VCC-to-GND and pad 6 RESET-to-GND while powered. RESET should be high, near 3.3 V. Do not short it to ground as a test: the exported PCB connects it to the PMIC interrupt.
2. With power disconnected, verify continuity from radio pad 4 SCK to header 23, pad 3 MOSI to header 19, pad 2 MISO to header 21, and pad 5 NSS to header 24. Check for unintended shorts and inspect connector seating after the screen change. The direct-pin test cannot prove signals reach the module pads.
3. If practical, compare with the old screen reattached, powering down before changing GPIO connections. This is the strongest A/B test for a lost electrical connection, power/ground dependency, or mechanical pressure/contact change.
4. Once the wiring/reset question is settled, require stable `RegVersion == 0x12` before attempting daemon integration or OTA messaging. Then validate one daemon at a time and exercise protocol switching.

A full power-off is also worth distinguishing from an SSH reboot: the radio's reset pad is not being driven by the configured reset GPIO. For a cold-start comparison, shut down the Pi, disconnect its power and display USB/HDMI connections, and verify radio VCC has fallen to zero before reconnecting. A software reboot alone does not establish that the radio received a power-on reset. This physical test has not been performed during this investigation.

A safe initial version check, with radio daemons stopped, needs no reset control:

```python
import spidev
s = spidev.SpiDev()
s.open(1, 1)
s.mode = 0
s.max_speed_hz = 100_000
try:
    print([hex(s.xfer2([0x42, 0])[1]) for _ in range(10)])
finally:
    s.close()
```

## Software repair implementation — September 10, 2026

This section supersedes the earlier "left on the Pi" software state. The original investigation above is retained as historical evidence. The owner authorized software fixes against the current PCB and deferred continuity/voltage measurements.

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
