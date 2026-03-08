#!/usr/bin/env python3
"""
Test E22 configuration mode via USB-TTL adapter.
Usage: python3 test_e22_config.py /dev/tty.usbserial-210
       python3 test_e22_config.py /dev/tty.usbserial-210 --scan (try multiple baud rates)
"""

import serial
import sys
import time

def test_e22(port, baud):
    """Test E22 at specific baud rate, return True if response received."""
    try:
        ser = serial.Serial(port, baud, timeout=1)
        time.sleep(0.1)

        ser.reset_input_buffer()
        ser.reset_output_buffer()

        # E22 config read command: C1 00 00 08 (read 8 bytes from addr 0)
        cmd = bytes([0xC1, 0x00, 0x00, 0x08])
        ser.write(cmd)
        ser.flush()

        time.sleep(0.3)
        response = ser.read(20)
        ser.close()

        if response:
            print(f"  [{baud}] Response ({len(response)} bytes): {response.hex(' ')}")
            if len(response) >= 7 and response[0] == 0xC1:
                channel = response[6] if len(response) > 6 else 0
                freq = 850.125 + channel
                print(f"  [{baud}] Channel: {channel} = {freq:.3f} MHz")
            return True
        return False

    except serial.SerialException as e:
        print(f"  [{baud}] Error: {e}")
        return False

if len(sys.argv) < 2:
    print("Usage: python3 test_e22_config.py /dev/tty.usbserial-XXX")
    print("       python3 test_e22_config.py /dev/tty.usbserial-XXX --scan")
    sys.exit(1)

port = sys.argv[1]
scan_mode = len(sys.argv) > 2 and sys.argv[2] == "--scan"

if scan_mode:
    # Try common E22 baud rates
    baud_rates = [9600, 115200, 57600, 38400, 19200, 4800, 2400, 1200]
    print(f"Scanning {port} at multiple baud rates...")
    found = False
    for baud in baud_rates:
        print(f"Trying {baud} baud...")
        if test_e22(port, baud):
            print(f"\n*** SUCCESS at {baud} baud! ***")
            found = True
            break
    if not found:
        print("\nNo response at any baud rate.")
        print("\nChecklist:")
        print("  1. Is TX/RX swapped? (USB-TTL TX -> E22 RX, USB-TTL RX -> E22 TX)")
        print("  2. Is E22 powered? (RangePi connected to USB power?)")
        print("  3. Is M0/M1 HIGH? (RangePi running v3.7 firmware?)")
        print("  4. Are the GP0/GP1 jumpers removed?")
else:
    print(f"Opening {port} at 9600 baud...")
    print("(Use --scan to try multiple baud rates)")

    try:
        ser = serial.Serial(port, 9600, timeout=2)
        time.sleep(0.1)

        ser.reset_input_buffer()
        ser.reset_output_buffer()

        cmd = bytes([0xC1, 0x00, 0x00, 0x08])
        print(f"Sending: {cmd.hex(' ')}")
        ser.write(cmd)
        ser.flush()

        time.sleep(0.5)
        response = ser.read(20)

        if response:
            print(f"Response ({len(response)} bytes): {response.hex(' ')}")
            if len(response) >= 7 and response[0] == 0xC1:
                channel = response[6] if len(response) > 6 else 0
                freq = 850.125 + channel
                print(f"Channel: {channel} = {freq:.3f} MHz")
        else:
            print("No response from E22!")
            print("\nChecklist:")
            print("  1. Is TX/RX swapped? (USB-TTL TX -> E22 RX, USB-TTL RX -> E22 TX)")
            print("  2. Is E22 powered? (RangePi connected to USB power?)")
            print("  3. Is M0/M1 HIGH? (RangePi running v3.7 firmware?)")
            print("  4. Are the GP0/GP1 jumpers removed?")

        ser.close()

    except serial.SerialException as e:
        print(f"Error: {e}")
    except KeyboardInterrupt:
        print("\nCancelled")