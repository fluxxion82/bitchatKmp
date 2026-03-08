#!/usr/bin/env python3
"""
CardKb I2C to Linux uinput driver.
Creates a virtual keyboard from M5Stack CardKB I2C input.

Usage:
    sudo python3 cardkb-driver.py

Requirements:
    pip3 install python-uinput smbus2
    modprobe uinput
"""

import smbus2
import uinput
import time
import signal
import sys
import argparse
import logging

CARDKB_ADDRESS = 0x5F
DEFAULT_I2C_BUS = 2  # Orange Pi Zero 3: i2c3-ph overlay creates /dev/i2c-2
POLL_INTERVAL = 0.01  # 10ms polling (100Hz)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# CardKb key code to Linux input event mapping
# CardKb sends ASCII codes for most keys
KEY_MAP = {
    0x08: uinput.KEY_BACKSPACE,
    0x09: uinput.KEY_TAB,
    0x0D: uinput.KEY_ENTER,
    0x1B: uinput.KEY_ESC,
    0x20: uinput.KEY_SPACE,
    # Arrow keys (CardKb uses custom codes)
    0xB4: uinput.KEY_LEFT,
    0xB5: uinput.KEY_UP,
    0xB6: uinput.KEY_DOWN,
    0xB7: uinput.KEY_RIGHT,
    # Delete
    0x7F: uinput.KEY_DELETE,
}

# ASCII letter/number to key mappings
ASCII_TO_KEY = {}
for i, c in enumerate('abcdefghijklmnopqrstuvwxyz'):
    ASCII_TO_KEY[ord(c)] = getattr(uinput, f'KEY_{c.upper()}')
for i, c in enumerate('1234567890'):
    key_name = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'][i]
    ASCII_TO_KEY[ord(c)] = getattr(uinput, f'KEY_{key_name}')

# Symbols - tuple of (key, needs_shift)
SYMBOL_MAP = {
    ord('`'): (uinput.KEY_GRAVE, False),
    ord('~'): (uinput.KEY_GRAVE, True),
    ord('-'): (uinput.KEY_MINUS, False),
    ord('_'): (uinput.KEY_MINUS, True),
    ord('='): (uinput.KEY_EQUAL, False),
    ord('+'): (uinput.KEY_EQUAL, True),
    ord('['): (uinput.KEY_LEFTBRACE, False),
    ord('{'): (uinput.KEY_LEFTBRACE, True),
    ord(']'): (uinput.KEY_RIGHTBRACE, False),
    ord('}'): (uinput.KEY_RIGHTBRACE, True),
    ord('\\'): (uinput.KEY_BACKSLASH, False),
    ord('|'): (uinput.KEY_BACKSLASH, True),
    ord(';'): (uinput.KEY_SEMICOLON, False),
    ord(':'): (uinput.KEY_SEMICOLON, True),
    ord("'"): (uinput.KEY_APOSTROPHE, False),
    ord('"'): (uinput.KEY_APOSTROPHE, True),
    ord(','): (uinput.KEY_COMMA, False),
    ord('<'): (uinput.KEY_COMMA, True),
    ord('.'): (uinput.KEY_DOT, False),
    ord('>'): (uinput.KEY_DOT, True),
    ord('/'): (uinput.KEY_SLASH, False),
    ord('?'): (uinput.KEY_SLASH, True),
    ord('!'): (uinput.KEY_1, True),
    ord('@'): (uinput.KEY_2, True),
    ord('#'): (uinput.KEY_3, True),
    ord('$'): (uinput.KEY_4, True),
    ord('%'): (uinput.KEY_5, True),
    ord('^'): (uinput.KEY_6, True),
    ord('&'): (uinput.KEY_7, True),
    ord('*'): (uinput.KEY_8, True),
    ord('('): (uinput.KEY_9, True),
    ord(')'): (uinput.KEY_0, True),
}


def get_key_event(code):
    """Convert CardKb code to (uinput_key, needs_shift) tuple."""
    # Direct mapping (special keys)
    if code in KEY_MAP:
        return (KEY_MAP[code], False)

    # Lowercase letters
    if ord('a') <= code <= ord('z'):
        return (ASCII_TO_KEY[code], False)

    # Uppercase letters (need shift)
    if ord('A') <= code <= ord('Z'):
        return (ASCII_TO_KEY[code + 32], True)  # +32 converts to lowercase ASCII

    # Numbers
    if ord('0') <= code <= ord('9'):
        return (ASCII_TO_KEY[code], False)

    # Symbols
    if code in SYMBOL_MAP:
        return SYMBOL_MAP[code]

    return None


