/**
 * RangePi LoRa Bridge Firmware
 *
 * LCD-first diagnostics + binary bridge protocol for Android BitChat.
 *
 * USB protocol (binary):
 *   TX (Host -> Device): <0x01><len_lo><len_hi><payload...>
 *   RX (Device -> Host): <0x02><len_lo><len_hi><rssi><snr><payload...>
 *   Status response:     <0x03><status>
 *   Ping:                <0x04>
 *   Pong:                <0x05><status>
 */

#include <Arduino.h>
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>

// E22 pins
#define LORA_TX 0
#define LORA_RX 1
#define LORA_M0 2
#define LORA_M1 3
#define LORA_AUX 4

// LCD pins
#define TFT_CS 9
#define TFT_DC 8
#define TFT_RST 12
#define TFT_BL 13
#define TFT_SCK 10
#define TFT_MOSI 11

#define LED_PIN 25

// UART mapping for RP2040
#define LoRaSerial Serial1

// Binary protocol commands
static const uint8_t CMD_TX = 0x01;
static const uint8_t CMD_RX = 0x02;
static const uint8_t CMD_CONFIG = 0x03;
static const uint8_t CMD_PING = 0x04;
static const uint8_t CMD_PONG = 0x05;

// Status codes
static const uint8_t STATUS_OK = 0x00;
static const uint8_t STATUS_PACKET_TOO_LONG = 0x01;
static const uint8_t STATUS_TIMEOUT = 0x02;
static const uint8_t STATUS_TX_FAILED = 0x03;

// E22 config constants
static const uint8_t E22_CMD_READ = 0xC1;
static const uint8_t E22_CMD_WRITE = 0xC0;
static const uint8_t E22_REG0 = 0x62; // 9600 8N1, air rate 2.4kbps
static const uint8_t E22_REG1 = 0x00; // 200B packet, 22 dBm
static const uint8_t E22_REG2_CH18 = 18; // 868.125 MHz (850.125 + 18)
static const uint8_t E22_DEFAULT_CHANNEL = E22_REG2_CH18; // 868.125 MHz bring-up default
static const uint8_t E22_REG3 = 0x03; // RSSI byte enabled

static const uint32_t USB_BAUD = 115200;
static const uint32_t LORA_UART_BAUD = 9600;
static const uint16_t MAX_PACKET_SIZE = 232;

// Optional beacon mode for RF validation.
// Set to 0 for bridge-only behavior.
#ifndef ENABLE_BEACON_TEST_MODE
#define ENABLE_BEACON_TEST_MODE 1
#endif
// Safety guardrail: avoid false confidence from RF activity when E22 config
// has not been verified in this boot.
#ifndef ALLOW_UNVERIFIED_BEACON_TX
#define ALLOW_UNVERIFIED_BEACON_TX 0
#endif
// Temporary recovery mode to run E22 truth-probe over serial using normal
// PlatformIO firmware upload flow.
#ifndef TRUTH_PROBE_MODE
#define TRUTH_PROBE_MODE 0
#endif

static const uint32_t BEACON_INTERVAL_MS = 5000;
static const uint32_t RX_IDLE_FLUSH_MS = 5;  // Reduced from 25ms for faster RX forwarding
static const uint32_t E22_BASE_HZ = 850125000;
static const uint32_t E22_CHANNEL_STEP_HZ = 1000000;

Adafruit_ST7789 tft = Adafruit_ST7789(&SPI1, TFT_CS, TFT_DC, TFT_RST);

struct DeviceState {
  bool bridgeActive = false;
  bool e22Configured = false;
  bool usbSeen = false;
  bool beaconEnabled = ENABLE_BEACON_TEST_MODE;

  uint8_t channel = E22_DEFAULT_CHANNEL;
  float frequencyMHz = 868.125f;

  uint32_t txCount = 0;
  uint32_t rxCount = 0;
  uint32_t pingCount = 0;
  uint32_t cfgCount = 0;
  uint32_t errCount = 0;
  uint32_t beaconSeq = 0;

  uint32_t lastBeaconMs = 0;
  uint32_t lastLoraRxByteMs = 0;
  uint32_t lastStatusRefreshMs = 0;

  char lastError[32] = "OK";
};

static DeviceState state;
static bool statusDirty = true;

static const uint8_t LOG_LINES = 5;
static char logLines[LOG_LINES][40];
static uint8_t logIndex = 0;

static uint8_t hostTxBuffer[MAX_PACKET_SIZE];
static uint8_t loraRxBuffer[256];
static uint16_t loraRxLen = 0;
static uint32_t activeLoRaBaud = LORA_UART_BAUD;

struct UartProbeConfig {
  uint32_t baud;
  uint32_t serialCfg;
  const char *label;
};

struct UartRouteConfig {
  uint8_t txPin;
  uint8_t rxPin;
  const char *label;
};

#ifndef ENABLE_UART_ROUTE_SWAP_PROBE
#define ENABLE_UART_ROUTE_SWAP_PROBE 0
#endif

