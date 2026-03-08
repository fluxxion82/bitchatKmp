"""
One-time E22 truth probe + configuration recovery script (MicroPython).

Goals:
- Discover a valid config-mode tuple (M1/M0 + baud + read-length).
- Read full config frame shape empirically.
- Write a full persistent config frame (C0) for CH18/868.125, transparent mode,
  conservative air rate profile, and crypto key 0x0000.
- Verify readback with strict PASS/FAIL checks.

Output:
- JSON-style single-line logs for each probe/write/verify step.
- Final RESULT line with SUCCESS or FAIL.
"""

from machine import Pin, UART
import time

PIN_M0 = 2
PIN_M1 = 3
PIN_AUX = 4
UART_ID = 0

CMD_READ = 0xC1
CMD_WRITE = 0xC0
CMD_WRITE_VOLATILE = 0xC2

CHANNEL = 18
READ_LENGTHS = (8, 9, 10)
BAUDS = (9600, 19200, 38400, 57600, 115200)
MODES = (
    (0, 0),  # normal
    (0, 1),  # wake-up
    (1, 0),  # config
    (1, 1),  # sleep/config variant
)
MAX_VERIFY_ATTEMPTS = 3

m0_pin = Pin(PIN_M0, Pin.OUT)
m1_pin = Pin(PIN_M1, Pin.OUT)
aux_pin = Pin(PIN_AUX, Pin.IN)


def jlog(event, **kwargs):
    parts = ['"event":"%s"' % event]
    for k, v in kwargs.items():
        if isinstance(v, str):
            parts.append('"%s":"%s"' % (k, v.replace('"', '\\"')))
        elif isinstance(v, bool):
            parts.append('"%s":%s' % (k, "true" if v else "false"))
        else:
            parts.append('"%s":%s' % (k, v))
    print("{" + ",".join(parts) + "}")


def hex_bytes(data):
    if not data:
        return ""
    return "".join("%02X" % b for b in data)


def set_mode(m1, m0):
    m0_pin.value(m0)
    m1_pin.value(m1)
    time.sleep_ms(40)


def wait_aux(timeout_ms=1200):
    start = time.ticks_ms()
    while not aux_pin.value():
        if time.ticks_diff(time.ticks_ms(), start) > timeout_ms:
            return False, time.ticks_diff(time.ticks_ms(), start)
        time.sleep_ms(2)
    return True, time.ticks_diff(time.ticks_ms(), start)


def read_frame(uart, nbytes):
    cmd = bytes([CMD_READ, 0x00, 0x00, nbytes])
    while uart.any():
        uart.read()
    uart.write(cmd)
    time.sleep_ms(220)
    return uart.read(64) or b""


def response_score(resp):
    if not resp:
        return -1
    score = 0
    if len(resp) >= 4:
        score += 10
    if resp[0] == CMD_READ:
        score += 35
    if len(resp) >= 7:
        score += 30
    if any(b not in (0x00, 0xFF, 0xFE, 0xF8, 0xE0) for b in resp):
        score += 15
    ch = extract_channel(resp)
    if ch is not None and 0 <= ch <= 83:
        score += 20
    return score


def extract_channel(resp):
    if len(resp) > 6 and 0 <= resp[6] <= 83:
        return resp[6]
    if len(resp) > 5 and 0 <= resp[5] <= 83:
        return resp[5]
    return None


def frame_data(resp):
    if len(resp) >= 4 and resp[0] in (CMD_READ, CMD_WRITE, CMD_WRITE_VOLATILE):
        return bytearray(resp[3:])
    return bytearray()


def build_target_data(current):
    target = bytearray(current)
    if len(target) >= 1:
        target[0] = 0x62  # UART 9600 8N1 + air rate baseline
    if len(target) >= 2:
        target[1] = 0x00  # packet/power baseline
    if len(target) >= 3:
        target[2] = CHANNEL
    if len(target) >= 4:
        target[3] = 0x03  # transparent mode / RSSI byte baseline
    if len(target) >= 2:
        # Force CRYPT key = 0x0000 (typically final two config bytes).
        target[-2] = 0x00
        target[-1] = 0x00
    return target