def create_uinput_device():
    """Create the virtual keyboard device with all supported keys."""
    events = [
        uinput.KEY_ESC, uinput.KEY_TAB, uinput.KEY_ENTER,
        uinput.KEY_BACKSPACE, uinput.KEY_SPACE, uinput.KEY_DELETE,
        uinput.KEY_LEFT, uinput.KEY_RIGHT, uinput.KEY_UP, uinput.KEY_DOWN,
        uinput.KEY_LEFTSHIFT, uinput.KEY_RIGHTSHIFT,
        uinput.KEY_LEFTCTRL, uinput.KEY_RIGHTCTRL,
        uinput.KEY_LEFTALT, uinput.KEY_RIGHTALT,
    ]

    # Add letter keys
    for c in 'ABCDEFGHIJKLMNOPQRSTUVWXYZ':
        events.append(getattr(uinput, f'KEY_{c}'))

    # Add number keys
    for c in '1234567890':
        events.append(getattr(uinput, f'KEY_{c}'))

    # Add symbol keys
    events.extend([
        uinput.KEY_GRAVE, uinput.KEY_MINUS, uinput.KEY_EQUAL,
        uinput.KEY_LEFTBRACE, uinput.KEY_RIGHTBRACE, uinput.KEY_BACKSLASH,
        uinput.KEY_SEMICOLON, uinput.KEY_APOSTROPHE,
        uinput.KEY_COMMA, uinput.KEY_DOT, uinput.KEY_SLASH,
    ])

    return uinput.Device(events, name="CardKb-I2C")


def main():
    parser = argparse.ArgumentParser(description='CardKb I2C to uinput driver')
    parser.add_argument('-b', '--bus', type=int, default=DEFAULT_I2C_BUS,
                        help=f'I2C bus number (default: {DEFAULT_I2C_BUS})')
    parser.add_argument('-a', '--address', type=lambda x: int(x, 0),
                        default=CARDKB_ADDRESS,
                        help=f'CardKb I2C address (default: 0x{CARDKB_ADDRESS:02X})')
    parser.add_argument('-v', '--verbose', action='store_true',
                        help='Enable verbose output')
    parser.add_argument('-q', '--quiet', action='store_true',
                        help='Suppress non-error output')
    args = parser.parse_args()

    if args.verbose:
        logger.setLevel(logging.DEBUG)
    elif args.quiet:
        logger.setLevel(logging.ERROR)

    logger.info("CardKb uinput driver starting...")

    # Open I2C bus
    try:
        bus = smbus2.SMBus(args.bus)
        logger.info(f"Opened /dev/i2c-{args.bus}")
    except FileNotFoundError:
        logger.error(f"I2C bus /dev/i2c-{args.bus} not found")
        logger.error("Make sure I2C is enabled (i2c3-ph overlay) and reboot")
        sys.exit(1)
    except PermissionError:
        logger.error(f"Permission denied for /dev/i2c-{args.bus}")
        logger.error("Run with sudo or add user to i2c group")
        sys.exit(1)
    except Exception as e:
        logger.error(f"Failed to open I2C bus: {e}")
        sys.exit(1)

    # Verify CardKb is present
    try:
        bus.read_byte(args.address)
        logger.info(f"CardKb found at address 0x{args.address:02X}")
    except Exception as e:
        logger.error(f"CardKb not found at 0x{args.address:02X}: {e}")
        logger.error("Check wiring: SDA->Pin3, SCL->Pin5, GND->GND, VCC->3.3V")
        logger.error("Run 'sudo i2cdetect -y %d' to scan the bus", args.bus)
        bus.close()
        sys.exit(1)

    # Create virtual keyboard
    try:
        device = create_uinput_device()
        logger.info("Virtual keyboard 'CardKb-I2C' created")
    except Exception as e:
        logger.error(f"Failed to create uinput device: {e}")
        logger.error("Make sure uinput module is loaded: modprobe uinput")
        bus.close()
        sys.exit(1)

    # Handle graceful shutdown
    running = True

    def signal_handler(sig, frame):
        nonlocal running
        running = False
        logger.info("Received signal, shutting down...")

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    logger.info("Driver running. Press Ctrl+C to exit.")

    # Main polling loop
    error_count = 0
    max_errors = 10

    while running:
        try:
            code = bus.read_byte(args.address)
            error_count = 0  # Reset on successful read

            if code != 0:
                event = get_key_event(code)
                if event:
                    key, needs_shift = event
                    if needs_shift:
                        device.emit(uinput.KEY_LEFTSHIFT, 1)
                    device.emit_click(key)
                    if needs_shift:
                        device.emit(uinput.KEY_LEFTSHIFT, 0)
                    logger.debug(f"Key: 0x{code:02X} -> {key}")
                else:
                    logger.debug(f"Unknown key code: 0x{code:02X}")

        except OSError as e:
            error_count += 1
            logger.warning(f"I2C read error ({error_count}/{max_errors}): {e}")
            if error_count >= max_errors:
                logger.error("Too many consecutive I2C errors, exiting")
                running = False
            time.sleep(0.1)  # Back off on errors

        except Exception as e:
            logger.error(f"Unexpected error: {e}")
            error_count += 1
            if error_count >= max_errors:
                running = False

        time.sleep(POLL_INTERVAL)

    # Cleanup
    bus.close()
    logger.info("Driver stopped")


if __name__ == '__main__':
    main()
