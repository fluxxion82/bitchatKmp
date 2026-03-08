"""
RangePi E22 configuration helper (MicroPython).

Sets the E22-900T22S to channel 65 (~915.125 MHz), saving to flash.
Uses onboard M0/M1 lines driven by the RP2040; no external jumpers needed.

Usage
-----
1) Hold BOOTSEL, plug RangePi via USB-C, release to mount RPI-RP2 drive.
2) Copy this file to the drive as `main.py`.
3) Eject, press RESET (or replug). Script runs once, prints over USB serial
   (115200 8N1). LCD (if present) is untouched.

Channel math: Freq = 850.125 MHz + CH*1 MHz (E22-900 series).
Channel 65 -> 915.125 MHz (US ISM).
"""

from machine import Pin, UART
import time

# Pin mapping on RangePi
PIN_M0 = 2
PIN_M1 = 3
PIN_AUX = 4
UART_ID = 0
UART_BAUD = 9600  # E22 config-mode baud

CHANNEL = 65  # target channel (915.125 MHz)

CMD_READ = 0xC1
CMD_WRITE = 0xC0

# Register bytes
REG0 = 0x62  # 9600 8N1, air rate 2.4 kbps
REG1 = 0x00  # 200B pkt, RSSI off, 22 dBm
REG2 = CHANNEL
REG3 = 0x03  # RSSI enable, fixed off, WOR 500 ms


def set_mode(m1: int, m0: int):
    m0_pin.value(m0)
    m1_pin.value(m1)
    time.sleep_ms(20)


def wait_aux(timeout_ms=1000) -> bool:
    start = time.ticks_ms()
    while not aux_pin.value():
        if time.ticks_diff(time.ticks_ms(), start) > timeout_ms:
            return False
        time.sleep_ms(2)
    return True


def read_config(uart) -> bytes:
    uart.write(bytes([CMD_READ, 0x00, 0x00, 0x08]))
    uart.flush()
    time.sleep_ms(200)
    return uart.read(16) or b""


def write_config(uart):
    frame = bytes([CMD_WRITE, 0x00, 0x00, REG0, REG1, REG2, REG3])
    uart.write(frame)
    uart.flush()
    time.sleep_ms(200)
    return uart.read(16) or b""


def decode_channel(resp: bytes):
    if len(resp) >= 7 and resp[0] == CMD_READ:
        ch = resp[5]
        return ch, 850.125 + ch
    return None, None


def main():
    print("RangePi: set E22 channel to", CHANNEL, "(~915.125 MHz)")

    m0_pin.init(Pin.OUT, value=0)
    m1_pin.init(Pin.OUT, value=0)
    aux_pin.init(Pin.IN)

    uart = UART(UART_ID, baudrate=UART_BAUD, bits=8, parity=None, stop=1)
    time.sleep_ms(50)

    # Enter config mode (M1=1, M0=0)
    set_mode(1, 0)
    if not wait_aux():
        print("AUX timeout entering config mode")
    else:
        print("Entered config mode")

    before = read_config(uart)
    print("Read before:", before)
    ch, freq = decode_channel(before)
    if ch is not None:
        print("Current channel:", ch, "≈ %.3f MHz" % freq)

    resp = write_config(uart)
    print("Write resp:", resp)

    after = read_config(uart)
    print("Read after:", after)
    ch2, freq2 = decode_channel(after)
    if ch2 is not None:
        print("New channel:", ch2, "≈ %.3f MHz" % freq2)

    # Back to normal (transparent) mode
    set_mode(0, 0)
    print("Done; back to normal mode.")


m0_pin = Pin(PIN_M0, Pin.OUT)
m1_pin = Pin(PIN_M1, Pin.OUT)
aux_pin = Pin(PIN_AUX, Pin.IN)


if __name__ == "__main__":
    main()
