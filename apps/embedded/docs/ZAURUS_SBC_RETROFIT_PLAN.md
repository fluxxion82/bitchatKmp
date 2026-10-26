# Zaurus SBC Retrofit — Rough Draft Plan

Status: **draft / proposal**. Nothing is built yet. Items marked *(verify)* come from forum posts, retailer
listings or memory of old driver code, and must be measured or checked on the real device.

Goal: keep the Sharp Zaurus SL-C860's case, hinge and keyboard, and replace the 2003 electronics with a modern
single-board computer (SBC). The result runs a normal arm64 Linux and **this repo's existing embedded bitchat
build**, with Wi-Fi, Bluetooth LE and LoRa.

Companion doc: [ZAURUS_LITE_OS_PLAN.md](ZAURUS_LITE_OS_PLAN.md) covers a new OS for the *stock* hardware. The
retrofit makes most of that plan unnecessary for bitchat: the new board runs Armbian / Raspberry Pi OS Lite, which
already *is* the "Pi OS Lite" experience. The stock-hardware OS stays a separate, optional community project.

---

## 1. Why this is the better path for bitchat

| | Stock SL-C860 | Retrofitted |
|---|---|---|
| CPU / RAM | PXA255 ARMv5, 64 MB | Cortex-A53 ×4, 512 MB–4 GB |
| bitchat | Needs a new native client written from scratch | **Existing `linuxArm64` embedded app** (Compose or TUI) |
| BLE mesh with phones | Impossible (no Bluetooth, no USB host) | Built-in Bluetooth on the SBC |
| Wi-Fi | 802.11b CF card | Built-in Wi-Fi 4/5 |
| LoRa | Only via an external node | SPI radio in the case, using the existing profile/code |
| OS | Custom ARMv5 build, kernel support ending | Stock Armbian / Raspberry Pi OS |

## 2. What we keep, what we replace

| Keep | Replace |
|------|---------|
| Case halves, swivel hinge, stylus, screws | Mainboard (PXA255, W100 graphics, Sharp bootloader) |
| Keyboard membrane + keycaps, d-pad and app buttons | Display panel (probably, see §4) |
| Power/lid switches, LEDs where possible | Battery (probably) and all power/charging circuits |
| Speaker (if it's easy to drive) | CF/SD/USB/DC ports → USB-C + microSD access |

Don't throw away the original mainboard and panel. Keep them, or pass them on to people repairing stock units.
**Consider buying a second, broken C7xx/C860 as a donor case** so that mistakes during prototyping don't cost
the good unit (the C700/C750/C760/C860 share the same case design *(verify)*).

## 3. Physical constraints (measure these first)

### Measured so far (SL-C860, ±1 mm, by hand)

| What | Measured | NHD-3.5-640480EF-MSXP-CTP (per listings) | Fit |
|------|----------|-------------------------------------------|-----|
| Original screen module (lid) | 88.64 × 64 × 5.3 mm | Outline 76.9 × 63.9 × 4.95 mm | Thickness: ~0.35 mm spare. |
| Screen pocket in the lid | ~90.3 × 65.5 mm | Outline 76.9 × 63.9 mm | Width: ~13.4 mm spare (needs side spacers). Height: ~1.6 mm spare (~0.8 mm per side). Plastic dividers at the pocket edges could be shaved; the original screen's PCBs sit behind them. |
| Case viewport | ~80.7 × 57.3 mm | Viewing area 71.08 × 53.56 mm | ~4.8 mm black margin left/right, ~1.9 mm top/bottom. The panel outline is *narrower* than the viewport, so a black mask/bezel insert is needed to hide the panel edges. |

Still to check:
- The NHD drawing's **active-area offset**. Panel borders are usually wider on the FFC side, and with only ~0.8 mm
  of play per side in height the active area can barely be shifted to centre it in the viewport.
- Where the viewport sits within the pocket (distance from each pocket edge to the viewport edge); it may not be
  centred.
- Whether the PCBs behind the dividers only serve the old screen (they go with it) or also carry the hinge flex
  or the lid/rotation switches (keep those).
- Which edge the original flex exits from, and where the NHD's 40-pin FFC and 6-pin touch tail exit (the fold-back
  needs space).
- What the other electronics in the lid are (LCD driver/backlight board, hinge/rotation switch, other), and
  which can go once the old panel is removed.

- Closed size about **120 × 83 × 24 mm**, ~250 g *(from a C760 listing; verify on the C860)*.
- Battery (EA-BL11): about **5 × 3 × 1 cm**, 3.7 V, reported as 1700–1800 mAh *(verify)*.
- Base half: keyboard on top, mainboard + battery underneath. This is where the SBC has to go.
- Lid: very thin, holds only the panel + backlight. **No room for a display driver board in the lid.**
- **Swivel hinge:** the display cable passes through a narrow rotating channel. Anything we use must get a
  **thin flex cable** through that channel. That rules out HDMI cables, and makes slow, wide parallel
  interfaces (DPI) much easier than high-speed ones (HDMI, MIPI DSI) on a custom flex.
