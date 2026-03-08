#!/usr/bin/env python3
"""
Meshtastic TCP Test Script

Tests sending messages via meshtasticd TCP API using the official
Meshtastic Python library. This validates that:
1. meshtasticd is running and accepting connections
2. The channel configuration is correct
3. Messages can be sent over LoRa

Usage:
  # Make sure meshtasticd is running first
  sudo systemctl start meshtasticd

  # Send a test message
  python3 meshtastic_send.py

  # Send a custom message
  python3 meshtastic_send.py "Hello from Python!"

  # Get device info
  python3 meshtastic_send.py --info

Dependencies:
  pip3 install meshtastic
  # Or on the Pi:
  source ~/meshtastic-build/bin/activate
"""

import sys
import argparse
import time


def main():
    parser = argparse.ArgumentParser(
        description="Test Meshtastic communication via TCP"
    )
    parser.add_argument(
        "message",
        nargs="?",
        default="Hello from Orange Pi!",
        help="Message to send (default: 'Hello from Orange Pi!')"
    )
    parser.add_argument(
        "--host",
        default="127.0.0.1",
        help="meshtasticd host (default: 127.0.0.1)"
    )
    parser.add_argument(
        "--port",
        type=int,
        default=4403,
        help="meshtasticd port (default: 4403)"
    )
    parser.add_argument(
        "--info",
        action="store_true",
        help="Show device info and exit"
    )
    parser.add_argument(
        "--nodes",
        action="store_true",
        help="Show all nodes in the mesh and exit"
    )
    parser.add_argument(
        "--channels",
        action="store_true",
        help="Show channel configuration and exit"
    )

    args = parser.parse_args()

    try:
        import meshtastic
        import meshtastic.tcp_interface
    except ImportError:
        print("ERROR: meshtastic library not installed")
        print("Install with: pip3 install meshtastic")
        print("Or activate venv: source ~/meshtastic-build/bin/activate")
        return 1

    print(f"Connecting to meshtasticd at {args.host}:{args.port}...")

    try:
        interface = meshtastic.tcp_interface.TCPInterface(
            hostname=args.host,
            portNumber=args.port
        )
    except Exception as e:
        print(f"ERROR: Failed to connect: {e}")
        print()
        print("Make sure meshtasticd is running:")
        print("  sudo systemctl status meshtasticd")
        print("  sudo systemctl start meshtasticd")
        return 1

    print(f"Connected!")
    print()

    try:
        # Get my node info
        my_info = interface.myInfo
        my_node = interface.getNode("^local")

        print("=== My Node ===")
        print(f"  Node ID: !{my_info.my_node_num:08x}")
        firmware = getattr(my_info, "firmware_version", None)
        if firmware is None:
            firmware = getattr(my_info, "firmware_edition", "unknown")
        print(f"  Firmware: {firmware}")
        if hasattr(my_node, 'user') and my_node.user:
            print(f"  Long Name: {my_node.user.longName}")
            print(f"  Short Name: {my_node.user.shortName}")
        print()

        if args.info:
            return 0

        if args.channels:
            print("=== Channels ===")
            if hasattr(interface, 'localNode') and interface.localNode:
                for i, ch in enumerate(interface.localNode.channels):
                    if ch.role != 0:  # Not DISABLED
                        role_name = ["DISABLED", "PRIMARY", "SECONDARY"][ch.role] if ch.role < 3 else str(ch.role)
                        print(f"  Channel {i}: {ch.settings.name or '(unnamed)'} ({role_name})")
                        if ch.settings.psk:
                            print(f"    PSK: {ch.settings.psk.hex()}")
            print()
            return 0

        if args.nodes:
            print("=== Nodes in Mesh ===")
            for node_id, node in interface.nodes.items():
                user = node.get('user', {})
                position = node.get('position', {})
                last_heard = node.get('lastHeard', 0)

                name = user.get('longName', user.get('shortName', f'!{node_id}'))
                print(f"  {name}")
                print(f"    ID: {node_id}")
                if last_heard:
                    print(f"    Last heard: {time.ctime(last_heard)}")
                if position:
                    lat = position.get('latitude', 0)
                    lon = position.get('longitude', 0)
                    if lat or lon:
                        print(f"    Position: {lat:.6f}, {lon:.6f}")
                print()
            return 0

        # Send message
        print(f"Sending message: {args.message}")
        print()

        result = interface.sendText(args.message)

        if result:
            print(f"Message sent!")
            print(f"  Packet ID: {result.id}")
        else:
            print("Message send returned None (may still have been sent)")

        print()
        print("Check Meshtastic app for reception.")

    finally:
        interface.close()

    return 0


if __name__ == "__main__":
    sys.exit(main())
