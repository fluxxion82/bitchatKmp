/**
 * Teensy 4.0 LoRa Bridge Firmware
 *
 * Bridges USB Serial <-> RFM95W LoRa radio
 * For use with BitChat Android/Desktop apps via USB OTG
 *
 * Uses LoRa library by Sandeep Mistry (simpler API than RadioHead)
 *
 * Wiring (Teensy 4.0 -> RFM95W):
 *   3.3V  -> VCC
 *   GND   -> GND
 *   10    -> NSS (CS)
 *   11    -> MOSI
 *   12    -> MISO
 *   13    -> SCK
 *   14    -> DIO0 (interrupt)
 *   15    -> RST
 *
 * Protocol (binary, little-endian):
 *   TX (Host -> Teensy): <0x01><len_lo><len_hi><payload...>
 *   RX (Teensy -> Host): <0x02><len_lo><len_hi><rssi><snr><payload...>
 *   Config response:     <0x03><status>
 *   Ping:                <0x04>
 *   Pong:                <0x05><status>
 */

#include <Arduino.h>
#include <SPI.h>
#include <LoRa.h>

// Pin definitions for Teensy 4.0
#define RFM95_CS   10
#define RFM95_DIO0 14
#define RFM95_RST  15

// LoRa radio configuration
#define RF95_FREQ      915E6  // 915 MHz - US frequency
#define TX_POWER       17     // dBm (max 20 for RFM95W)
#define SPREADING      9      // SF7-12
#define BANDWIDTH      125E3  // 125 kHz
#define CODING_RATE    5      // 4/5
#define SYNC_WORD      0xBC   // BitChat network ID

// Protocol commands
#define CMD_TX     0x01
#define CMD_RX     0x02
#define CMD_CONFIG 0x03
#define CMD_PING   0x04
#define CMD_PONG   0x05

// LED for status indication
#define LED_PIN    13

// Buffers
#define MAX_PACKET_SIZE 250
uint8_t txBuf[MAX_PACKET_SIZE];
uint8_t rxBuf[256];

// State
bool radioReady = false;
uint32_t lastRxTime = 0;
uint32_t packetsSent = 0;
uint32_t packetsRecv = 0;

void blinkLED(int times, int delayMs = 100) {
    for (int i = 0; i < times; i++) {
        digitalWrite(LED_PIN, HIGH);
        delay(delayMs);
        digitalWrite(LED_PIN, LOW);
        delay(delayMs);
    }
}

void sendResponse(uint8_t cmd, uint8_t status) {
    Serial.write(cmd);
    Serial.write(status);
}

void sendPacketToHost(uint8_t* data, uint8_t len, int8_t rssi, int8_t snr) {
    Serial.write(CMD_RX);
    Serial.write(len & 0xFF);        // len_lo
    Serial.write((len >> 8) & 0xFF); // len_hi
    Serial.write((uint8_t)rssi);     // RSSI (signed, but sent as byte)
    Serial.write((uint8_t)snr);      // SNR
    Serial.write(data, len);
}

bool initRadio() {
    // Set pins
    LoRa.setPins(RFM95_CS, RFM95_RST, RFM95_DIO0);

    // Initialize LoRa
    if (!LoRa.begin(RF95_FREQ)) {
        Serial.println("# LoRa init failed!");
        return false;
    }

    // Configure modem
    LoRa.setSpreadingFactor(SPREADING);
    LoRa.setSignalBandwidth(BANDWIDTH);
    LoRa.setCodingRate4(CODING_RATE);
    LoRa.setTxPower(TX_POWER);
    LoRa.setSyncWord(SYNC_WORD);
    LoRa.enableCrc();

    Serial.print("# LoRa ready at ");
    Serial.print(RF95_FREQ / 1E6);
    Serial.println(" MHz");

    return true;
}

void handleSerialInput() {
    if (Serial.available() < 1) return;

    uint8_t cmd = Serial.read();

    switch (cmd) {
        case CMD_TX: {
            // Wait for length bytes
            uint32_t timeout = millis() + 500;
            while (Serial.available() < 2 && millis() < timeout) { delay(1); }

            if (Serial.available() < 2) {
                sendResponse(CMD_CONFIG, 0x02); // Error: timeout
                return;
            }

            uint8_t len_lo = Serial.read();
            uint8_t len_hi = Serial.read();
            uint16_t len = len_lo | (len_hi << 8);

            if (len > MAX_PACKET_SIZE) {
                sendResponse(CMD_CONFIG, 0x01); // Error: too long
                return;
            }

            // Wait for payload
            uint16_t received = 0;
            timeout = millis() + 1000;
            while (received < len && millis() < timeout) {
                if (Serial.available()) {
                    txBuf[received++] = Serial.read();
                }
            }

            if (received != len) {
                sendResponse(CMD_CONFIG, 0x02); // Error: timeout
                return;
            }

            // Transmit!
            digitalWrite(LED_PIN, HIGH);

            LoRa.beginPacket();
            LoRa.write(txBuf, len);
            LoRa.endPacket();

            // Return to RX mode immediately after sending
            LoRa.receive();

            digitalWrite(LED_PIN, LOW);

            packetsSent++;
            sendResponse(CMD_CONFIG, 0x00); // Success
            break;
        }

        case CMD_PING: {
            // Simple ping/pong for connection test
            sendResponse(CMD_PONG, 0x00);
            break;
        }

        case CMD_CONFIG: {
            // Future: handle config commands
            sendResponse(CMD_CONFIG, 0x00);
            break;
        }

        default:
            // Unknown command - could be garbage, ignore
            break;
    }
}

void checkRadioRx() {
    int packetSize = LoRa.parsePacket();
    if (packetSize == 0) return;

    // Read packet
    uint8_t len = 0;
    while (LoRa.available() && len < sizeof(rxBuf)) {
        rxBuf[len++] = LoRa.read();
    }

    int8_t rssi = LoRa.packetRssi();
    int8_t snr = (int8_t)LoRa.packetSnr();

    // Send to host
    sendPacketToHost(rxBuf, len, rssi, snr);

    packetsRecv++;
    lastRxTime = millis();

    // Blink LED on receive
    blinkLED(1, 50);
}

void setup() {
    // Initialize LED
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);

    // Initialize USB Serial
    Serial.begin(115200);

    // Wait a bit for serial to be ready (USB enumeration)
    delay(500);

    Serial.println("# Teensy LoRa Bridge v1.0");
    Serial.println("# Initializing RFM95W...");

    // Initialize radio
    radioReady = initRadio();

    if (radioReady) {
        Serial.println("# Ready!");
        // Start in RX mode by default
        LoRa.receive();
        Serial.println("# Listening...");
        blinkLED(3, 100); // 3 blinks = ready
    } else {
        Serial.println("# ERROR: Radio init failed");
        blinkLED(10, 50); // Fast blinks = error
    }

    digitalWrite(LED_PIN, LOW);
}

void loop() {
    if (!radioReady) {
        // Try to reinit every 5 seconds
        static uint32_t lastRetry = 0;
        if (millis() - lastRetry > 5000) {
            lastRetry = millis();
            radioReady = initRadio();
            if (radioReady) {
                LoRa.receive(); // Start in RX mode
                blinkLED(3, 100);
            }
        }
        return;
    }

    // Handle incoming serial commands
    handleSerialInput();

    // Check for incoming radio packets
    checkRadioRx();
}
