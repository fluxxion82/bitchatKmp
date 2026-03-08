#!/usr/bin/env python3
"""
GPIO Monitor Script for Orange Pi Zero 3

Monitors the DIO0 pin (GPIO70/PC6) for edge events using gpiod.
This tests whether GPIO interrupts are working properly.

meshtasticd uses GPIO interrupts (edge detection) to get notified
when TX/RX operations complete. If interrupts don't work, it falls
back to polling and may have timing issues.

Usage:
  sudo systemctl stop meshtasticd  # Stop meshtasticd first!

  # Terminal 1: Run this monitor
  sudo python3 gpio_monitor.py

  # Terminal 2: Trigger a TX
  sudo python3 lora_test.py --skip-gpio --transmit

Expected output if interrupts work:
  Monitoring DIO0 (GPIO70)... Press Ctrl+C to stop
  DIO0 event: RISING_EDGE at 1234567890.123
  DIO0 event: FALLING_EDGE at 1234567890.234

If no events appear during TX, GPIO interrupt is not working.

Dependencies:
  sudo apt install python3-libgpiod
"""

import sys
import time

try:
    import gpiod
except ImportError:
    print("ERROR: python3-libgpiod not installed")
    print("Install with: sudo apt install python3-libgpiod")
    sys.exit(1)

# GPIO Configuration for Orange Pi Zero 3
# Pin 11 = PC6 = gpiochip1 line 70
GPIO_CHIP = "gpiochip1"
DIO0_LINE = 70  # PC6

# Alternative pins to try if DIO0_LINE doesn't work
ALTERNATIVE_LINES = {
    70: "Pin 11 (PC6) - current DIO0",
    71: "Pin 12 (PC7)",
    73: "Pin 7 (PC9) - current RESET",
    74: "Pin 13 (PC10)",
    75: "Pin 15 (PC11)",
    76: "Pin 16 (PC12)",
    72: "Pin 22 (PC8)",
}


def monitor_line(chip_name: str, line_num: int):
    """Monitor a single GPIO line for events."""
    print(f"Opening {chip_name} line {line_num}...")

    try:
        chip = gpiod.Chip(chip_name)
        line = chip.get_line(line_num)

        # Request the line for both rising and falling edge events
        line.request(
            consumer="gpio_monitor",
            type=gpiod.LINE_REQ_EV_BOTH_EDGES
        )

        print(f"Monitoring GPIO line {line_num}... Press Ctrl+C to stop")
        print(f"Current value: {line.get_value()}")
        print()
        print("Waiting for edge events...")
        print("(Trigger a TX with: sudo python3 lora_test.py --skip-gpio --transmit)")
        print()

        event_count = 0
        last_print = time.time()

        while True:
            # Wait for event with 1 second timeout
            if line.event_wait(sec=1):
                event = line.event_read()
                event_count += 1

                event_type = "RISING_EDGE" if event.type == gpiod.LineEvent.RISING_EDGE else "FALLING_EDGE"
                timestamp = f"{event.sec}.{event.nsec:09d}"

                print(f"GPIO{line_num} event #{event_count}: {event_type} at {timestamp}")
            else:
                # Timeout - print current value every 5 seconds
                now = time.time()
                if now - last_print >= 5.0:
                    val = line.get_value()
                    print(f"  (waiting... current value: {val})", end='\r')
                    last_print = now

    except KeyboardInterrupt:
        print(f"\n\nStopped. Total events received: {event_count}")
    except Exception as e:
        print(f"ERROR: {e}")
        return 1
    finally:
        try:
            line.release()
        except:
            pass

    return 0


def scan_gpio_values(chip_name: str):
    """Scan and print current values of all candidate GPIO lines."""
    print(f"Scanning GPIO values on {chip_name}...")
    print()

    try:
        chip = gpiod.Chip(chip_name)

        for line_num, description in sorted(ALTERNATIVE_LINES.items()):
            try:
                line = chip.get_line(line_num)
                line.request(consumer="gpio_scan", type=gpiod.LINE_REQ_DIR_IN)
                value = line.get_value()
                line.release()
                print(f"  Line {line_num}: {value} ({description})")
            except Exception as e:
                print(f"  Line {line_num}: ERROR - {e}")

        print()

    except Exception as e:
        print(f"ERROR opening chip: {e}")


def main():
    import argparse

    parser = argparse.ArgumentParser(
        description="Monitor GPIO for edge events (DIO0 interrupt testing)"
    )
    parser.add_argument(
        "--line", "-l",
        type=int,
        default=DIO0_LINE,
        help=f"GPIO line number to monitor (default: {DIO0_LINE})"
    )
    parser.add_argument(
        "--chip", "-c",
        default=GPIO_CHIP,
        help=f"GPIO chip name (default: {GPIO_CHIP})"
    )
    parser.add_argument(
        "--scan", "-s",
        action="store_true",
        help="Scan and print current values of all candidate GPIO lines"
    )

    args = parser.parse_args()

    print("=" * 60)
    print("GPIO Monitor - Testing Interrupt Capability")
    print("=" * 60)
    print()

    if args.scan:
        scan_gpio_values(args.chip)
        print("To monitor a specific line, run without --scan:")
        print(f"  sudo python3 gpio_monitor.py --line {DIO0_LINE}")
        return 0

    return monitor_line(args.chip, args.line)


if __name__ == "__main__":
    sys.exit(main())
