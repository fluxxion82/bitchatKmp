# Teensy 4.0 LoRa Bridge

USB Serial to RFM95W LoRa bridge for BitChat.

## Hardware

### Parts Needed
- Teensy 4.0
- RFM95W module (915MHz for US, 868MHz for EU)
- Antenna for RFM95W (important!)
- Jumper wires

### Wiring

```
Teensy 4.0    RFM95W
──────────    ──────
3.3V     ──>  VCC
GND      ──>  GND
Pin 10   ──>  NSS (CS)
Pin 11   ──>  MOSI
Pin 12   ──>  MISO
Pin 13   ──>  SCK
Pin 14   ──>  DIO0
Pin 15   ──>  RST

          ┌─────────┐
          │ RFM95W  │
          │         │
    ANT ──┤         ├── VCC (3.3V)
          │         ├── GND
          │         ├── MISO (12)
          │         ├── MOSI (11)
          │         ├── SCK  (13)
          │         ├── NSS  (10)
          │         ├── DIO0 (14)
          │         ├── RST  (15)
          └─────────┘
```

⚠️ **IMPORTANT**: Always connect an antenna before powering on! Transmitting without an antenna can damage the radio.

## Flashing

### Option 1: PlatformIO (Recommended)

1. Install [PlatformIO](https://platformio.org/):
   - VS Code: Install "PlatformIO IDE" extension
   - CLI: `pip install platformio`

2. Build and upload:
   ```bash
   cd firmware/teensy-lora
   pio run -t upload
   ```

3. When prompted, press the button on the Teensy to enter bootloader mode.

### Option 2: Arduino IDE + Teensyduino

1. Install [Arduino IDE](https://www.arduino.cc/en/software)

2. Install [Teensyduino](https://www.pjrc.com/teensy/td_download.html)

3. Install LoRa library:
   - Sketch → Include Library → Manage Libraries
   - Search "LoRa" by Sandeep Mistry and install

4. Open `src/main.cpp` in Arduino IDE

5. Select:
   - Tools → Board → Teensy 4.0
   - Tools → USB Type → Serial

6. Click Upload

7. Press the button on Teensy when the Teensy Loader window appears

### Option 3: Teensy Loader (pre-built hex)

1. Build the hex file:
   ```bash
   pio run
   ```
   The hex file will be at `.pio/build/teensy40/firmware.hex`

2. Download [Teensy Loader](https://www.pjrc.com/teensy/loader.html)

3. Open Teensy Loader, select the .hex file

4. Press the button on Teensy to flash

## Testing

1. Connect Teensy to computer via USB

2. Open a serial monitor at 115200 baud

3. You should see:
   ```
   # Teensy LoRa Bridge v1.0
   # Initializing RFM95W...
   # LoRa ready at 915.00 MHz
   # Ready!
   ```

4. LED will blink 3 times when ready

## Protocol

Binary protocol over USB Serial:

### TX (Host → Teensy)
```
Byte 0:    0x01 (CMD_TX)
Byte 1-2:  Length (little-endian, max 250)
Byte 3+:   Payload
```

### RX (Teensy → Host)
```
Byte 0:    0x02 (CMD_RX)
Byte 1-2:  Length (little-endian)
Byte 3:    RSSI (signed int8)
Byte 4:    SNR (signed int8)
Byte 5+:   Payload
```

### Config Response
```
Byte 0:    0x03 (CMD_CONFIG)
Byte 1:    Status (0x00 = success)
```

### Ping/Pong
```
TX: 0x04 (CMD_PING)
RX: 0x05, 0x00 (CMD_PONG, success)
```

## Troubleshooting

**LED blinks rapidly (10 times)**: Radio init failed. Check wiring.

**No response from radio**:
- Check SPI wiring (MOSI, MISO, SCK, CS)
- Verify 3.3V power to RFM95W
- Check RST and DIO0 connections

**Short range**:
- Connect proper antenna!
- Check antenna connection
- Try reducing TX power if very close range

## Configuration

Edit these in `src/main.cpp`:

```cpp
#define RF95_FREQ    915.0   // Frequency in MHz
#define TX_POWER     17      // TX power in dBm (max 23)
#define SPREADING    9       // Spreading factor 7-12
```

Make sure all devices use the same frequency and spreading factor!