#if ENABLE_UART_ROUTE_SWAP_PROBE
static const UartRouteConfig UART_ROUTE_CANDIDATES[] = {
  {LORA_TX, LORA_RX, "tx0_rx1"},
  {LORA_RX, LORA_TX, "tx1_rx0"}
};
#else
static const UartRouteConfig UART_ROUTE_CANDIDATES[] = {
  {LORA_TX, LORA_RX, "tx0_rx1"}
};
#endif

void setLoRaUartRoute(const UartRouteConfig &route) {
  LoRaSerial.end();
  LoRaSerial.setTX(route.txPin);
  LoRaSerial.setRX(route.rxPin);
}

void beginLoRaSerial(uint32_t baud, uint32_t serialCfg) {
  LoRaSerial.begin(baud, serialCfg);
  activeLoRaBaud = baud;
}

enum HostParseState {
  WAIT_CMD,
  WAIT_LEN_LO,
  WAIT_LEN_HI,
  WAIT_PAYLOAD
};

static HostParseState hostState = WAIT_CMD;
static uint16_t hostExpectedLen = 0;
static uint16_t hostReadLen = 0;

void logLine(const char *level, const char *msg) {
  char line[40];
  snprintf(line, sizeof(line), "%s %s", level, msg);
  strncpy(logLines[logIndex], line, sizeof(logLines[logIndex]) - 1);
  logLines[logIndex][sizeof(logLines[logIndex]) - 1] = '\0';
  logIndex = (logIndex + 1) % LOG_LINES;
  statusDirty = true;
}

void setError(const char *msg) {
  strncpy(state.lastError, msg, sizeof(state.lastError) - 1);
  state.lastError[sizeof(state.lastError) - 1] = '\0';
  state.errCount++;
  logLine("E", msg);
}

void drawStatus() {
  tft.fillScreen(ST77XX_BLACK);
  tft.setTextSize(1);
  tft.setTextWrap(false);

  tft.setTextColor(ST77XX_CYAN);
  tft.setCursor(2, 2);
  tft.print("RangePi v2-DBG");

  tft.setTextColor(ST77XX_WHITE);
  tft.setCursor(2, 14);
  tft.print("MODE:");
  tft.print(state.bridgeActive ? "BRIDGE" : (state.beaconEnabled ? "BEACON" : "IDLE"));

  tft.setCursor(2, 26);
  tft.print("CH:");
  tft.print(state.channel);
  tft.print(" F:");
  tft.print(state.frequencyMHz, 3);
  tft.print("MHz");

  tft.setCursor(2, 38);
  tft.print("USB:");
  tft.print(state.usbSeen ? "YES" : "NO");
  tft.print(" E22:");
  tft.print(state.e22Configured ? "OK" : "NO");

  tft.setCursor(2, 50);
  tft.print("TX:");
  tft.print(state.txCount);
  tft.print(" RX:");
  tft.print(state.rxCount);

  tft.setCursor(2, 62);
  tft.print("B:");
  tft.print((unsigned long)activeLoRaBaud);
  tft.print(" CFG:");
  tft.print(state.cfgCount);

  tft.setCursor(2, 74);
  tft.print("PING:");
  tft.print(state.pingCount);
  tft.print(" ERR:");
  tft.print(state.errCount);

  tft.drawFastHLine(0, 86, 240, ST77XX_BLUE);

  tft.setTextColor(ST77XX_GREEN);
  for (uint8_t i = 0; i < LOG_LINES; i++) {
    uint8_t idx = (logIndex + i) % LOG_LINES;
    tft.setCursor(2, 90 + (i * 9));
    tft.print(logLines[idx]);
  }
  statusDirty = false;
}

void sendStatus(uint8_t status) {
  Serial.write(CMD_CONFIG);
  Serial.write(status);
}

void sendPong(uint8_t status) {
  Serial.write(CMD_PONG);
  Serial.write(status);
}

void sendRxPacketToHost(const uint8_t *payload, uint16_t len, int8_t rssi, int8_t snr) {
  Serial.write(CMD_RX);
  Serial.write((uint8_t)(len & 0xFF));
  Serial.write((uint8_t)((len >> 8) & 0xFF));
  Serial.write((uint8_t)rssi);
  Serial.write((uint8_t)snr);
  Serial.write(payload, len);
  Serial.flush();  // Ensure USB buffer is flushed immediately
}

void setE22Mode(bool m1, bool m0) {
  digitalWrite(LORA_M0, m0 ? HIGH : LOW);
  digitalWrite(LORA_M1, m1 ? HIGH : LOW);
  delay(30);
}

bool waitAux(uint32_t timeoutMs) {
  uint32_t start = millis();
  while (digitalRead(LORA_AUX) == LOW) {
    if ((millis() - start) > timeoutMs) {
      return false;
    }
    delay(2);
  }
  return true;
}

