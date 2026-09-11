#!/bin/sh
# Run locally on the Pi; safe while the app is using the single-client radio.
# No socket connection probe and no radio messages. Requires adjacent Python helper.
set -eu
exec python3 "$(dirname "$0")/pi_lora_runtime.py" --verify "$@"