def write_full_frame(uart, target_data):
    frame = bytes([CMD_WRITE, 0x00, 0x00]) + bytes(target_data)
    while uart.any():
        uart.read()
    uart.write(frame)
    time.sleep_ms(240)
    ack = uart.read(64) or b""
    return frame, ack


def verify_readback(resp, expected_data):
    if not resp or resp[0] != CMD_READ:
        return False, "bad_prefix_or_empty"
    data = frame_data(resp)
    if len(data) < len(expected_data):
        return False, "short_readback"

    # Validate changed critical fields: REG0/1/2/3 and trailing CRYPT bytes.
    indices = []
    for idx in (0, 1, 2, 3):
        if idx < len(expected_data):
            indices.append(idx)
    if len(expected_data) >= 2:
        indices.extend([len(expected_data) - 2, len(expected_data) - 1])

    for idx in indices:
        if data[idx] != expected_data[idx]:
            return False, "mismatch_idx_%d" % idx

    ch = extract_channel(resp)
    if ch != CHANNEL:
        return False, "channel_mismatch"

    return True, "ok"


def discover_tuple():
    best = None
    uart = UART(UART_ID, baudrate=9600, bits=8, parity=None, stop=1)
    for m1, m0 in MODES:
        set_mode(m1, m0)
        aux_ok, aux_wait_ms = wait_aux()
        for baud in BAUDS:
            uart.init(baudrate=baud, bits=8, parity=None, stop=1)
            time.sleep_ms(30)
            for read_len in READ_LENGTHS:
                resp = read_frame(uart, read_len)
                score = response_score(resp)
                ch = extract_channel(resp)
                jlog(
                    "probe",
                    m1=m1,
                    m0=m0,
                    baud=baud,
                    read_len=read_len,
                    aux_ready=aux_ok,
                    aux_wait_ms=aux_wait_ms,
                    resp_len=len(resp),
                    resp_hex=hex_bytes(resp),
                    score=score,
                    channel=(-1 if ch is None else ch),
                )
                candidate = {
                    "m1": m1,
                    "m0": m0,
                    "baud": baud,
                    "read_len": read_len,
                    "resp": resp,
                    "score": score,
                }
                if best is None or candidate["score"] > best["score"]:
                    best = candidate
    return best


def configure_and_verify(best):
    uart = UART(UART_ID, baudrate=best["baud"], bits=8, parity=None, stop=1)
    set_mode(best["m1"], best["m0"])
    aux_ok, aux_wait_ms = wait_aux()
    jlog("selected_tuple", m1=best["m1"], m0=best["m0"], baud=best["baud"], read_len=best["read_len"], score=best["score"], aux_ready=aux_ok, aux_wait_ms=aux_wait_ms)

    base_resp = read_frame(uart, best["read_len"])
    base_data = frame_data(base_resp)
    jlog("baseline_read", resp_len=len(base_resp), resp_hex=hex_bytes(base_resp), data_len=len(base_data))
    if len(base_data) < 4:
        return False, "baseline_data_too_short"

    target_data = build_target_data(base_data)
    jlog("target_profile", target_len=len(target_data), target_hex=hex_bytes(target_data))

    for attempt in range(1, MAX_VERIFY_ATTEMPTS + 1):
        frame, ack = write_full_frame(uart, target_data)
        rb = read_frame(uart, best["read_len"])
        ok, reason = verify_readback(rb, target_data)
        jlog(
            "write_verify",
            attempt=attempt,
            write_len=len(frame),
            write_hex=hex_bytes(frame),
            ack_len=len(ack),
            ack_hex=hex_bytes(ack),
            readback_len=len(rb),
            readback_hex=hex_bytes(rb),
            verify_ok=ok,
            verify_reason=reason,
        )
        if ok:
            set_mode(0, 0)
            wait_aux(300)
            return True, "verified"

    set_mode(0, 0)
    wait_aux(300)
    return False, "verify_failed"


def main():
    jlog("start", channel=CHANNEL)
    best = discover_tuple()
    if best is None or best["score"] < 30:
        jlog("result", status="FAIL", reason="no_valid_tuple")
        return

    ok, reason = configure_and_verify(best)
    if ok:
        jlog("result", status="SUCCESS", reason=reason)
    else:
        jlog("result", status="FAIL", reason=reason)


if __name__ == "__main__":
    main()