int readE22Response(uint8_t *buf, size_t maxLen, uint32_t timeoutMs) {
  uint32_t start = millis();
  size_t n = 0;
  while ((millis() - start) < timeoutMs && n < maxLen) {
    while (LoRaSerial.available() && n < maxLen) {
      buf[n] = (uint8_t)LoRaSerial.read();
      // Debug: Log each byte received from E22
      Serial.print("#DBG: E22 resp byte[");
      Serial.print(n);
      Serial.print("]: 0x");
      if (buf[n] < 0x10) Serial.print("0");
      Serial.println(buf[n], HEX);
      n++;
    }
    delay(2);
  }
  return (int)n;
}

void drainLoRaSerialBounded(uint32_t maxMs, size_t maxBytes) {
  uint32_t start = millis();
  size_t drained = 0;
  while ((millis() - start) < maxMs && drained < maxBytes) {
    if (!LoRaSerial.available()) {
      break;
    }
    (void)LoRaSerial.read();
    drained++;
  }
}

int parseE22Channel(const uint8_t *resp, int readLen) {
  if (readLen < 7 || resp[0] != E22_CMD_READ) {
    return -1;
  }

  // E22 response format seen on RangePi scripts uses channel at byte 5.
  int ch = resp[5];
  if (ch < 0 || ch > 83) {
    // Fallback for variant frame layouts.
    ch = resp[6];
    if (ch < 0 || ch > 83) {
      return -1;
    }
  }
  return ch;
}

bool configureE22Channel18() {
  uint8_t resp[16];
  const uint8_t readFrame[] = {E22_CMD_READ, 0x00, 0x00, 0x08};
  const UartProbeConfig uartCandidates[] = {
    {9600, SERIAL_8N1, "8N1"},
    {9600, SERIAL_8E1, "8E1"},
    {9600, SERIAL_8O1, "8O1"},
    {19200, SERIAL_8N1, "8N1"},
    {19200, SERIAL_8E1, "8E1"},
    {19200, SERIAL_8O1, "8O1"},
    {38400, SERIAL_8N1, "8N1"},
    {57600, SERIAL_8N1, "8N1"},
    {115200, SERIAL_8N1, "8N1"},
    {4800, SERIAL_8N1, "8N1"},
    {2400, SERIAL_8N1, "8N1"},
    {1200, SERIAL_8N1, "8N1"}
  };
  const bool modeCandidates[][2] = {
    {true, true},   // M1=1,M0=1 (E22 sleep/config mode)
    {true, false},  // fallback for board/mode mapping differences
    {false, true}   // fallback for board/mode mapping differences
  };

  int discoveredChannel = -1;
  bool gotRead = false;
  int selectedRouteIdx = 0;

  for (uint8_t r = 0; r < (sizeof(UART_ROUTE_CANDIDATES) / sizeof(UART_ROUTE_CANDIDATES[0])) && !gotRead; r++) {
    setLoRaUartRoute(UART_ROUTE_CANDIDATES[r]);
    for (uint8_t m = 0; m < (sizeof(modeCandidates) / sizeof(modeCandidates[0])) && !gotRead; m++) {
      for (uint8_t b = 0; b < (sizeof(uartCandidates) / sizeof(uartCandidates[0])); b++) {
      uint32_t baud = uartCandidates[b].baud;
      uint32_t serialCfg = uartCandidates[b].serialCfg;
      const char *uartLabel = uartCandidates[b].label;

      // Debug: Log each config attempt
      Serial.print("#DBG: Trying route=");
      Serial.print(UART_ROUTE_CANDIDATES[r].label);
      Serial.print(" ");
      Serial.print("#DBG: Trying baud=");
      Serial.print(baud);
      Serial.print(" cfg=");
      Serial.print(uartLabel);
      Serial.print(" mode M1=");
      Serial.print(modeCandidates[m][0] ? 1 : 0);
      Serial.print(" M0=");
      Serial.println(modeCandidates[m][1] ? 1 : 0);

      beginLoRaSerial(baud, serialCfg);
      delay(60);
      drainLoRaSerialBounded(30, 64);

      setE22Mode(modeCandidates[m][0], modeCandidates[m][1]);
      if (!waitAux(500)) {
        logLine("W", "AUX wait timeout");
        Serial.println("#DBG: AUX wait timed out (stayed LOW)");
      } else {
        Serial.println("#DBG: AUX went HIGH (ready)");
      }

      Serial.println("#DBG: Sending read config frame...");
      LoRaSerial.write(readFrame, sizeof(readFrame));
      LoRaSerial.flush();
      delay(120);

      int readLen = readE22Response(resp, sizeof(resp), 250);
      Serial.print("#DBG: Got ");
      Serial.print(readLen);
      Serial.println(" response bytes");

      int ch = parseE22Channel(resp, readLen);

      char attempt[40];
      snprintf(
          attempt,
          sizeof(attempt),
          "CFG m%d%d b%lu %s r%d ch%d",
          modeCandidates[m][0] ? 1 : 0,
          modeCandidates[m][1] ? 1 : 0,
          (unsigned long)baud,
          uartLabel,
          readLen,
          ch
      );
      logLine(ch >= 0 ? "I" : "W", attempt);

      if (ch >= 0) {
        discoveredChannel = ch;
        selectedRouteIdx = r;
        gotRead = true;
        break;
      }
    }
    }
  }

  if (!gotRead) {
    setE22Mode(false, false);
    waitAux(200);
    setError("No E22 cfg response");
    Serial.println("#E22_TROUBLESHOOT no_valid_cfg_response");
    Serial.println("#E22_TROUBLESHOOT check_gp0_gp1_jumpers_installed_for_onboard_fw=1");
    Serial.println("#E22_TROUBLESHOOT note_remove_jumpers_only_for_external_usb_ttl=1");
    Serial.println("#E22_TROUBLESHOOT check_e22_module_seated=1");
    Serial.println("#E22_TROUBLESHOOT check_m0_m1_aux_wiring=1");
    Serial.println("#E22_TROUBLESHOOT check_uart_path_gp0_gp1_to_e22=1");
    return false;
  }

  state.channel = (uint8_t)discoveredChannel;
  state.frequencyMHz = 850.125f + (float)discoveredChannel;

  uint8_t cfgFrame[] = {E22_CMD_WRITE, 0x00, 0x00, E22_REG0, E22_REG1, E22_REG2_CH18, E22_REG3};
  LoRaSerial.write(cfgFrame, sizeof(cfgFrame));
  LoRaSerial.flush();
  delay(180);
  (void)readE22Response(resp, sizeof(resp), 250);

  LoRaSerial.write(readFrame, sizeof(readFrame));
  LoRaSerial.flush();
  delay(120);
  int verifyLen = readE22Response(resp, sizeof(resp), 250);

  setE22Mode(false, false);
  waitAux(200);
  setLoRaUartRoute(UART_ROUTE_CANDIDATES[selectedRouteIdx]);
  beginLoRaSerial(LORA_UART_BAUD, SERIAL_8N1);
  activeLoRaBaud = LORA_UART_BAUD;
  delay(60);

  int verifiedChannel = parseE22Channel(resp, verifyLen);
  if (verifiedChannel >= 0) {
    state.channel = (uint8_t)verifiedChannel;
    state.frequencyMHz = 850.125f + (float)verifiedChannel;
    state.e22Configured = (verifiedChannel == E22_REG2_CH18);
    if (state.e22Configured) {
      logLine("I", "E22 CH18 configured");
      return true;
    }
    setError("CH verify failed");
    return false;
  }

  setError("Post-write verify fail");
  return false;
}

