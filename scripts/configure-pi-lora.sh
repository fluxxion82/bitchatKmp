#!/bin/sh
# Run on the Pi. Default is audit only; --apply is a separate, explicit setup step.
# Keep scripts/pi_lora_runtime.py and apps/embedded/systemd beside this package.
set -eu
exec python3 "$(dirname "$0")/pi_lora_runtime.py" "$@"
