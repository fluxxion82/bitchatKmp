#!/usr/bin/env bash
# Shared pin and source preparation for all Arti platform builds.
ARTI_BUILD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export RUSTUP_TOOLCHAIN="${RUSTUP_TOOLCHAIN:-$(tr -d '[:space:]' < "$ARTI_BUILD_DIR/RUST_TOOLCHAIN")}"

ensure_arti_source() {
  local source="$1" expected="$2" actual
  if [[ ! "$expected" =~ ^[0-9a-f]{40}$ ]]; then
    echo "ARTI_VERSION must contain an exact commit hash" >&2
    return 1
  fi
  if [ ! -e "$source/.git" ]; then
    echo "Arti submodule not initialized: $source; run git submodule update --init" >&2
    return 1
  fi
  actual="$(git -c safe.directory="$source" -C "$source" rev-parse HEAD)" || return 1
  if [ "$actual" != "$expected" ]; then
    echo "Arti checkout $actual does not match ARTI_VERSION $expected; update the submodule explicitly" >&2
    return 1
  fi
  # The nested arti-corpora checkout contains test data, not wrapper build sources.
  if ! git -c safe.directory="$source" -C "$source" diff --quiet HEAD --ignore-submodules=all; then
    echo "Arti has tracked changes; build only the reviewed commit" >&2
    return 1
  fi
}

copy_arti_source() {
  local source="$1" destination="$2" commit scratch
  commit="$(git -c safe.directory="$source" -C "$source" rev-parse HEAD)" || return 1
  if [ -f "$destination/.bitchat-source-commit" ] &&
     [ "$(cat "$destination/.bitchat-source-commit")" = "$commit" ]; then
    return 0
  fi
  mkdir -p "$(dirname "$destination")"
  scratch="$(mktemp -d "${destination}.XXXXXX")" || return 1
  # Copy tracked source only: do not copy user files, Git metadata or old Cargo targets.
  if ! (set -o pipefail; git -c safe.directory="$source" -C "$source" archive "$commit" | tar -xf - -C "$scratch"); then
    rm -rf "$scratch"
    return 1
  fi
  printf '%s\n' "$commit" > "$scratch/.bitchat-source-commit"
  rm -rf "$destination"
  mv "$scratch" "$destination"
}