int scoreProbeResponse(const uint8_t *resp, int readLen) {
  if (readLen <= 0) return -1;
  int score = 0;
  if (readLen >= 4) score += 10;
  if (resp[0] == E22_CMD_READ) score += 35;
  if (readLen >= 7) score += 30;
  int ch = parseE22Channel(resp, readLen);
  if (ch >= 0) score += 20;
  return score;
}

void runTruthProbeMode() {
  const UartProbeConfig uartCandidates[] = {
    {9600, SERIAL_8N1, "8N1"},
    {9600, SERIAL_8E1, "8E1"},
    {9600, SERIAL_8O1, "8O1"},
    {19200, SERIAL_8N1, "8N1"},
    {19200, SERIAL_8E1, "8E1"},
    {19200, SERIAL_8O1, "8O1"},
    {38400, SERIAL_8N1, "8N1"},
    {38400, SERIAL_8E1, "8E1"},
    {38400, SERIAL_8O1, "8O1"},
    {57600, SERIAL_8N1, "8N1"},
    {57600, SERIAL_8E1, "8E1"},
    {57600, SERIAL_8O1, "8O1"},
    {115200, SERIAL_8N1, "8N1"},
    {115200, SERIAL_8E1, "8E1"},
    {115200, SERIAL_8O1, "8O1"},
    {4800, SERIAL_8N1, "8N1"},
    {2400, SERIAL_8N1, "8N1"},
    {1200, SERIAL_8N1, "8N1"}
  };
  const bool modeCandidates[][2] = {
    {false, false}, // M1=0 M0=0
    {false, true},  // M1=0 M0=1
    {true, false},  // M1=1 M0=0
    {true, true}    // M1=1 M0=1
  };
  const uint8_t readLens[] = {0x08, 0x09, 0x0A};

  int bestScore = -999;
  int bestR = -1;
  int bestM = -1;
  int bestB = -1;
  int bestL = -1;
  uint8_t bestResp[32] = {0};
  int bestRespLen = 0;

  Serial.println("{\"event\":\"start\",\"mode\":\"truth_probe\",\"channel\":18}");

  for (uint8_t r = 0; r < (sizeof(UART_ROUTE_CANDIDATES) / sizeof(UART_ROUTE_CANDIDATES[0])); r++) {
    Serial.print("{\"event\":\"route_start\",\"uart_route\":\"");
    Serial.print(UART_ROUTE_CANDIDATES[r].label);
    Serial.println("\"}");
    setLoRaUartRoute(UART_ROUTE_CANDIDATES[r]);
    for (uint8_t m = 0; m < (sizeof(modeCandidates) / sizeof(modeCandidates[0])); m++) {
      Serial.print("{\"event\":\"mode_start\",\"uart_route\":\"");
      Serial.print(UART_ROUTE_CANDIDATES[r].label);
      Serial.print("\",\"m1\":");
      Serial.print(modeCandidates[m][0] ? 1 : 0);
      Serial.print(",\"m0\":");
      Serial.print(modeCandidates[m][1] ? 1 : 0);
      Serial.println("}");
      for (uint8_t b = 0; b < sizeof(uartCandidates) / sizeof(uartCandidates[0]); b++) {
        uint32_t baud = uartCandidates[b].baud;
        uint32_t serialCfg = uartCandidates[b].serialCfg;
        const char *uartLabel = uartCandidates[b].label;
        beginLoRaSerial(baud, serialCfg);
        delay(30);
        drainLoRaSerialBounded(20, 64);

        setE22Mode(modeCandidates[m][0], modeCandidates[m][1]);
        bool auxReady = waitAux(500);

        for (uint8_t l = 0; l < sizeof(readLens); l++) {
          uint8_t readFrame[] = {E22_CMD_READ, 0x00, 0x00, readLens[l]};
          LoRaSerial.write(readFrame, sizeof(readFrame));
          LoRaSerial.flush();
          delay(80);

          uint8_t resp[32] = {0};
          int readLen = readE22Response(resp, sizeof(resp), 140);
          int ch = parseE22Channel(resp, readLen);
          int score = scoreProbeResponse(resp, readLen);

          Serial.print("{\"event\":\"probe\",\"uart_route\":\"");
          Serial.print(UART_ROUTE_CANDIDATES[r].label);
          Serial.print("\",\"m1\":");
          Serial.print(modeCandidates[m][0] ? 1 : 0);
          Serial.print(",\"m0\":");
          Serial.print(modeCandidates[m][1] ? 1 : 0);
          Serial.print(",\"baud\":");
          Serial.print(baud);
          Serial.print(",\"uart_cfg\":\"");
          Serial.print(uartLabel);
          Serial.print("\"");
          Serial.print(",\"read_len\":");
          Serial.print((int)readLens[l]);
          Serial.print(",\"aux_ready\":");
          Serial.print(auxReady ? "true" : "false");
          Serial.print(",\"resp_len\":");
          Serial.print(readLen);
          Serial.print(",\"score\":");
          Serial.print(score);
          Serial.print(",\"channel\":");
          Serial.print(ch);
          Serial.println("}");

          if (score > bestScore) {
            bestScore = score;
            bestR = r;
            bestM = m;
            bestB = b;
            bestL = l;
            bestRespLen = readLen > (int)sizeof(bestResp) ? (int)sizeof(bestResp) : readLen;
            for (int i = 0; i < bestRespLen; i++) bestResp[i] = resp[i];
          }
        }
      }
    }
    Serial.print("{\"event\":\"route_done\",\"uart_route\":\"");
    Serial.print(UART_ROUTE_CANDIDATES[r].label);
    Serial.println("\"}");
  }

  if (bestScore < 30 || bestR < 0 || bestM < 0 || bestB < 0 || bestL < 0 || bestRespLen < 7) {
    Serial.println("{\"event\":\"result\",\"status\":\"FAIL\",\"reason\":\"no_valid_tuple\"}");
    setE22Mode(false, false);
    setLoRaUartRoute(UART_ROUTE_CANDIDATES[0]);
    beginLoRaSerial(LORA_UART_BAUD, SERIAL_8N1);
    activeLoRaBaud = LORA_UART_BAUD;
    return;
  }

  // Re-enter chosen tuple.
  setLoRaUartRoute(UART_ROUTE_CANDIDATES[bestR]);
  beginLoRaSerial(uartCandidates[bestB].baud, uartCandidates[bestB].serialCfg);
  delay(60);
  setE22Mode(modeCandidates[bestM][0], modeCandidates[bestM][1]);
  bool selectedAux = waitAux(1200);

  Serial.print("{\"event\":\"selected_tuple\",\"m1\":");
  Serial.print(modeCandidates[bestM][0] ? 1 : 0);
  Serial.print(",\"m0\":");
  Serial.print(modeCandidates[bestM][1] ? 1 : 0);
  Serial.print(",\"uart_route\":\"");
  Serial.print(UART_ROUTE_CANDIDATES[bestR].label);
  Serial.print("\"");
  Serial.print(",\"baud\":");
  Serial.print(uartCandidates[bestB].baud);
  Serial.print(",\"uart_cfg\":\"");
  Serial.print(uartCandidates[bestB].label);
  Serial.print("\"");
  Serial.print(",\"read_len\":");
  Serial.print((int)readLens[bestL]);
  Serial.print(",\"aux_ready\":");
  Serial.print(selectedAux ? "true" : "false");
  Serial.print(",\"score\":");
  Serial.print(bestScore);
  Serial.println("}");

  // Build target data from discovered frame shape (C1 + addr + offset + data...).
  int dataLen = bestRespLen - 3;
  if (dataLen < 4 || dataLen > 20) {
    Serial.println("{\"event\":\"result\",\"status\":\"FAIL\",\"reason\":\"baseline_shape_invalid\"}");
    setE22Mode(false, false);
    setLoRaUartRoute(UART_ROUTE_CANDIDATES[0]);
    beginLoRaSerial(LORA_UART_BAUD, SERIAL_8N1);
    activeLoRaBaud = LORA_UART_BAUD;
    return;
  }

  uint8_t targetData[20] = {0};
  for (int i = 0; i < dataLen; i++) targetData[i] = bestResp[3 + i];
  targetData[0] = E22_REG0;
  targetData[1] = E22_REG1;
  targetData[2] = E22_REG2_CH18;
  targetData[3] = E22_REG3;
  if (dataLen >= 2) {
    targetData[dataLen - 2] = 0x00; // CRYPT high (best effort)
    targetData[dataLen - 1] = 0x00; // CRYPT low  (best effort)
  }

  bool verified = false;
  const uint8_t readFrame[] = {E22_CMD_READ, 0x00, 0x00, 0x08};
  for (int attempt = 1; attempt <= 3 && !verified; attempt++) {
    uint8_t writeFrame[24] = {0};
    writeFrame[0] = E22_CMD_WRITE;
    writeFrame[1] = 0x00;
    writeFrame[2] = 0x00;
    for (int i = 0; i < dataLen; i++) writeFrame[3 + i] = targetData[i];
    LoRaSerial.write(writeFrame, 3 + dataLen);
    LoRaSerial.flush();
    delay(220);
    uint8_t ack[24] = {0};
    int ackLen = readE22Response(ack, sizeof(ack), 220);

    LoRaSerial.write(readFrame, sizeof(readFrame));
    LoRaSerial.flush();
    delay(180);
    uint8_t verifyResp[32] = {0};
    int verifyLen = readE22Response(verifyResp, sizeof(verifyResp), 280);
    int verifiedChannel = parseE22Channel(verifyResp, verifyLen);

    bool fieldsOk = false;
    if (verifyLen >= (3 + dataLen) && verifyResp[0] == E22_CMD_READ) {
      fieldsOk = true;
      for (int i = 0; i < 4; i++) {
        if (i < dataLen && verifyResp[3 + i] != targetData[i]) fieldsOk = false;
      }
      if (dataLen >= 2) {
        if (verifyResp[3 + dataLen - 2] != 0x00 || verifyResp[3 + dataLen - 1] != 0x00) {
          fieldsOk = false;
        }
      }
    }
    verified = (verifiedChannel == E22_REG2_CH18) && fieldsOk;

    Serial.print("{\"event\":\"write_verify\",\"attempt\":");
    Serial.print(attempt);
    Serial.print(",\"ack_len\":");
    Serial.print(ackLen);
    Serial.print(",\"readback_len\":");
    Serial.print(verifyLen);
    Serial.print(",\"verify_ok\":");
    Serial.print(verified ? "true" : "false");
    Serial.print(",\"verify_channel\":");
    Serial.print(verifiedChannel);
    Serial.println("}");
  }

  if (verified) {
    Serial.println("{\"event\":\"result\",\"status\":\"SUCCESS\",\"reason\":\"verified\"}");
  } else {
    Serial.println("{\"event\":\"result\",\"status\":\"FAIL\",\"reason\":\"verify_failed\"}");
  }

  setE22Mode(false, false);
  waitAux(200);
  setLoRaUartRoute(UART_ROUTE_CANDIDATES[0]);
  beginLoRaSerial(LORA_UART_BAUD, SERIAL_8N1);
  activeLoRaBaud = LORA_UART_BAUD;
}

