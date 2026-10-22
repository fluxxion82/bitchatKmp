# Zaurus Lite OS — Rough Draft Plan

Status: **draft / proposal**. Nothing here is built yet. Facts marked *(verify)* came from old or secondhand
sources and should be checked before anyone relies on them.

> See also [ZAURUS_SBC_RETROFIT_PLAN.md](ZAURUS_SBC_RETROFIT_PLAN.md): replacing the electronics with a modern
> SBC is the more practical route to running bitchat. This plan is for the stock hardware.

Goal: a small, English, "Raspberry Pi OS Lite"-style Linux for the Sharp Zaurus **SL-C860**, booting to a console
with Wi-Fi, SSH and a package story, that can run bitchat and anything else the community wants.

---

## 1. The hardware we are targeting

| Part | SL-C860 ("boxer", `c7x0`/`corgi` family) | What it means for us |
|------|-------------------------------------------|----------------------|
| CPU | Intel PXA255 XScale, 400 MHz, **ARMv5TE, no FPU** | Soft-float `armel` only. Rules out armhf/ARMv7 distros (Alpine, Raspberry Pi OS, Arch ARMv7). |
| RAM | 64 MB | No systemd, no JVM, no browser. BusyBox-class userland. |
| Flash | 128 MB NAND + Sharp bootloader | Leave NAND alone at first; boot from SD/CF. |
| Display | 640×480 VGA, touchscreen, ATI Imageon W100 (`w100fb`) | Good framebuffer console: ~80×30 with an 8×16 font, more with smaller fonts. |
| Input | Built-in QWERTY keyboard | Needs a correct keymap (Fn layer, Japanese key labels). |
| Expansion | 1× CF Type II, 1× SD | CF is the only practical slot for Wi-Fi **or** a serial card — not both at once. |
| USB | **Client/device port only** (no host) | No USB Wi-Fi/BLE dongles or USB LoRa sticks. Use it for USB-gadget Ethernet to a PC instead. |
| Radios | IrDA only | **No Bluetooth, no Wi-Fi built in.** |
| Other | Serial lines on the bottom I/O connector, WM8731 audio | Serial cables exist; check voltage levels before wiring anything *(verify)*. |

## 2. Assessment

**Is it worth doing?** Yes, with honest expectations. It's a well-loved device with a good keyboard and screen,
lots of prior art and a small, active community (OESF forums, r/Zaurus). A clean modern image with English,
Wi-Fi and SSH would be welcome. The one thing it cannot do well is run the current bitchat codebase unchanged
(section 5).

**Is the original source available?**
- Sharp released its GPL kernel sources (2.4.18-based "Embedix/Lineo" kernel), but Sharp's developer site is
  gone, so copies only survive in community mirrors and archives *(verify)*. The Qtopia UI and the Japanese
  apps on the stock ROM were proprietary and were never released.
- We don't need any of it. The SL-C7xx machine support ("corgi") went into **mainline Linux** years ago, and
  OpenEmbedded's `meta-handheld` layer has a `c7x0` machine that explicitly lists the SL-C860.

