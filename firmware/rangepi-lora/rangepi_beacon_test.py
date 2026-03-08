"""
RangePi beacon test sender for deterministic interop bring-up.

Behavior:
- Configures E22 channel 18 (868.125 MHz) using config mode.
- Switches back to normal mode.
- Emits one beacon packet every 5 seconds:
  RPIB1|seq=<uint>|mode=BEACON|chan=18|hz=868125000|crc=<hex4>
- Prints ASCII payload and hex bytes for each TX.
"""

from machine import Pin, UART
import time

PIN_M0 = 2
PIN_M1 = 3
PIN_AUX = 4
UART_ID = 0
UART_CONFIG_BAUD = 9600
UART_RUN_BAUD = 9600

CHANNEL = 18
FREQUENCY_HZ = 868_125_000
INTERVAL_SECONDS = 5

CMD_READ = 0xC1
CMD_WRITE = 0xC0

# E22 register profile (same baseline used in existing config helper)
REG0 = 0x62  # UART 9600 8N1, air rate 2.4kbps
REG1 = 0x00  # 200B packet, RSSI off, max power profile
REG2 = CHANNEL
REG3 = 0x03  # RSSI enable, fixed off, WOR 500ms


m0_pin = Pin(PIN_M0, Pin.OUT)
m1_pin = Pin(PIN_M1, Pin.OUT)
aux_pin = Pin(PIN_AUX, Pin.IN)


def set_mode(m1: int, m0: int):
    m0_pin.value(m0)
    m1_pin.value(m1)
    time.sleep_ms(40)


def wait_aux(timeout_ms=1500) -> bool:
    start = time.ticks_ms()
    while not aux_pin.value():
        if time.ticks_diff(time.ticks_ms(), start) > timeout_ms:
            return False
        time.sleep_ms(2)
    return True


def read_config(uart) -> bytes:
    uart.write(bytes([CMD_READ, 0x00, 0x00, 0x08]))
    time.sleep_ms(200)
    return uart.read(16) or b""


def write_config(uart) -> bytes:
    frame = bytes([CMD_WRITE, 0x00, 0x00, REG0, REG1, REG2, REG3])
    uart.write(frame)
    time.sleep_ms(200)
    return uart.read(16) or b""


def crc16_ccitt(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= (b & 0xFF) << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def format_beacon(seq: int) -> str:
    base = "RPIB1|seq={}|mode=BEACON|chan={}|hz={}".format(seq, CHANNEL, FREQUENCY_HZ)
    crc = crc16_ccitt(base.encode("utf-8"))
    return "{}|crc={:04X}".format(base, crc)


def decode_channel(resp: bytes):
    if len(resp) >= 7 and resp[0] == CMD_READ:
        ch = resp[5]
        return ch, 850.125 + ch
    return None, None


def configure_radio(uart):
    print("Configuring E22 for channel", CHANNEL, "(~868.125 MHz)")
    set_mode(1, 0)  # M1=1, M0=0: config mode

    if not wait_aux():
        print("AUX timeout entering config mode")
    before = read_config(uart)
    print("Read before:", before)
    ch, freq = decode_channel(before)
    if ch is not None:
        print("Current channel:", ch, "≈ %.3f MHz" % freq)

    wr = write_config(uart)
    print("Write resp:", wr)

    after = read_config(uart)
    print("Read after:", after)
    ch2, freq2 = decode_channel(after)
    if ch2 is not None:
        print("New channel:", ch2, "≈ %.3f MHz" % freq2)

    set_mode(0, 0)  # normal mode
    time.sleep_ms(100)
    print("Config complete; switched to normal mode.")


def main():
    uart = UART(UART_ID, baudrate=UART_CONFIG_BAUD, bits=8, parity=None, stop=1)
    time.sleep_ms(50)
    configure_radio(uart)

    # Keep UART in same baud for transparent sends.
    uart.init(baudrate=UART_RUN_BAUD, bits=8, parity=None, stop=1)

    seq = 0
    print("Starting deterministic beacon TX every", INTERVAL_SECONDS, "seconds")
    while True:
        payload = format_beacon(seq).encode("utf-8")
        uart.write(payload)
        hex_payload = " ".join("{:02x}".format(b) for b in payload)
        print("TX seq={} len={} text={}".format(seq, len(payload), payload.decode("utf-8")))
        print("TX hex={}".format(hex_payload))
        seq = (seq + 1) & 0xFFFFFFFF
        time.sleep(INTERVAL_SECONDS)


if __name__ == "__main__":
    main()