bool sendLoRaPayload(const uint8_t *payload, uint16_t len) {
  size_t written = LoRaSerial.write(payload, len);
  LoRaSerial.flush();

  if (written != len) {
    setError("LoRa TX short write");
    return false;
  }

  state.txCount++;
  statusDirty = true;
  digitalWrite(LED_PIN, HIGH);
  delay(20);
  digitalWrite(LED_PIN, LOW);
  return true;
}

uint16_t crc16Ccitt(const uint8_t *data, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= (uint16_t)data[i] << 8;
    for (uint8_t bit = 0; bit < 8; bit++) {
      if (crc & 0x8000) {
        crc = (uint16_t)((crc << 1) ^ 0x1021);
      } else {
        crc <<= 1;
      }
    }
  }
  return crc;
}

void maybeSendBeacon() {
#if ENABLE_BEACON_TEST_MODE
  if (!state.beaconEnabled) {
    return;
  }

  uint32_t now = millis();
  if ((now - state.lastBeaconMs) < BEACON_INTERVAL_MS) {
    return;
  }
  state.lastBeaconMs = now;

  uint32_t freqHz = (uint32_t)(state.frequencyMHz * 1000000.0f);
  if (freqHz == 0) {
    freqHz = E22_BASE_HZ + ((uint32_t)state.channel * E22_CHANNEL_STEP_HZ);
  }
  char base[96];
  const uint32_t beaconSeq = state.beaconSeq % 100;
  snprintf(
      base,
      sizeof(base),
      "RPIB1|seq=%lu|mode=BEACON|chan=%u|hz=%lu",
      (unsigned long)beaconSeq,
      state.channel,
      (unsigned long)freqHz
  );
  uint16_t crc = crc16Ccitt((const uint8_t *)base, strlen(base));

  char msg[112];
  snprintf(msg, sizeof(msg), "%s|crc=%04X", base, crc);
  state.beaconSeq = (state.beaconSeq + 1) % 100;
  Serial.print("#BEACON ");
  Serial.println(msg);

  bool ok = false;
  if (state.e22Configured) {
    ok = sendLoRaPayload((const uint8_t *)msg, strlen(msg));
  } else {
#if ALLOW_UNVERIFIED_BEACON_TX
    // Deterministic fallback path for interop testing:
    // do not baud-sweep, which can generate unintelligible RF payloads.
    beginLoRaSerial(LORA_UART_BAUD, SERIAL_8N1);
    activeLoRaBaud = LORA_UART_BAUD;
    delay(20);
    ok = sendLoRaPayload((const uint8_t *)msg, strlen(msg));
#else
    logLine("W", "Beacon blocked: E22 NO");
    Serial.println("#BEACON_TX_BLOCKED reason=E22_NOT_CONFIGURED");
    return;
#endif
  }

  if (ok) {
    char logMsg[36];
    snprintf(logMsg, sizeof(logMsg), "Beacon TX #%lu b%lu", (unsigned long)state.txCount, (unsigned long)activeLoRaBaud);
    logLine("I", logMsg);
    Serial.print("#BEACON_TX_OK len=");
    Serial.print(strlen(msg));
    Serial.print(" channel=");
    Serial.print(state.channel);
    Serial.print(" freqMHz=");
    Serial.println(state.frequencyMHz, 3);
  } else {
    sendStatus(STATUS_TX_FAILED);
    Serial.println("#BEACON_TX_FAIL");
  }
#endif
}