**Biggest caveat: the kernel.** Mainline **removed the corgi (SL-C7xx) board file in Linux 6.3** (Arnd
Bergmann's PXA cleanup, early 2023). Only spitz/akita/borzoi (SL-C1000/C3x00) and gumstix stayed, and those
are now deprecated too, due to be removed unless someone converts them to device tree. So:
- **Short term:** base on **Linux 6.1.y LTS**, the last LTS that still has corgi. It is projected to get
  fixes until about **Dec 2027** *(verify on kernel.org)*.
- **Long term:** the only way to stay on current kernels is to convert corgi (and spitz) to **device tree** and
  get that upstream. That's real kernel work, but it's the most valuable thing this project could give the
  community.

**Japanese stock ROM, today:** the quickest fix is the **Cacko ROM**, a community English build of the Sharp
ROM that was made for the C7xx/C860. Use it while the real OS is being built.

**Difficulty, roughly:**

| Piece | Difficulty | Why |
|-------|-----------|-----|
| English via Cacko ROM | Easy (an evening) | Existing image, standard reflash. |
| Booting a 6.1 kernel + BusyBox rootfs from SD | Moderate (weeks of evenings) | Lots of prior art (meta-handheld, kexecboot). Main pain: the Sharp bootloader's ~1.2 MB kernel limit. |
| Polished "Pi OS Lite" image (Wi-Fi, SSH, first-boot config, suspend, battery) | Moderate–hard (a few months) | Mostly integration and testing on real hardware. |
| bitchat running *natively* | Hard | The existing KMP/JVM code can't target ARMv5. Needs a small native client (section 5). |
| Device-tree port upstreamed | Hard | Kernel-developer work plus review by linux-arm-kernel. |

## 3. Prior art to reuse (don't reinvent)

- **OpenEmbedded `meta-handheld`** (Andrea Adami): `c7x0` machine config, Zaurus kernel configs, `zaurusd`, and
  **kexecboot**.
- **kexecboot**: a tiny kernel + initramfs that fits in the Sharp kernel partition, shows a boot menu, and
  kexecs a full kernel from `/boot` on SD/CF. This works around the bootloader size limit and keeps the stock
  ROM recoverable.
- **ubuildzaurus** (github.com/gelbidar/ubuildzaurus): Buildroot-based Zaurus image tooling (tested on akita,
  5.4 kernel, SD boot via kexecboot). Close to what we want structurally. It isn't for the C860 yet, but it's the
  closest template.
- **greguu's VoidZ / ZALARM** (Void Linux and Arch ARM for the SL-Cxx00): proves a modern userland on these
  machines; worth talking to them about kernel work.
- Historical: OpenZaurus → Ångström, pdaXrom, Cacko. Useful mainly for hardware quirks, keymaps and power
  management notes.

## 4. Proposed OS design ("Zaurus Lite")

### Build system: Buildroot first
- Buildroot handles ARMv5TE soft-float, musl or glibc, BusyBox and reproducible SD images well, and is much
  easier for newcomers than Yocto.
- Trade-off: Buildroot has no package manager. Options, decided in Phase 4:
  1. Buildroot images + a published SDK/toolchain so people can cross-compile (simplest).
  2. Move to Yocto/OE + `meta-handheld` to get an `opkg` feed (most "Pi OS-like", most work).
  3. A Debian **armel** rootfs on SD for a huge package archive. But Debian 13 (trixie) is the **last** release
     with armel, and it was already removed from unstable/forky. That makes it a dead end for a new distro, though
     it's fine as a one-off experiment.

### Kernel
- Linux **6.1.y LTS**, `corgi` board file, our own defconfig, **built as a kexecboot target** (SD boot).
- Must-have config: `w100fb` + fbcon, corgi keyboard, ADS7846 touchscreen, `pxamci` (SD), PXA PCMCIA/CF
  (`pxa2xx_sharpsl`), `sharpsl_pm` (battery/charging/suspend), **USB gadget Ethernet** (CDC-ECM/RNDIS),
  `hostap_cs`/`orinoco_cs` (CF Wi-Fi), `serial_cs` (CF serial), and **`CONFIG_KUSER_HELPERS=y`** (ARMv5 has no
  LDREX/STREX; Rust and C11 atomics fall back to the kernel's cmpxchg helper).

### Userland (aim: < 20 MB RAM used at idle)
- BusyBox init (or OpenRC). **No systemd.**
- `dropbear` (SSH), `wpa_supplicant` + `iw`, `udhcpc`, `chrony`/`ntpd` (no RTC battery after long storage).
- `tmux`, `ncurses`, `nano`, `vi`, `curl`/`wget` with TLS (mbedTLS or OpenSSL), `ca-certificates`.
- Framebuffer console with a readable font (Terminus 8×16 / 6×12), proper Zaurus keymap, screen blanking.
- Power: suspend/resume on the power key, battery status in the shell prompt / `zaurus-status`.

### The "Pi OS Lite" touches
- **SD card layout:** small FAT `boot` partition (kernel, `boot.cfg`, user config) + ext4 root.
- **First-boot config file** on the FAT partition, editable from any PC, like Raspberry Pi Imager:
  `zaurus-config.txt` → hostname, Wi-Fi SSID/PSK, SSH public key, timezone, keymap.
- **USB networking out of the box:** plug into a PC → `ssh root@zaurus.local` over USB gadget Ethernet.
- `zaurus-config` text-menu tool (like `raspi-config`): Wi-Fi, users, SSH, font size, suspend timeout.
- Default user with a forced password change on first login; SSH password login off once a key is set.

### Wi-Fi reality check
- CF Wi-Fi cards are mostly 802.11b (Prism2/2.5/3 → `hostap_cs`; Orinoco/Hermes → `orinoco_cs`). WPA2 works
  with `wpa_supplicant` on Prism2 via host-side encryption *(verify per card)*.
- Many modern routers turn off 802.11b rates. Plan for "enable legacy rates" instructions, or a cheap travel
  router/phone hotspot as the documented fallback.
- Maintain a **known-good card list** in the docs. This will be the most-asked community question.

## 5. Running bitchat on it

### Why the current code can't just be cross-compiled
- This repo targets **JVM** and **Kotlin/Native `linuxArm64`** (Orange Pi). Kotlin/Native has **no 32-bit ARM
  Linux target** at all now (`linuxArm32Hfp` was deprecated and removed, and it was ARMv7 hard-float anyway).
- The JVM path is out too: OpenJDK on armel is the interpreter-only Zero VM. With 64 MB RAM, a Kotlin +
  coroutines app, let alone Compose/Skiko, isn't realistic.
- **BLE mesh**: the Zaurus has no Bluetooth, CF Bluetooth cards are BT 1.x/2.0 Classic (no LE), and there's no USB
  host for a dongle.

### Staged approach

**Stage A — Zaurus as a terminal (works almost immediately).**
Run bitchat's existing TUI on the Orange Pi / a PC (`bitchat-tui.service` + `bitchat-tui-attach` already
exist in `apps/embedded/systemd/`). The Zaurus SSHes in over Wi-Fi or USB networking and attaches to the
tmux session. No bitchat code changes. It needs tmux/terminfo and a font with decent Unicode coverage on the
Zaurus.

**Stage B — `bitchat-lite`, a small native client, Nostr first.**
- Language: **Rust** (`armv5te-unknown-linux-musleabi` / `-gnueabi` are Tier 2 with `std`) or **C** (reusing the
  same `libsodium`, `secp256k1` and `noise-c` this repo already vendors as submodules, all of which build for
  ARMv5).
- Start with **geohash/location channels over Nostr** (internet over CF Wi-Fi): WebSocket + TLS, secp256k1
  Schnorr signatures, NIP-17/NIP-44 for private messages. This needs no radio hardware.
- ncurses TUI shaped like the existing desktop/embedded TUI, so the UX stays familiar.
- Keep proof-of-work difficulty low or off. Mining on a 400 MHz no-FPU core is slow.
- Interop tests against this repo's desktop TUI and the iOS/Android clients.
- Tor/Arti: optional and probably skipped on-device (RAM/CPU). Could route via a Pi later.

**Stage C — LoRa mesh via an external node.**
This repo already speaks **Meshtastic (TCP 4403)** and **MeshCore (TCP 5000)**. Reuse the same protocols:
- Over Wi-Fi: the Zaurus talks TCP to an ESP32 Meshtastic/MeshCore node on the same network.
- Over serial: CF serial card (or the I/O-connector UART, after checking voltage levels) to a node's serial
  API. This frees the device from needing Wi-Fi, but takes the CF slot.
- Port only the subset of `data/remote/transport/lora/*` framing that `bitchat-lite` needs.

**Stage D — (stretch) BLE mesh via an ESP32 HCI-UART bridge.**
An ESP32/nRF52 running controller-only HCI-over-UART firmware, attached to a serial port, gives BlueZ a BLE
controller (`btattach`). Then implement bitchat's GATT service/characteristics in `bitchat-lite`. This is the
only path to talking to phones over the BLE mesh directly, and it's the most work.

## 6. Phased plan

### Phase 0 — Safety, English and parts (week 1)
- [ ] **Back up the NAND** with the Sharp boot/service menu's backup option before touching anything
      *(look up the exact key combo for the C860 on the OESF wiki)*.
- [ ] Flash **Cacko ROM** for an English, usable device in the meantime.
- [ ] Get: 1–2 known-good SD cards (start ≤ 2 GB, then test SDHC), a supported **CF Wi-Fi card**, optionally a
      CF serial card, a USB cable for gadget networking, and a spare battery if yours is old.
- [ ] Join OESF forums / r/Zaurus and ask about current C7xx kernel work (avoid duplicate effort).

### Phase 1 — Boot a modern kernel (weeks 2–5)
- [ ] Set up an ARMv5TE soft-float toolchain (Buildroot's, in Docker, like this repo's
      `docker/Dockerfile.linux-arm64-cross`).
- [ ] Build **kexecboot** for `c7x0` (from meta-handheld) and boot it from SD via the Sharp updater mechanism.
- [ ] Build **Linux 6.1.y** with a `corgi` defconfig; boot to a BusyBox shell on the framebuffer.
- [ ] Get a debug console: USB gadget serial/Ethernet first (serial cable as backup).
- [ ] Hardware checklist: screen, keyboard, touchscreen, SD, CF, battery reading, suspend/resume, audio.

### Phase 2 — "Zaurus Lite" v0.1 image (weeks 5–12)
- [ ] Buildroot `br2-external` tree: defconfigs, rootfs overlay, post-image script producing `zaurus-lite.img`.
- [ ] Networking: CF Wi-Fi + `wpa_supplicant`, USB gadget Ethernet, mDNS (`zaurus.local`), dropbear.
- [ ] First-boot `zaurus-config.txt` handling + `zaurus-config` menu tool.
- [ ] Keymap, console font, power key, battery status, screen blanking, clock sync.
- [ ] Measure: boot time, idle RAM, battery life in suspend and idle.
- [ ] **v0.1 release:** image + flashing guide + known-good hardware list.

### Phase 3 — bitchat (parallel with Phase 2)
- [ ] Stage A: document the "SSH into bitchat-tui" setup; test the TUI rendering on the Zaurus console.
- [ ] Stage B: `bitchat-lite` Nostr geohash client; interop tests with the desktop TUI.
- [ ] Stage C: Meshtastic/MeshCore over TCP, then serial.
- [ ] Stage D: evaluate ESP32 HCI-UART BLE.

### Phase 4 — Community and distribution
- [ ] Separate GitHub repo (e.g. `zaurus-lite`), with this doc moved there; GPL-2.0 for kernel/Buildroot glue.
- [ ] CI (GitHub Actions + Docker) that builds images and the SDK on every tag.
- [ ] Decide on packages: SDK only vs. Yocto/opkg feed (section 4).
- [ ] Docs site / wiki: install, recovery to stock, Wi-Fi card list, FAQ.
- [ ] Extend to sibling machines that share most of the work: SL-C700/750/760 (same `c7x0`), later
      SL-C1000/C3x00 (`spitz`, PXA270).

### Phase 5 — Long-term kernel future (stretch)
- [ ] Convert corgi (and help with spitz) to **device tree**, submit to linux-arm-kernel.
- [ ] If upstreaming stalls: maintain an out-of-tree 6.1.y-based kernel until its EOL, then decide.

## 7. Risks

| Risk | Mitigation |
|------|-----------|
| Bricking the device | NAND backup first; boot only from SD via kexecboot; never overwrite the bootloader. |
| 6.1 LTS goes EOL, no newer kernel has corgi | Device-tree port (Phase 5); collaborate with spitz/VoidZ folks. |
| Wi-Fi cards incompatible with modern APs | Known-good list; travel-router fallback; USB gadget networking always works. |
| bitchat-lite drifts from the protocol | Interop tests against this repo's clients in CI; implement from the shared protocol docs/code. |
| One-person bus factor | Publish early, keep the build reproducible in Docker, write things down. |

## 8. Open questions

1. Is a package manager essential for v1, or is "image + SDK" enough? (Decides Buildroot vs. Yocto.)
2. Which Wi-Fi card(s) do we have, and do they do WPA2 against a current router?
3. Rust or C for `bitchat-lite`?
4. Is someone already working on a 6.x / device-tree corgi kernel we should join instead?
5. Should the OS live in its own repo from the start? (Recommended: yes. Only the bitchat-lite interop bits
   need to stay close to this repo.)

## Sources

- Linux PXA board removal (6.3), corgi dropped: https://lkml.iu.edu/hypermail/linux/kernel/2301.0/04948.html
- Remaining sharpsl/gumstix board files deprecated: https://lkml.iu.edu/hypermail/linux/kernel/2412.0/04403.html
- Yocto `c7x0` machine (SL-C700/750/760/860): https://git.yoctoproject.org/poky/tree/meta/conf/machine/c7x0.conf?h=blinky
- meta-handheld / kexecboot: https://openembedded.org/wiki/Zaurus
- ubuildzaurus (Buildroot for Zaurus): https://github.com/gelbidar/ubuildzaurus
- ZALARM (Arch ARM on Zaurus): https://github.com/greguu/ZALARM-install
- Kotlin/Native target deprecations: https://kotl.in/native-targets-tiers
- Debian armel end of life: https://lwn.net/Articles/1033740/ ,
  https://www.phoronix.com/news/Debian-Drops-MIPS64EL-ARMEL
- Device overview: https://en.wikipedia.org/wiki/Sharp_Zaurus