- Heat: a closed plastic shell with no airflow. Plan for a thermal pad to a small heatsink or metal plate, and
  accept some throttling under sustained load.
- Antennas: built-in Wi-Fi/BT antennas work in plastic, but keep them away from the battery and any metal plate.

## 4. The display decides the board

The panel is the hardest part. The original is a Sharp 3.7" 640×480 "CG Silicon" panel with drivers on the
glass, configured over a serial link by a Sharp timing controller (the kernel's `corgi_lcd` driver). No public
datasheet or pinout turned up. **Reusing it is a research project.** Plan on a new panel unless someone finds
the pinout.

A **3.5" 4:3 640×480 IPS** panel fits the 3.7" window almost perfectly (slightly smaller active area) and keeps
the original resolution. Waveshare sells one with a **DPI (parallel RGB666)** interface and I2C capacitive touch.

### Candidate panels

Size check: the original 3.7" 4:3 active area is about **75 × 56 mm**. A 3.5" 4:3 panel is about **71–72 × 53–55 mm**,
so it shows a thin extra black border (~2 mm per side) inside the original window. Visually fine, but it needs a
printed shim/bezel to centre it.

| Panel | Interface | Fits which board | Fit in the lid |
|-------|-----------|------------------|----------------|
| [Waveshare 3.5inch DPI LCD](https://www.waveshare.com/3.5inch-dpi-lcd.htm) (SKU 19173; [Amazon B08TBF5PHH](https://www.amazon.com/Waveshare-3-5inch-Capacitive-LCD-DPI/dp/B08TBF5PHH)), 640×480 IPS, I2C cap touch, ~$35 | DPI666 via the 40-pin header | Raspberry Pi (Option A) | **As sold, no:** it's a HAT that plugs directly onto the Pi (panel + toughened cover glass on an adapter board, ~80 g). In the Zaurus the Pi sits in the base and the panel in the lid, so the panel would have to come off its adapter and get a new flex through the hinge. Whether its FPC is separable is unknown until one is in hand. **Great for the Phase 1 bench prototype.** |
| [Waveshare 3.5inch HDMI LCD (E)](https://www.amazon.com/3-5inch-HDMI-Capacitive-LCD-Resolution/dp/B0B2LJY1S5), 640×480 IPS, cap touch | HDMI | Orange Pi Zero 2W (Option B) | Driver board + HDMI connector behind the panel; likely too thick for the lid *(verify)*. Bench use only. |
| [Waveshare 3.5inch DSI LCD (E)](https://www.waveshare.com/3.5inch-dsi-lcd-e.htm), 640×480 IPS, cap touch | MIPI DSI | Boards with a DSI port: Pi 4/5, CM4/CM5 (Option C). **Not** the Pi Zero 2 W (no DSI) | Unknown; check its outline drawing. |
| [Waveshare 3.5inch TFT Touch Shield](https://www.waveshare.com/product/displays/lcd-oled/lcd-oled-2/3.5inch-tft-touch-shield.htm), **480×320**, ILI9486, XPT2046 resistive touch | SPI (~10 slow signals) | **Any board**, incl. Orange Pi and Pi Zero 2 W, with no bridge | Outline 86.0 × 57.2 mm fits the 90.3 × 65.5 pocket; display area 74.2 × 49.8 mm leaves ~3.3 mm / ~3.8 mm margins in the 80.7 × 57.3 window. **Thickness not published:** it's an Arduino shield (header pins, SD slot under the PCB), so it only fits the 5.3 mm pocket with the headers removed, or the panel taken off the PCB. Hinge-friendly wiring. Trade-offs: half the pixels of the original (60×20 vs 80×30 text at 8×16), ~10–15 fps, and the Compose app needs work to render to an SPI panel (the TUI works on the console as-is). |
| [Newhaven NHD-3.5-640480EF-MSXP-CTP](https://newhavendisplay.com/content/specs/NHD-3.5-640480EF-MSXP-CTP.pdf), 640×480 IPS, 810-nit sunlight-readable, FT5426 cap touch | MIPI DSI, 40-pin 0.5 mm FFC + 6-pin touch | DSI boards (Option C) | **Best candidate for the final build:** a bare panel (thinnest), full datasheet with an outline drawing, viewing area 71.68 × 54.56 mm. |

Also checked: Newhaven's own HDMI version (NHD-3.5-HDMI-HR-RSXP, 640×480) is **92 × 63.9 × 15.34 mm** (17.09 mm with
touch, `-CTU`), which is too wide for the 90.3 mm pocket and about 3× too thick for the lid. Bench use only.

#### More candidates (second search; numbers from distributor listings / kernel sources, **(u)** = unverified)

| Part | Res / interface | Outline W×H×T (mm) | Drive | Notes |
|------|-----------------|--------------------|-------|-------|
| **Sharp LS037V7DW01** (DW05 = resistive-touch variant) | 3.7" 480×640 portrait, transflective, parallel RGB18, **no SPI init** (strap pins only) | 65 × 89.2 × 4.4 (u) | Pi Zero 2 W DPI; OPi needs an HDMI→RGB bridge | **Closest match to the original** (same 3.7" VGA size; our pocket is 90.3 × 65.5). Mainline `panel-sharp-ls037v7dw01` driver. Obsolete: eBay/old stock only, ~$65 (u). Needs 90° rotation in software. |
| Ortustech COM37H3M05DTC / 99UTC | 3.7" 480×640, RGB24, mainline `panel-simple` | 65 × 89; 99UTC (touch) ~5.8 thick | Same as above | Fits in outline, but the touch version is ~0.5 mm too thick (u). Obsolete. |
| **BuyDisplay ER-TFT035-7** / Focus LCDs E35GG-I-CW350-N | 3.5" 640×480 IPS landscape, NV3052C, RGB24 + 3-wire SPI init, 54-pin FPC | 76.84 × 63.84 × 3.2 (no touch) | Pi Zero 2 W DPI + bit-banged SPI init; OPi via a matching HDMI board | **Current production**, datasheet available, mainline `panel-newvision-nv3052c` driver. Optional cap/resistive touch (thickness with touch u). ~5 mm side margins to mask. |
| BuyDisplay ER-TFT035-9 | 3.5" 640×480 IPS, MIPI DSI (NV3051) | 76.74 × 63.79 × 3.0 | CM4/CM5, or an HDMI→DSI bridge | Thin and cheap (~$11 at qty 10), but DSI only. |
| **3.5" 640×480 HDMI kit** (VS035T-001A 54-pin panel + UT-HDMI035-style board; BitBuilt group buy) | HDMI board → panel FPC | Panel ~76.8 × 63.8 class (u) | **OPi Zero 2W (or Pi) over plain HDMI** | **Simplest Orange Pi route:** board in the base, only the panel FPC crosses the hinge. Buy panel + board as a matched pair (some panels need an init sequence). |
| **LESOWN M35-A01S** ([listing](https://www.lesown.com/products/lesown-3-5-inch-small-screen-lcd-panel-display-54pin-resolution-640x480-hd-ips-hdmi-usb-powered-module-for-computer-pc-laptop)) | 3.5" 640×480 IPS, 54-pin TTL RGB panel + PCB800850 HDMI driver board (mini-HDMI in, micro-USB 5 V) | Panel 76.84 × 63.84 × **3.32**; board 51.3 × 45.7 | **Either board over plain HDMI**, no GPIO used | Same outline as the ER-TFT035-7 class. Fits the lid with ~2 mm of depth left (room for a touch overlay); ~5 mm side margins to mask. No touch. The listing also says "800x800" in one place; confirm 640×480 with the seller. The board must live in the base, and the 54-pin cable has to cross the hinge (needs an extension; check the hinge channel width). |
| Anbernic RG35XX / Fascontek FS035VG158 / Leadtek LTK035C5444T spares | 3.5" 640×480 IPS, RGB24 + SPI init (NV3052C driver) | (u) | Pi DPI + SPI init | Cheap spares with a Linux driver, but no published outline/pinout. |

Pi Zero 2 W + DPI pin budget: DPI18 uses GPIO 0–21 (including hardware I2C/SPI0), which leaves GPIO 22–27 for the
panel's SPI init, touch (i2c-gpio) and reset. That's enough for the display, but not for LoRa too, so LoRa moves to the
companion MCU (§5). About 25 wires must cross the hinge for DPI.

#### Connecting the Sharp LS037V7DW01 to the Pi Zero 2 W (chosen candidate)

There's no off-the-shelf adapter for this panel, so we build a small one. Signal chain:

```
LS037V7DW01 ──51-pin 0.3 mm FPC──▶ LID BOARD ──~30-pin 0.5 mm FFC through hinge──▶ BASE BOARD ──▶ Pi Zero 2 W GPIO
 (lid)                              (lid, in the freed       (thin, flexible, loops    (soldered to the
                                     space beside the panel)  like the original)         Pi's 40-pin header)
```

What we know about the panel (needs the full Sharp spec, LCP-06008A, to confirm):
- **FPC:** 51 pins, 0.3 mm pitch *(reseller listing, u)*. Pin 1 LED+, pin 3 LED−, pins 24–26 VCC 3.3 V, several GND. RGB/control
  pin positions are not confirmed yet.
- **Logic:** 3.3 V (3.0–3.6 V), the same as the Pi's GPIO, so no level shifting.
- **Backlight:** white LED string, **~27.9 V at 16–20 mA**. Needs a small boost constant-current LED driver (TPS61160-class),
  PWM-dimmed from a Pi GPIO.
- **Control pins** (from the Linux binding): INI (power on), RESB (reset), MO (low = 480×640 VGA), LR and UD (scan
  direction). MO/LR/UD can be tied with resistors, leaving INI and RESB on GPIOs.
- **Timing** (mainline `panel-sharp-ls037v7dw01.c`): 19.2 MHz pixel clock, 480×640, hfp 1 / hsync 2 / hbp 28, vfp 1 /
  vsync 1 / vbp 1. Well within the Pi's DPI range.
- **Touch:** the DW05 variant has a 4-wire resistive layer → a TSC2007 (I2C, mainline driver) on the lid board, or the 4
  lines to the RP2040's ADC.

Boards:
- **Lid board:** 51-pin 0.3 mm ZIF connector for the panel, LED boost driver, 3.3 V load switch (for power sequencing),
  strap resistors, touch controller, and an FFC connector for the hinge cable.
- **Hinge cable (~30 lines):** 18 RGB data + PCLK + HSYNC + VSYNC (+ DE) + INI + RESB + backlight PWM + touch I2C/IRQ +
  3.3 V + 5 V + several GND. An off-the-shelf 0.5 mm FFC first, a custom flex later if it doesn't survive the swivel.
- **Base board:** FFC connector → the Pi's GPIO in DPI RGB666 mode (GPIO 0–21), INI/RESB/PWM/touch on GPIO 22–27.

Software:
- `dtoverlay=vc4-kms-dpi-generic` with the timings above and `rgb666-padhi`, INI/RESB driven by a small script. Or, better:
  a small overlay that binds the mainline `sharp,ls037v7dw01` panel driver to the Pi's DPI output, so the kernel handles
  power-up and reset. Check that Raspberry Pi OS builds `CONFIG_DRM_PANEL_SHARP_LS037V7DW01`; if not, build it as a module.
- The panel is portrait (480×640). Rotate 90° in software (`fbcon=rotate:1` for the console; the Compose app's GL
  path for the GUI). The LR/UD straps only flip it 180°.

Bench prototype before any PCB: a generic FPC-to-pin breakout + jumper wires to the Pi header + an adjustable LED
driver module. Keep the 28 V backlight lines away from everything else.

**DW05 (touch) note:** the touch version's listing gives **61 pins** (vs 51 on the DW01), so the touch lines are
probably on the same FPC *(u)*. Get the **DW05** spec for the pin map and pitch. To check the pitch by hand, measure
the contact width: ~18.3 mm ≈ 0.3 mm pitch, ~30.5 mm ≈ 0.5 mm.

**"Touch screen digitizer" in listings:** this is the resistive touch sheet glued on top of the LCD. Sharp's own spec
title for the DW01 is "Transflective LCD module with touch panel", so many DW01s already have one, probably on its own
small 4-wire tail *(u)*. Some eBay listings sell the digitizer alone ("mit Kleber" = with adhesive). If the tail is a
4-pin 1.0 mm FPC, it plugs straight into the Adafruit TSC2007 breakout (product 5423), which has a 1 mm FPC connector.

**FPC breakout:** no off-the-shelf 51- or 61-pin breakout turned up (only 45-pin 0.3 mm and 60-pin 0.5 mm boards). Plan
on a tiny custom breakout PCB with the matching ZIF connector, assembled by JLCPCB/PCBWay. This is effectively lid
board v0.

**Choosing the ZIF connector** (one per panel variant; panels on order: one DW01 without touch, one DW05 with touch):
1. Measure each FPC end: contact count; first-to-last contact centre distance = (N−1) × pitch (51 @ 0.3 mm → 15.0 mm,
   61 @ 0.3 mm → 18.0 mm, 51 @ 0.5 mm → 25.0 mm, 61 @ 0.5 mm → 30.0 mm); FPC thickness at the contacts (usually 0.2 mm
   for 0.3 mm pitch, 0.3 mm for 0.5 mm pitch); which face has the exposed contacts.
2. Parametric search on LCSC (best, because JLCPCB can then assemble it), DigiKey or Mouser, category "FFC, FPC
   Connectors": positions, pitch, FPC thickness, contact side (top/bottom, or dual to avoid the question),
   right-angle SMT, flip-lock or back-flip actuator. Families to check: Hirose FH26W / FH35C (0.3 mm), Hirose FH12
   (0.5 mm), Molex Easy-On 0.3 mm, plus generic LCSC equivalents.
3. Have the board assembled. 0.3 mm pitch is not hand-solderable for most people.

**Touch on the DW05:** it has a single ribbon, so the four touch lines (XL/XR/YU/YD) are on the 61-pin FPC. The breakout
PCB routes them to pads that wire to the Adafruit TSC2007's X+/X−/Y+/Y− 0.1" pins (not its 1 mm FPC socket), and the
TSC2007's SDA/SCL/IRQ/3V/GND go to the Pi. A later revision puts the bare TSC2007 chip on the lid board. The DW01 has
no touch, so use it for display bring-up only.

**Pi Zero 2 W pin budget:** DPI RGB666 takes GPIO 0–21 (including the hardware I2C, SPI and PWM pins). GPIO 22–27 are
left: INI, RESB, backlight enable, touch SDA/SCL (`i2c-gpio`) and touch IRQ. That's exactly six, with nothing spare.
If more pins are needed, tie RESB to INI, or move touch to the RP2040.

**Adapter parts, bench prototype:**

| Part | Purpose | Notes |
|------|---------|-------|
| 61-pin FPC-to-DIP breakout board with ZIF connector (pitch per datasheet) | Gets the panel's FPC onto 2.54 mm pins | Match pitch and contact side (top/bottom) |
| Raspberry Pi Zero 2 W with a soldered header | Host | |
| Boost constant-current LED driver for ~28–32 V, 16–20 mA (TPS61160/TPS61169 eval module or LCD-backlight driver board) | Backlight | Must have open-LED overvoltage protection |
| **TSC2007** breakout (e.g. Adafruit's STEMMA QT TSC2007 board, u) | 4-wire resistive touch → I2C | Mainline `tsc2007` driver; needs the PENIRQ line on a GPIO |
| 10 kΩ resistors | Straps: MO low (VGA), LR/UD high (normal scan) | |
| Short jumper wires, plenty of ground wires | DPI at 19.2 MHz works over ~10 cm | Keep the clock wire short |

**Adapter parts, custom PCBs (Phase 3):**
- **Lid board:**
  - 61-pin ZIF connector;
  - TPS61160-class boost driver with inductor (~22 µH), Schottky diode, a ≥50 V output cap and a sense resistor
    (≈ 0.2 V / 18 mA ≈ 11 Ω);
  - TSC2007 + decoupling;
  - a load switch for the panel's 3.3 V (power sequencing), strap resistors, decoupling caps;
  - a ~30-pin 0.5 mm ZIF connector for the hinge FFC.
- **Hinge:** ~30-pin 0.5 mm FFC, length measured through the swivel.
- **Base board:** ~30-pin 0.5 mm ZIF connector, pads to solder onto the Pi's 40-pin header, and 22–33 Ω series resistors
  on PCLK/data (less ringing and EMI).

#### Driving the NHD (MIPI DSI) panel
- **Neither the Orange Pi Zero 2W/Zero 3 nor the Pi Zero 2 W can drive it directly.** The H616/H618 only output
  HDMI (and composite); the Pi Zero 2 W has no DSI connector.
- **Orange Pi (or Pi Zero 2 W) route: an HDMI→MIPI DSI bridge** (e.g. Lontium LT6911C, HDMI 1.4 → 1–4-lane DSI) on a
  small board in the base. Linux just sees a 640×480 HDMI monitor, so no display driver is needed. The catch: the
  bridge has to be configured with the panel's FL7703NI init sequence and timings (Lontium firmware or a vendor
  config), and it needs a backlight LED driver and the panel's 3.0 V supply. **Get a bridge vendor to confirm they can
  configure it for this panel before buying anything.** Wiring: SBC mini-HDMI → flat HDMI ribbon → bridge (base) →
  40-pin DSI flex through the hinge → panel.
- **Compute-module route:** a Raspberry Pi CM4/CM5 (or another DSI-capable board) drives it natively. Newhaven
  describes the panel as Raspberry Pi compatible *(verify what driver/overlay they provide)*.
- **Touch (either route):** the FT5426 is on its own 6-pin tail. It goes to I2C + an interrupt GPIO (+ reset) on
  the SBC and uses the mainline `edt-ft5x06` driver.

Lid status: the PCBs behind the pocket dividers only connect to the old screen's ribbon cables, so they come out
with it and free that space. Bezel window: ~80.7 × 57.3 mm.

Takeaway: the Waveshare DPI panel is the right *first* screen to buy, to prove 640×480, touch and the software. A
clean in-case build is easiest with a **bare panel on its own FFC**, which points toward a DSI panel + a board with
DSI (compute module), unless the Waveshare DPI panel turns out to be separable from its adapter.

### Option A — Raspberry Pi Zero 2 W + 3.5" 640×480 DPI panel (cleanest display)
- 65 × 30 mm, RP3A0 (4× Cortex-A53), **512 MB**, Wi-Fi 4 + BT 4.2/BLE.
- Raspberry Pis drive DPI panels straight from GPIO. No bridge chip, ~25 MHz pixel clock, flex-friendly through
  the hinge.
- Cost: DPI uses about **22 of the 28 GPIOs**. That leaves too few pins for an SPI LoRa radio + I2C touch, so
  move the keyboard (and possibly LoRa) to a companion MCU over USB (§5).
- Risk: 512 MB has never been tested with the Compose embedded app. The TUI will certainly fit.
- The embedded binary links against Debian bookworm arm64 libraries. Raspberry Pi OS Lite 64-bit is
  bookworm/trixie-based, so it should run, but the DRM/GBM/EGL path on `vc4` needs testing.

### Option B — Orange Pi Zero 2W (keeps the H618 platform)
- 65 × 30 mm, **H618 — the same SoC as the Orange Pi Zero 3 this repo already targets**, 1–4 GB LPDDR4,
  Wi-Fi 5 + BT 5.0, 40-pin header, 24-pin expansion FPC.
- Software is nearly drop-in: Armbian, `deploy-pi.sh`, SPI LoRa, overlays (new pin profile needed; the 40-pin
  header numbering differs from the Zero 3's).
- Cost: **the only display output on the board is mini-HDMI.** That leaves two choices:
  - an HDMI→RGB/MIPI bridge board (e.g. TFP401- or Lontium-based) in the base, then a flex to the panel. It
    adds size, ~0.5 W and one more board;
  - or a **3.5" SPI panel (480×320)**, e.g. the Waveshare 3.5inch TFT Touch Shield (see the candidate table). It's slow
    but fine for text/TUI, and loses the 640×480 resolution and 4:3 fit. Correction to an earlier draft: the repo
    has **not** run an SPI *display* before. The old Elecrow screen was HDMI video with SPI (XPT2046) *touch*. The
    touch side is supported; the display side is new work (see the shield row).

### Option C — Compute module on a custom carrier (best end result, v2)
- A Raspberry Pi CM4/CM5-class module on a PCB shaped like the Zaurus mainboard, with DSI/DPI, charger, MCU,
  LoRa and connectors all on one board. RAM up to 8 GB, eMMC.
- Most design work (a 4–6 layer PCB with fine-pitch connectors). Do this only after A or B proves the concept.

### Not recommended: Orange Pi Zero 3 as-is
55 × 50 mm, with a tall Ethernet jack and USB-A port that would need desoldering. It has the same HDMI-only
display problem as the Zero 2W in a worse shape. The Zero 2W is the same SoC in a better form factor.

**Recommendation:** buy one of each of A and B for the bench (they're cheap) and decide in Phase 1 by running
the Compose app on both at 640×480. My lean is **A for v1** (the display is clean and hinge-friendly) unless 512 MB
proves too tight, then **C for v2**.

## 5. Companion microcontroller (one small board does all the "glue")

An **RP2040** (e.g. a Waveshare RP2040-Zero-sized board, or a bare chip on the later custom PCB) connected to
the SBC over **USB**:

- **Keyboard:** scan the Zaurus keyboard matrix and appear as a **USB HID keyboard** (QMK or KMK firmware).
  - The embedded app already reads keyboards through **evdev** (`apps/embedded/compose/.../KeyboardInput.kt`),
    so a USB HID keyboard needs **no app changes**, and it also works in the console, over SSH and everywhere
    else.
  - Old kernel `corgi` keyboard code suggests an 8×12 matrix (20 lines) with the Fn layer handled in software
    *(verify by tracing the flex)*. Its keycode table is a ready-made starting point for the QMK keymap.
- **Power button and soft power:** the Zaurus power key wakes the MCU, which enables the 5 V boost and later
  tells the SBC to shut down cleanly (a GPIO line or a USB message), then cuts power.
- **Lid / screen-rotation sensors:** reuse the original switches/magnets for screen blanking and rotation.
- **LEDs:** charge and notification LEDs.
- **Optional LoRa:** put the RFM95W on the MCU and reuse the **USB-serial LoRa bridge** firmware this repo
  already has (`firmware/teensy-lora`, `firmware/rangepi-lora`). Note: the `linuxArm64` LoRa transport currently
  talks **SPI** directly (`SpiPort.kt`). A USB-serial bridge transport exists for Android but would need porting
  to `linuxArm64`. With Option B, keep LoRa on SPI as today and skip this.

## 6. Power

- **Battery:** either reuse the EA-BL11 pack (if its protection circuit is healthy) or fit a flat LiPo sized to
  the battery bay. Aim higher than 1700 mAh if it fits.
- **Charger + 5 V boost:** a single-chip USB-C charger/boost (TI BQ2589x-class) on the custom PCB, or an
  off-the-shelf "power bank" module for the prototype.
- **Fuel gauge:** MAX17048 over I2C (Linux `max17040` driver) for a real battery percentage.
- **Rough budget:** SBC 1–2 W idle/light use + panel/backlight 0.5–1 W against ~6.5 Wh gives about **3–5 h** of
  use. Measure in Phase 1; suspend support on these SoCs is limited, so "off" should really be off.
- Low-battery shutdown handled by a small daemon, so the SD card doesn't get corrupted.

## 7. Radios and antennas

- **BLE mesh:** works out of the box. Both boards have Bluetooth, and the repo already has a Linux BLE path
  (`apps/embedded/docs/BLUETOOTH_LINUX_ARM64.md`).
- **Wi-Fi:** built in, so Nostr/geohash channels and Tor work like on the Orange Pi today.
- **LoRa:** RFM95W/SX1276 per `ORANGEPI_ZERO3_PCB.md`, or an SX1262 module. Antenna options: an internal flex
  antenna along the case edge, or an **SMA/u.FL connector through the old CF-slot opening** (best range).
- Keep the LoRa antenna away from the Wi-Fi antenna and the SBC.

## 8. Ports and mechanics

- **USB-C** (charge + data) at the old DC jack / USB position.
- **CF slot opening:** LoRa SMA connector, and/or a USB-A port.
- **SD slot opening:** a microSD extension to the SBC's card slot, so the card can be swapped without opening the
  case.
- **3D-printed internal brackets** that use the original screw bosses to hold the SBC, battery and MCU.
- Keep the keyboard flex intact. Add a matching FPC connector on a small breakout board (measure pin count and
  pitch first).

## 9. Software

- **Base OS:** Armbian minimal (Option B) or Raspberry Pi OS Lite 64-bit (Option A). Both are already
  "Pi OS Lite".
- **Our additions** (a small "zaurus-retrofit" layer: a script or `.deb`):
  - device-tree overlays: display (DPI or SPI), touch (I2C), fuel gauge, LoRa SPI;
  - power daemon: power key, clean shutdown, low battery, lid → screen off;
  - console font + keymap for the 640×480 screen; QMK firmware + keymap for the RP2040;
  - the **existing bitchat embedded app** deployed with `scripts/deploy-pi.sh` and `bitchat.service`.
- **Repo changes expected (small):**
  - a new board profile doc (like `ORANGEPI_ZERO3_PCB.md`) with the new GPIO mapping;
  - checking the Compose layout at **640×480** (the current rigs use larger screens);
  - optionally a `linuxArm64` USB-serial LoRa transport (only for the MCU-LoRa route).

## 10. Phased plan

### Phase 0 — Teardown and measurement (1–2 weeks)
- [ ] Back up anything on the stock device you care about. Open it carefully and photograph every layer.
      Disassembly steps for the C-series screen: [furrtek.org Zaurus LCD repair](http://www.furrtek.org/?a=zauruslcd&i=2).
      The orange flex cables break easily, and two small white plastic tabs can fall out when the case opens.
      No published internal dimensions were found, so **measure before ordering the final panel.**
- [ ] Measure the base cavity (with and without the battery), lid cavity, hinge cable channel, screw bosses and
      port openings. Record them in a drawing (even a hand sketch with numbers helps).
- [ ] Keyboard flex: count pins and measure pitch; map the matrix with a multimeter.
- [ ] Identify the power, lid and rotation switches and the LEDs, and how they connect.
- [ ] Decide: donor case or the good unit.

### Phase 1 — Bench prototypes, outside the case (3–6 weeks)
- [ ] Pi Zero 2 W + 3.5" 640×480 DPI panel: boot Raspberry Pi OS Lite, run the embedded app (TUI, then
      Compose), and check RAM headroom.
- [ ] Orange Pi Zero 2W + display candidate (HDMI bridge or SPI): same tests.
- [ ] RP2040 + Zaurus keyboard on a breakout → USB HID with a full keymap, Fn layer included.
- [ ] Power: charger/boost module + battery + fuel gauge; measure real power draw and estimate runtime.
- [ ] LoRa: send and receive against another bitchat node with the radio mounted where it will sit in the case.
- [ ] **Pick the board.**

### Phase 2 — First in-case build, hand-wired (3–6 weeks)
- [ ] 3D-printed brackets; fit the SBC, MCU, battery and power board.
- [ ] Route the display through the hinge (an off-the-shelf FPC extension first, custom flex later).
- [ ] Thermal check: sustained load with the lid closed; add a pad or heatsink as needed.
- [ ] Daily-use test for a week: battery life, keyboard feel, Wi-Fi/BLE/LoRa range.

### Phase 3 — Custom PCB (2–4 months)
- [ ] KiCad board shaped to the base cavity: SBC mount (or CM socket for Option C), RP2040, charger/boost, fuel
      gauge, LoRa module + antenna connector, USB-C, keyboard FPC, display/touch FPC, speaker amp (I2S, e.g.
      MAX98357A).
- [ ] Custom flex PCB for the hinge display cable.
- [ ] Order prototypes (JLCPCB/PCBWay offer assembly); bring up, revise, re-order.

### Phase 4 — Software polish and image
- [ ] The "zaurus-retrofit" setup layer, plus a prebuilt image with bitchat preinstalled.
- [ ] First-boot setup: Wi-Fi, user, SSH key (reuse Raspberry Pi Imager / Armbian first-run tools).
- [ ] Update path: `deploy-pi.sh` for development, packaged releases for users.

### Phase 5 — Open hardware release
- [ ] Separate repo (e.g. `zaurus-retrofit`): KiCad files, STLs, BOM, QMK keymap, setup scripts, build guide
      with photos, CERN-OHL or similar for hardware.
- [ ] Post to OESF forums / r/Zaurus / Hackaday.io and invite people to build one and report back.
- [ ] Variants for SL-C1000/C3x00 (similar case, different keyboard matrix) if there's interest.

## 11. Rough BOM (prototype, USD, approximate)

| Part | Cost |
|------|------|
| Pi Zero 2 W *or* Orange Pi Zero 2W (1–4 GB) | $15–35 |
| 3.5" 640×480 DPI IPS panel w/ touch (or SPI/HDMI alternative) | $30–45 |
| RP2040 board | $5 |
| Charger/boost + fuel gauge boards | $10–20 |
| LiPo battery | $10–15 |
| RFM95W/SX1262 + antenna/connector | $15–25 |
| FPC connectors, extension flex, wires, microSD extender | $15–25 |
| 3D printing | $5–20 |
| **Prototype total** | **~$105–190** |
| Custom PCB + flex, small batch (Phase 3, per round) | $50–150 |

## 12. Risks

| Risk | Mitigation |
|------|-----------|
| Not enough room in the base for SBC + battery | Measure first (Phase 0); thinner battery; custom PCB that combines boards; Option C. |
| Display cable through the swivel hinge | Prefer DPI (slow, wide, flex-friendly); design a custom flex; test hinge cycles. |
| 512 MB too small for the Compose app (Option A) | Run the TUI, or switch to Option B/C. |
| Heat in a sealed case | Thermal pad to a plate; CPU frequency cap; test early. |
| Damaging the only good unit | Donor case; non-destructive mounting; keep the original board. |
| Battery safety | Use a protected cell, a proper charger IC and a fuse; never charge an unprotected pack unattended. |

## 13. Open questions

1. Is keeping the original screen important to you, or is a modern 3.5" IPS panel fine? (This decides most
   of the hardware.)
2. Compose UI or TUI as the main interface? (Decides whether 512 MB is enough.)
3. LoRa on SPI from the SBC, or on the companion MCU over USB?
4. Do you want the speaker/headphone audio working, or is it OK to skip?
5. One-off for you first, or design for other builders from day one? (Affects how early the custom PCB
   happens.)

## Sources

- Orange Pi Zero 2W specs: https://linux-sunxi.org/Xunlong_Orange_Pi_Zero2W ,
  https://www.cnx-software.com/2023/09/09/orange-pi-zero-2w-raspberry-pi-zero-2w-alternative-4gb-ram/
- Waveshare 3.5" 640×480 DPI LCD: https://www.waveshare.com/product/raspberry-pi/displays/lcd-oled/3.5inch-dpi-lcd.htm
- Raspberry Pi DPI interface overview: https://wiki.recalbox.com/en/tutorials/video/tft/tft-lcd-on-dpi-bus
- Zaurus C7xx display/driver notes (NetBSD): https://nxr.netbsd.org/xref/src/sys/arch/zaurus/dev/zlcd.c?r=1.1
- C760 dimensions listing: https://www.hwupgrade.it/forum/archive/index.php/t-1556069.html
- C860 battery discussion: https://www.mobileread.com/forums/showthread.php?p=34697
- Device overview: https://en.wikipedia.org/wiki/Sharp_Zaurus