void flushLoraRxIfIdle() {
  if (loraRxLen == 0) {
    return;
  }

  uint32_t now = millis();
  if ((now - state.lastLoraRxByteMs) < RX_IDLE_FLUSH_MS) {
    return;
  }

  if (state.bridgeActive) {
    // E22 transparent mode does not expose packet metrics here; set placeholders.
    sendRxPacketToHost(loraRxBuffer, loraRxLen, 0, 0);
  }

  state.rxCount++;
  statusDirty = true;
  char logMsg[36];
  snprintf(logMsg, sizeof(logMsg), "LoRa RX %u bytes", (unsigned)loraRxLen);
  logLine("I", logMsg);

  loraRxLen = 0;
}

void readLoRaIncoming() {
  while (LoRaSerial.available()) {
    int b = LoRaSerial.read();
    if (b < 0) {
      break;
    }

    if (loraRxLen < sizeof(loraRxBuffer)) {
      loraRxBuffer[loraRxLen++] = (uint8_t)b;
      state.lastLoraRxByteMs = millis();
    } else {
      setError("LoRa RX overflow");
      loraRxLen = 0;
      break;
    }
  }

  flushLoraRxIfIdle();
}

void handleHostTxPacket() {
  if (hostExpectedLen > MAX_PACKET_SIZE) {
    sendStatus(STATUS_PACKET_TOO_LONG);
    setError("Host TX too long");
    hostState = WAIT_CMD;
    return;
  }

  bool ok = sendLoRaPayload(hostTxBuffer, hostExpectedLen);
  sendStatus(ok ? STATUS_OK : STATUS_TX_FAILED);
  state.cfgCount++;
  statusDirty = true;

  if (ok) {
    char logMsg[36];
    snprintf(logMsg, sizeof(logMsg), "Host TX %u bytes", (unsigned)hostExpectedLen);
    logLine("I", logMsg);
  }

  hostState = WAIT_CMD;
  hostExpectedLen = 0;
  hostReadLen = 0;
}

void handleHostByte(uint8_t b) {
  switch (hostState) {
    case WAIT_CMD:
      if (b == CMD_PING) {
        state.bridgeActive = true;
        state.beaconEnabled = false;
        state.pingCount++;
        statusDirty = true;
        sendPong(STATUS_OK);
        logLine("I", "PING -> PONG");
      } else if (b == CMD_CONFIG) {
        sendStatus(STATUS_OK);
        state.cfgCount++;
        statusDirty = true;
        logLine("I", "Host CONFIG");
      } else if (b == CMD_TX) {
        state.bridgeActive = true;
        state.beaconEnabled = false;
        hostState = WAIT_LEN_LO;
      }
      break;

    case WAIT_LEN_LO:
      hostExpectedLen = b;
      hostState = WAIT_LEN_HI;
      break;

    case WAIT_LEN_HI:
      hostExpectedLen |= (uint16_t)b << 8;
      hostReadLen = 0;
      if (hostExpectedLen == 0 || hostExpectedLen > MAX_PACKET_SIZE) {
        sendStatus(STATUS_PACKET_TOO_LONG);
        setError("Invalid host len");
        hostState = WAIT_CMD;
      } else {
        hostState = WAIT_PAYLOAD;
      }
      break;

    case WAIT_PAYLOAD:
      hostTxBuffer[hostReadLen++] = b;
      if (hostReadLen >= hostExpectedLen) {
        handleHostTxPacket();
      }
      break;
  }
}

void handleHostSerial() {
  while (Serial.available()) {
    state.usbSeen = true;
    int v = Serial.read();
    if (v < 0) {
      break;
    }
    handleHostByte((uint8_t)v);
  }
}

void initDisplay() {
  SPI1.setSCK(TFT_SCK);
  SPI1.setTX(TFT_MOSI);
  SPI1.begin();

  pinMode(TFT_BL, OUTPUT);
  digitalWrite(TFT_BL, HIGH);

  tft.init(135, 240);
  tft.setRotation(1);
  tft.fillScreen(ST77XX_BLACK);
}

void initPins() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  pinMode(LORA_M0, OUTPUT);
  pinMode(LORA_M1, OUTPUT);
  // AUX can appear stuck LOW if the line is floating; keep an internal pull-up
  // so mode-ready polling is usable across board revisions.
  pinMode(LORA_AUX, INPUT_PULLUP);

  setE22Mode(false, false);
}

void setup() {
  initPins();
  initDisplay();

  for (uint8_t i = 0; i < LOG_LINES; i++) {
    logLines[i][0] = '\0';
  }

  Serial.begin(USB_BAUD);
  LoRaSerial.setTX(LORA_TX);
  LoRaSerial.setRX(LORA_RX);
  beginLoRaSerial(LORA_UART_BAUD, SERIAL_8N1);

  logLine("I", "Booting v2-debug");
  logLine("I", "Transparent mode fix");

  // Debug: Log initial pin states
  Serial.print("#DBG: Pin states - M0=");
  Serial.print(digitalRead(LORA_M0));
  Serial.print(" M1=");
  Serial.print(digitalRead(LORA_M1));
  Serial.print(" AUX=");
  Serial.println(digitalRead(LORA_AUX));

#if TRUTH_PROBE_MODE
  logLine("I", "Truth probe mode");
  drawStatus();
  runTruthProbeMode();
  return;
#endif

  // Try to configure E22, but don't fail if it doesn't respond.
  // The official RangePi code doesn't configure at all - it just uses transparent mode.
  // E22 retains settings from factory or previous configuration.
  bool configured = configureE22Channel18();
  if (configured) {
    logLine("I", "E22 configured OK");
  } else {
    // Configuration failed, but continue anyway in transparent mode
    // like the official RangePi firmware does
    logLine("W", "E22 cfg fail, using defaults");
    state.e22Configured = false;

    // Ensure we're in transparent mode (M0=0, M1=0) - same as official code
    setE22Mode(false, false);
    beginLoRaSerial(LORA_UART_BAUD, SERIAL_8N1);
    activeLoRaBaud = LORA_UART_BAUD;  // Fix display to show correct baud
    delay(100);

    // Keep e22Configured=false because channel/frequency could not be verified.
    logLine("I", "Using transparent mode");
  }

  Serial.print("#E22_CONFIGURED=");
  Serial.print(state.e22Configured ? "YES" : "NO");
  Serial.print(" channel=");
  Serial.print(state.channel);
  Serial.print(" freqMHz=");
  Serial.println(state.frequencyMHz, 3);

#if ENABLE_BEACON_TEST_MODE
  logLine("I", "Beacon mode enabled");
#else
  logLine("I", "Bridge mode only");
#endif

  drawStatus();
}

void loop() {
#if TRUTH_PROBE_MODE
  static uint32_t lastBeat = 0;
  uint32_t nowMs = millis();
  if (nowMs - lastBeat > 2000) {
    Serial.println("{\"event\":\"idle\",\"mode\":\"truth_probe\"}");
    lastBeat = nowMs;
  }
  return;
#endif

  handleHostSerial();
  readLoRaIncoming();
  maybeSendBeacon();

  uint32_t now = millis();
  if (statusDirty && (now - state.lastStatusRefreshMs) > 750) {
    state.lastStatusRefreshMs = now;
    drawStatus();
  }

  // Debug: Periodic E22 UART and pin status
  static uint32_t lastUartDebug = 0;
  if ((now - lastUartDebug) > 2000) {
    Serial.print("#DBG: LoRaSerial.available()=");
    Serial.print(LoRaSerial.available());
    Serial.print(" AUX=");
    Serial.print(digitalRead(LORA_AUX));
    Serial.print(" M0=");
    Serial.print(digitalRead(LORA_M0));
    Serial.print(" M1=");
    Serial.println(digitalRead(LORA_M1));
    lastUartDebug = now;
  }
}
