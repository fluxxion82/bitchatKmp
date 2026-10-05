#!/usr/bin/env bash
# Build, ship, verify and restart a selected embedded UI board on the Orange Pi.
#
# Release layout on the device:
#   Compose: /opt/bitchat/releases/<sha12>[-dirty]-<build>-<digest8>/   binary, compose-resources/,
#                                                                         SHA256SUMS, BUILD_INFO, bitchat.service,
#                                                                         wait-for-input-devices.sh,
#                                                                         bluetooth-bitchat-ble.conf
#            /opt/bitchat/releases/current -> <release dir>             swapped atomically (ln + mv -T)
#            /opt/bitchat/bitchat.service                               copy owned by the deploy user
#                                                                         that the sudoers rule lets us install
#            ~/bitchat-embedded.kexe -> .../current/bitchat-embedded.kexe
#   TUI:     /opt/bitchat-tui/releases/<sha12>[-dirty]-<build>-<digest8>/ binary, launcher, tmux config,
#                                                                         attach script, SHA256SUMS, BUILD_INFO,
#                                                                         bitchat-tui.service, bluetooth-bitchat-ble.conf
#            /opt/bitchat-tui/releases/current -> <release dir>         swapped atomically (ln + mv -T)
#            /opt/bitchat-tui/bitchat-tui.service                       copy owned by the deploy user
#            ~/bitchat-tui.kexe -> .../current/bitchat-tui.kexe
#            ~/bitchat-tui-attach -> .../current/bitchat-tui-attach
#
# <digest8> is the first 8 hex chars of sha256 over the SHA256SUMS lines minus the
# ./BUILD_INFO line, so a release name is a pure function of the shipped payload
# (executable, release resources, unit, launcher scripts, bluetoothd drop-in) and an existing release directory is never
# rewritten with a different payload.
#
# Identity comes from the selected board's build-info sidecar that every link task writes
# next to the executable. Its kexe_sha256 must match the
# executable we ship (so --no-build cannot pair stale metadata with a newer binary) and its
# identity= line must be exactly what `--version` prints on the device and what the service
# logs at startup.
#
# bash 3.2 compatible (macOS): no associative arrays, mapfile or ${var,,}.
# macOS rsync is openrsync (protocol 29): only plain -a --delete -e are used; set RSYNC to
# use another binary. Every remote command is passed as the ssh argument, never via stdin.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$(pwd)"
RSYNC="${RSYNC:-rsync}"

usage() {
  cat <<USAGE
Usage: scripts/deploy-pi.sh [options]

Builds and deploys the selected linuxArm64 UI board. Compose is the default:
it stages the executable, compose-resources/, its unit and input-device wait
script; tui stages the executable and its console unit. Every release gets a
SHA256SUMS manifest and BUILD_INFO, is verified on the device, atomically
selected through that board's current symlink, and then its unit is restarted.

The target is not baked into this repository. Set PI_HOST (an ssh
destination: user@host, or a Host alias from ~/.ssh/config) or pass
--host. Key-based ssh must work without a passphrase prompt (BatchMode).

Options:
  --ui compose|tui  select the board to deploy (default: compose)
  --release          link the release binary (default: debug)
  --debug            link the debug binary
  --no-build         reuse the selected board's existing link output
                    (its build-info sidecar must match)
  --no-restart       upload, verify and switch that board's current release,
                    but do not install or restart its unit
  --dry-run          build and stage locally, print the selected release and
                    manifest, and do not use ssh
  --host DEST        target ssh destination (overrides \$PI_HOST); required if
                    PI_HOST is unset
  -h, --help

Env:
  PI_HOST   target ssh destination (required unless --host is given)
  PI_USER   account the selected unit runs as (default: the user part of the
            target, so PI_HOST=pi@box implies pi; required when the target is
            a bare host or an ssh alias)
  PI_GROUP  group for the selected unit (default: same as PI_USER)
  RSYNC     rsync binary (default: rsync)
USAGE
}
die() { echo "deploy-pi.sh: $*" >&2; exit 1; }
log() { echo "== $*"; }

# --- 1. options -------------------------------------------------------------
UI=compose; BUILD=debug; DO_BUILD=1; DO_RESTART=1; DRY_RUN=0; HOST="${PI_HOST:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ui)         [[ $# -ge 2 ]] || { echo "deploy-pi.sh: --ui needs compose or tui" >&2; usage >&2; exit 2; }
                  UI="$2"; shift ;;
    --release)    BUILD=release ;;
    --debug)      BUILD=debug ;;
    --no-build)   DO_BUILD=0 ;;
    --no-restart) DO_RESTART=0 ;;
    --dry-run)    DRY_RUN=1 ;;
    --host)       [[ $# -ge 2 ]] || { echo "deploy-pi.sh: --host needs an ssh destination" >&2; usage >&2; exit 2; }
                  HOST="$2"; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            echo "deploy-pi.sh: unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
case "$UI" in compose|tui) ;; *) echo "deploy-pi.sh: unknown UI: $UI" >&2; usage >&2; exit 2 ;; esac
[[ -n "$HOST" ]] || die "no target; set PI_HOST=user@host or pass --host user@host (see --help)"
# The unit runs as an unprivileged account on the device. Derive it from the target so a plain
# PI_HOST=user@host needs nothing else, and let PI_USER/PI_GROUP override for ssh aliases.
PI_USER="${PI_USER:-}"
if [[ -z "$PI_USER" && "$HOST" == *@* ]]; then PI_USER="${HOST%%@*}"; fi
if [[ -z "$PI_USER" ]]; then
  case "$UI" in
    compose) die "cannot tell which account bitchat.service should run as: '$HOST' carries no user part, so set PI_USER" ;;
    tui)     die "cannot tell which account bitchat-tui.service should run as: '$HOST' carries no user part, so set PI_USER" ;;
  esac
fi
case "$PI_USER" in *[!A-Za-z0-9._-]*) die "PI_USER '$PI_USER' is not a plain user name" ;; esac
PI_GROUP="${PI_GROUP:-$PI_USER}"
case "$PI_GROUP" in *[!A-Za-z0-9._-]*) die "PI_GROUP '$PI_GROUP' is not a plain group name" ;; esac

case "$BUILD" in debug) BUILD_CAP=Debug ;; release) BUILD_CAP=Release ;; esac
TASK="link${BUILD_CAP}ExecutableLinuxArm64"
case "$UI" in
  compose)
    APP_PROJECT="apps:embedded:compose"
    APP_DIR="apps/embedded/compose"
    BINARY_BASENAME="bitchat-embedded"
    SERVICE_NAME="bitchat.service"
    RELEASES="/opt/bitchat/releases"
    UNIT_FILE="$REPO/apps/embedded/systemd/bitchat.service"
    OWNER_UNIT_FILE="/opt/bitchat/bitchat.service"
    HOME_LINK_NAME="bitchat-embedded.kexe"
    NEEDS_COMPOSE_RESOURCES=1
    NEEDS_INPUT_WAIT=1
    # The unit's ExecStartPre= references this through /opt/bitchat/releases/current/, so it ships
    # inside the release directory (and therefore counts towards the payload digest).
    WAIT_SCRIPT="$REPO/apps/embedded/systemd/wait-for-input-devices.sh"
    ;;
  tui)
    APP_PROJECT="apps:embedded:tui"
    APP_DIR="apps/embedded/tui"
    BINARY_BASENAME="bitchat-tui"
    SERVICE_NAME="bitchat-tui.service"
    RELEASES="/opt/bitchat-tui/releases"
    UNIT_FILE="$REPO/apps/embedded/systemd/bitchat-tui.service"
    OWNER_UNIT_FILE="/opt/bitchat-tui/bitchat-tui.service"
    HOME_LINK_NAME="bitchat-tui.kexe"
    ATTACH_LINK_NAME="bitchat-tui-attach"
    TUI_LAUNCHER="$REPO/apps/embedded/systemd/bitchat-tui-launcher"
    TUI_TMUX_CONFIG="$REPO/apps/embedded/systemd/bitchat-tui.tmux.conf"
    TUI_ATTACH="$REPO/apps/embedded/systemd/bitchat-tui-attach"
    NEEDS_COMPOSE_RESOURCES=0
    NEEDS_INPUT_WAIT=0
    WAIT_SCRIPT=""
    ;;
esac
OUT_DIR="$REPO/$APP_DIR/build/bin/linuxArm64/${BUILD}Executable"
BINARY="$OUT_DIR/$BINARY_BASENAME.kexe"
SIDECAR="$OUT_DIR/$BINARY_BASENAME.build-info"
# Installed by hand, once (README, "Pairing prompts on iPhones"); shipped so that copy is on the device and
# so step 11a can tell whether the installed one and the running bluetoothd still match it.
BT_DROPIN_SRC="$REPO/apps/embedded/systemd/bluetooth.service.d/bitchat-ble.conf"
BT_DROPIN_NAME="bluetooth-bitchat-ble.conf"
BT_DROPIN="/etc/systemd/system/bluetooth.service.d/bitchat-ble.conf"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=60)
# -n: ssh must never read this script's stdin. Only here, never in the rsync -e string.
remote() { ssh -n "${SSH_OPTS[@]}" "$HOST" "$@"; }
# ssh and rsync exit 255 when the transport itself failed, not the remote command.
# The optional third argument is appended to the message (used once current is swapped).
transport_check() { [[ "$1" != 255 ]] || die "ssh transport failure talking to $HOST ($2)${3:+; $3}"; }
# Flush the device's page cache to the card.
#
# Uploading and verifying a release does not make it durable. sha256sum -c reads back through
# the page cache, so it reports the bytes in RAM, not the bytes on the card -- a release can
# verify perfectly while none of it has been written. The Orange Pi mounts root data=writeback
# with commit=120 (Armbian's defaults, to spare the SD card), which leaves a window up to two
# minutes wide where losing power turns freshly written files into zero-length ones that still
# have valid metadata and their original mode. That is not theoretical: it emptied every file
# of a verified release except the kexe, and a 0-byte ExecStartPre script with its +x bit
# intact fails at boot with 203/EXEC.
#
# A clean reboot syncs, so this only matters when power is cut. Needs no privileges. Never
# fatal: a release that is on the device but not yet flushed is still better than aborting,
# and the device flushes on its own soon enough.
sync_remote() {
  local rc=0
  remote "sync" || rc=$?
  transport_check "$rc" "sync${1:+, $1}" "${2:-}"
  [[ "$rc" == 0 ]] \
    || echo "WARNING: sync failed on $HOST (exit $rc)${1:+ ($1)}; the release is not durable until the device flushes on its own -- do not cut power yet" >&2
}

# --- 2. build ---------------------------------------------------------------
if [[ "$DO_BUILD" == 1 ]]; then
  log "./gradlew -Pembedded.enabled=true :$APP_PROJECT:$TASK --console=plain"
  ./gradlew -Pembedded.enabled=true ":$APP_PROJECT:$TASK" --console=plain
fi

# --- 3. identity (from the sidecar the link task wrote next to the binary) --
[[ -f "$BINARY" ]]  || die "missing $BINARY; run without --no-build (or ./gradlew -Pembedded.enabled=true :$APP_PROJECT:$TASK)"
[[ -f "$SIDECAR" ]] || die "no build-info sidecar next to the binary; run without --no-build"
if [[ "$NEEDS_COMPOSE_RESOURCES" == 1 ]]; then
  [[ -d "$OUT_DIR/compose-resources" ]] || die "missing $OUT_DIR/compose-resources; the app resolves it beside the executable, relink with :$APP_PROJECT:$TASK"
fi
[[ -f "$UNIT_FILE" ]] || die "missing $UNIT_FILE"
if [[ "$NEEDS_INPUT_WAIT" == 1 ]]; then
  [[ -f "$WAIT_SCRIPT" ]] || die "missing $WAIT_SCRIPT ($SERVICE_NAME runs it as ExecStartPre)"
fi
[[ -f "$BT_DROPIN_SRC" ]] || die "missing $BT_DROPIN_SRC"
# The last ExecStart= line is the command bluetoothd must be running with (the first one only resets it).
BT_EXEC="$(sed -n 's/^ExecStart=\(..*\)$/\1/p' "$BT_DROPIN_SRC" | tail -n 1)"
[[ -n "$BT_EXEC" ]] || die "$BT_DROPIN_SRC has no non-empty ExecStart= line"
field() { sed -n "s/^$1=//p" "$SIDECAR"; }
VERSION="$(field version)"; GIT_SHA="$(field git_sha)"; GIT_BRANCH="$(field git_branch)"
GIT_DIRTY="$(field git_dirty)"; BUILT_AT="$(field built_at)"; SIDECAR_BUILD="$(field build)"
KEXE_SHA256="$(field kexe_sha256)"; IDENTITY_EXPECTED="$(field identity)"
[[ -n "$VERSION" && -n "$GIT_SHA" && -n "$GIT_BRANCH" && -n "$GIT_DIRTY" && -n "$BUILT_AT" \
   && -n "$SIDECAR_BUILD" && -n "$KEXE_SHA256" && -n "$IDENTITY_EXPECTED" ]] \
  || die "could not parse version/git_sha/git_branch/git_dirty/built_at/build/kexe_sha256/identity from $SIDECAR"
[[ "$SIDECAR_BUILD" == "$BUILD" ]] \
  || die "sidecar says build=$SIDECAR_BUILD but --$BUILD was selected; relink with :$APP_PROJECT:$TASK"
LOCAL_SHA256="$(shasum -a 256 "$BINARY" | cut -d' ' -f1)"
[[ "$LOCAL_SHA256" == "$KEXE_SHA256" ]] || die "sidecar does not match the executable; rebuild"
SHA12="${GIT_SHA:0:12}"
log "identity: $IDENTITY_EXPECTED"

# --- 4. stage the payload ---------------------------------------------------
TMP="${TMPDIR:-/tmp}"; TMP="${TMP%/}"
STAGE="$(mktemp -d "$TMP/bitchat-deploy.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
# -p keeps the link-time mtimes so rsync of an unchanged release is a no-op.
cp -p "$BINARY" "$STAGE/$BINARY_BASENAME.kexe"
chmod 755 "$STAGE/$BINARY_BASENAME.kexe"
if [[ "$NEEDS_COMPOSE_RESOURCES" == 1 ]]; then
  cp -Rp "$OUT_DIR/compose-resources" "$STAGE/compose-resources"
fi
# The selected unit ships with __BITCHAT_USER__/__BITCHAT_GROUP__ placeholders so no account
# name is checked into the repository; fill them in here. touch -r restores the source mtime
# so an unchanged release still rsyncs as a no-op, and the payload digest stays content-based.
sed -e "s/__BITCHAT_USER__/$PI_USER/g" -e "s/__BITCHAT_GROUP__/$PI_GROUP/g" \
  "$UNIT_FILE" > "$STAGE/$SERVICE_NAME"
! grep -q '__BITCHAT_' "$STAGE/$SERVICE_NAME" \
  || die "$UNIT_FILE still has an unsubstituted __BITCHAT_* placeholder"
grep -q "^User=$PI_USER$" "$STAGE/$SERVICE_NAME" \
  || die "staged $SERVICE_NAME has no User=$PI_USER line; check $UNIT_FILE"
chmod 644 "$STAGE/$SERVICE_NAME"
touch -r "$UNIT_FILE" "$STAGE/$SERVICE_NAME"
if [[ "$NEEDS_INPUT_WAIT" == 1 ]]; then
  cp -p "$WAIT_SCRIPT" "$STAGE/wait-for-input-devices.sh"
  chmod 755 "$STAGE/wait-for-input-devices.sh"
fi
if [[ "$UI" == tui ]]; then
  cp -p "$TUI_LAUNCHER" "$STAGE/bitchat-tui-launcher"
  cp -p "$TUI_TMUX_CONFIG" "$STAGE/bitchat-tui.tmux.conf"
  cp -p "$TUI_ATTACH" "$STAGE/bitchat-tui-attach"
  chmod 755 "$STAGE/bitchat-tui-launcher" "$STAGE/bitchat-tui-attach"
  chmod 644 "$STAGE/bitchat-tui.tmux.conf"
fi
cp -p "$BT_DROPIN_SRC" "$STAGE/$BT_DROPIN_NAME"
chmod 644 "$STAGE/$BT_DROPIN_NAME"
manifest() { ( cd "$STAGE" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 > SHA256SUMS ); }

# --- 5. release name: <sha12>[-dirty]-<build>-<digest8 of the payload> ------
manifest
PAYLOAD_DIGEST="$(grep -v '  ./BUILD_INFO$' "$STAGE/SHA256SUMS" | shasum -a 256 | cut -c1-8)"
NAME="$SHA12"
[[ "$GIT_DIRTY" == "true" ]] && NAME="$NAME-dirty"
NAME="$NAME-$BUILD-$PAYLOAD_DIGEST"
REMOTE_DIR="$RELEASES/$NAME"
log "staging $NAME in $STAGE"
{
  cat "$SIDECAR"
  echo "release=$NAME"
  echo "deployed_from=$(hostname)"
  echo "deployed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$STAGE/BUILD_INFO"
manifest
( cd "$STAGE" && shasum -a 256 --quiet -c SHA256SUMS ) || die "local SHA256SUMS verification failed in $STAGE"
[[ "$(grep -v '  ./BUILD_INFO$' "$STAGE/SHA256SUMS" | shasum -a 256 | cut -c1-8)" == "$PAYLOAD_DIGEST" ]] \
  || die "payload digest changed while staging (internal error)"

# --- 6. dry run -------------------------------------------------------------
if [[ "$DRY_RUN" == 1 ]]; then
  log "dry run: would upload to $HOST:$REMOTE_DIR/"
  echo "release: $NAME"
  echo "--- BUILD_INFO ---";  cat "$STAGE/BUILD_INFO"
  echo "--- SHA256SUMS ---";  cat "$STAGE/SHA256SUMS"
  echo "--- size ---";        echo "$(du -sh "$STAGE" | cut -f1) staged in $STAGE (removed on exit)"
  exit 0
fi

# --- 7. preflight -----------------------------------------------------------
log "preflight: ssh $HOST"
rc=0; remote "test -w '$RELEASES' && mkdir -p '$REMOTE_DIR'" || rc=$?
transport_check "$rc" "preflight"
[[ "$rc" == 0 ]] || die "cannot write $RELEASES on $HOST: key-based ssh (BatchMode) must work and $RELEASES must be owned by the ssh user"

# --- 8. upload --------------------------------------------------------------
log "$RSYNC -> $HOST:$REMOTE_DIR/"
rc=0; "$RSYNC" -a --delete -e "ssh ${SSH_OPTS[*]}" "$STAGE/" "$HOST:$REMOTE_DIR/" || rc=$?
transport_check "$rc" "rsync"
[[ "$rc" == 0 ]] || die "rsync failed (exit $rc)"

# Before verifying, not after: the checksum pass below reads through the page cache either
# way, but syncing first means what it verifies is also what survives a power cut.
log "flushing the upload to disk on $HOST"
sync_remote "after upload"

# --- 9. verify on the device: checksums, then the exact identity line -------
log "verifying checksums and --version on $HOST"
rc=0; VERSION_OUT="$(remote "cd '$REMOTE_DIR' && sha256sum --quiet -c SHA256SUMS && ./$BINARY_BASENAME.kexe --version")" || rc=$?
transport_check "$rc" "verification"
[[ "$rc" == 0 ]] || die "on-device verification failed (exit $rc); $REMOTE_DIR left in place for inspection"
IDENTITY="$(printf '%s\n' "$VERSION_OUT" | tail -n 1)"
[[ "$IDENTITY" == "$IDENTITY_EXPECTED" ]] \
  || die "--version on the device printed '$IDENTITY', expected exactly '$IDENTITY_EXPECTED'; $REMOTE_DIR left in place"
echo "$IDENTITY"

# --- 10. switch current (atomic), remembering the previous target -----------
log "switching $RELEASES/current -> $REMOTE_DIR"
rc=0
SWITCH_OUT="$(remote "if [ -e '$RELEASES/current' ] && [ ! -L '$RELEASES/current' ]; then exit 43; fi
  readlink '$RELEASES/current' 2>/dev/null || echo '(none)'
  ln -sfn '$REMOTE_DIR' '$RELEASES/current.tmp' && mv -T '$RELEASES/current.tmp' '$RELEASES/current' && readlink '$RELEASES/current'")" || rc=$?
transport_check "$rc" "symlink swap"
[[ "$rc" != 43 ]] || die "$RELEASES/current exists and is not a symlink on $HOST; move it aside by hand"
[[ "$rc" == 0 ]] || die "could not swap $RELEASES/current on $HOST (exit $rc)"
PREVIOUS="$(printf '%s\n' "$SWITCH_OUT" | sed -n '1p')"
CURRENT="$(printf '%s\n' "$SWITCH_OUT" | sed -n '2p')"
[[ "$CURRENT" == "$REMOTE_DIR" ]] || die "$RELEASES/current points at '$CURRENT' after the swap, expected $REMOTE_DIR"
# From here on every failure must say so: the device already runs from the new release dir.
SWAPPED="current already points at $REMOTE_DIR (previous: $PREVIOUS)"

# --- 10b. keep the selected home link pointing at the current release -------
# A symlink can never go stale, and /proc/self/exe resolves symlinks fully, so the Compose
# resource reader still finds compose-resources/ in the release directory it points into.
# This step never fails the deploy: a home directory we cannot write to is a warning, not a
# broken release.
HOME_LINK_TARGET="$RELEASES/current/$BINARY_BASENAME.kexe"
HOME_LINK_OK=0
if [[ "$UI" == tui ]]; then
  # Preserve the pre-release hand-installed TUI at its documented backup name without ever
  # overwriting an existing backup. Later regular files use the normal dated stale name.
  HOME_MIGRATE='if [ ! -L "$f" ] && [ -f "$f" ]; then
    if [ ! -e "$f.prev.bak" ] && [ ! -L "$f.prev.bak" ]; then
      mv -- "$f" "$f.prev.bak" || exit 44
      echo "__moved=$f.prev.bak"
    else
      d=$(date -r "$f" +%Y-%m-%d 2>/dev/null) || d=""
      [ -n "$d" ] || d=unknown
      mv -- "$f" "$f.stale-$d" || exit 44
      echo "__moved=$f.stale-$d"
    fi
  fi'
else
  HOME_MIGRATE='if [ ! -L "$f" ] && [ -f "$f" ]; then
    d=$(date -r "$f" +%Y-%m-%d 2>/dev/null) || d=""
    [ -n "$d" ] || d=unknown
    mv -- "$f" "$f.stale-$d" || exit 44
    echo "__moved=$f.stale-$d"
  fi'
fi
HOME_CMD='f="$HOME/'"$HOME_LINK_NAME"'"
  if [ ! -w "$HOME" ]; then echo "__unwritable"; exit 0; fi
  '"$HOME_MIGRATE"'
  ln -sfn '"'$HOME_LINK_TARGET'"' "$f.tmp" && mv -T "$f.tmp" "$f" || exit 45
  echo "__link=$(readlink "$f")"'
rc=0; HOME_OUT="$(remote "$HOME_CMD")" || rc=$?
transport_check "$rc" "home symlink" "$SWAPPED"
if [[ "$rc" != 0 ]]; then
  echo "WARNING: could not point ~/$HOME_LINK_NAME at $HOME_LINK_TARGET on $HOST (exit $rc): ${HOME_OUT:-no output}" >&2
else
  MOVED="$(printf '%s\n' "$HOME_OUT" | sed -n 's/^__moved=//p')"
  HOME_LINK="$(printf '%s\n' "$HOME_OUT" | sed -n 's/^__link=//p')"
  [[ -z "$MOVED" ]] || log "moved the old regular file aside: $MOVED (delete it by hand when you no longer want it)"
  case "$HOME_OUT" in
    *__unwritable*) echo "WARNING: the home directory on $HOST is not writable; ~/$HOME_LINK_NAME not updated" >&2 ;;
    *) if [[ "$HOME_LINK" == "$HOME_LINK_TARGET" ]]; then
         HOME_LINK_OK=1
         log "home symlink: ~/$HOME_LINK_NAME -> $HOME_LINK_TARGET"
       else
         echo "WARNING: ~/$HOME_LINK_NAME points at '${HOME_LINK:-?}' on $HOST, expected $HOME_LINK_TARGET" >&2
       fi ;;
  esac
fi

HOME_ATTACH_LINK_OK=0
if [[ "$UI" == tui ]]; then
  # Keep the SSH attach entry point on the same atomic current-release link and preserve a
  # hand-installed regular file with the same rule used for the executable convenience link.
  ATTACH_LINK_TARGET="$RELEASES/current/$ATTACH_LINK_NAME"
  ATTACH_HOME_CMD='f="$HOME/'"$ATTACH_LINK_NAME"'"
  if [ ! -w "$HOME" ]; then echo "__unwritable"; exit 0; fi
  '"$HOME_MIGRATE"'
  ln -sfn '"'$ATTACH_LINK_TARGET'"' "$f.tmp" && mv -T "$f.tmp" "$f" || exit 45
  echo "__link=$(readlink "$f")"'
  rc=0; ATTACH_HOME_OUT="$(remote "$ATTACH_HOME_CMD")" || rc=$?
  transport_check "$rc" "attach home symlink" "$SWAPPED"
  if [[ "$rc" != 0 ]]; then
    echo "WARNING: could not point ~/$ATTACH_LINK_NAME at $ATTACH_LINK_TARGET on $HOST (exit $rc): ${ATTACH_HOME_OUT:-no output}" >&2
  else
    MOVED="$(printf '%s\n' "$ATTACH_HOME_OUT" | sed -n 's/^__moved=//p')"
    ATTACH_HOME_LINK="$(printf '%s\n' "$ATTACH_HOME_OUT" | sed -n 's/^__link=//p')"
    [[ -z "$MOVED" ]] || log "moved the old regular file aside: $MOVED (delete it by hand when you no longer want it)"
    case "$ATTACH_HOME_OUT" in
      *__unwritable*) echo "WARNING: the home directory on $HOST is not writable; ~/$ATTACH_LINK_NAME not updated" >&2 ;;
      *) if [[ "$ATTACH_HOME_LINK" == "$ATTACH_LINK_TARGET" ]]; then
           HOME_ATTACH_LINK_OK=1
           log "home symlink: ~/$ATTACH_LINK_NAME -> $ATTACH_LINK_TARGET"
         else
           echo "WARNING: ~/$ATTACH_LINK_NAME points at '${ATTACH_HOME_LINK:-?}' on $HOST, expected $ATTACH_LINK_TARGET" >&2
         fi ;;
    esac
  fi
fi

# --- 11. install unit, check boot ordering, restart, check the journal -------
STATE="not restarted (--no-restart)"
# Every ordering warning is appended, never overwritten, so the summary reports all of them.
ORDER_WARN=""
add_order_warn() { if [[ -z "$ORDER_WARN" ]]; then ORDER_WARN="$1"; else ORDER_WARN="$ORDER_WARN; $1"; fi; }
if [[ "$DO_RESTART" == 1 ]]; then
  log "installing $SERVICE_NAME"
  rc=0
  remote "cat '$REMOTE_DIR/$SERVICE_NAME' > '$OWNER_UNIT_FILE' || exit 42
    sudo -n install -m 644 -o root -g root '$OWNER_UNIT_FILE' /etc/systemd/system/$SERVICE_NAME \
      && sudo -n systemctl daemon-reload" || rc=$?
  transport_check "$rc" "install" "$SWAPPED"
  if [[ "$rc" == 42 ]]; then
    die "$OWNER_UNIT_FILE is missing or not writable by $PI_USER; create it once with: sudo install -o $PI_USER -g $PI_GROUP -m 644 /dev/null $OWNER_UNIT_FILE; $SWAPPED"
  elif [[ "$rc" != 0 ]]; then
    die "installing $SERVICE_NAME failed (exit $rc); $SWAPPED"
  fi

  # Boot-ordering check: the actual cycle test, run against the unit systemd has just loaded.
  # systemd adds an implicit After= from a target to every unit that target Wants, unless an
  # Runs for whichever unit this deploy installed: both carry After=multi-user.target plus another
  # unit that is itself After=multi-user.target (cardkb/xpt2046-touch for compose, getty@tty1 for the
  # TUI), so both can form the cycle below.
  # ordering dependency between the two already exists. cardkb.service and xpt2046-touch.service
  # both declare After=multi-user.target, which suppresses that implicit edge for them;
  # $SERVICE_NAME must do the same. Without it, multi-user.target ends up ordered after
  # $SERVICE_NAME and an After=cardkb.service here closes the cycle
  # bitchat -> cardkb -> multi-user.target -> bitchat. systemd breaks a cycle by deleting a job,
  # and at boot it deleted $SERVICE_NAME/start. A restart never exercises this, so the
  # post-restart check below cannot catch it: warn loudly, do not fail.
  rc=0
  ORDER_OUT="$(remote "systemctl show -p After --value $SERVICE_NAME; echo __after_sep__
    systemctl show -p After --value multi-user.target")" || rc=$?
  transport_check "$rc" "reading After=" "$SWAPPED"
  AFTER_UNIT="$(printf '%s\n' "$ORDER_OUT" | sed -n '1p')"
  ORDER_SEP="$(printf '%s\n' "$ORDER_OUT" | sed -n '2p')"
  AFTER_TARGET="$(printf '%s\n' "$ORDER_OUT" | sed -n '3p')"
  if [[ "$rc" != 0 || "$ORDER_SEP" != "__after_sep__" ]]; then
    echo "WARNING: could not read After= of $SERVICE_NAME / multi-user.target on $HOST (exit $rc, output '$ORDER_OUT'); boot-ordering check skipped" >&2
    add_order_warn "boot-ordering check skipped (could not read After= on $HOST)"
  else
    # The implicit reverse edge is present exactly when multi-user.target is ordered after us.
    IMPLICIT_EDGE=0
    case " $AFTER_TARGET " in *" $SERVICE_NAME "*) IMPLICIT_EDGE=1 ;; esac
    OFFENDERS=""
    if [[ "$IMPLICIT_EDGE" == 1 ]]; then
      log "note: multi-user.target is ordered after $SERVICE_NAME (implicit edge; the unit does not declare After=multi-user.target)"
      for unit in cardkb.service xpt2046-touch.service; do
        case " $AFTER_UNIT " in
          *" $unit "*) OFFENDERS="${OFFENDERS:+$OFFENDERS, }$unit" ;;
        esac
      done
    fi
    if [[ -n "$OFFENDERS" ]]; then
      ORDER_MSG="ordering cycle at boot: multi-user.target is ordered after $SERVICE_NAME (implicit edge) and $SERVICE_NAME is ordered after $OFFENDERS, which is itself After=multi-user.target; systemd will delete the $SERVICE_NAME/start job. Remedy: add multi-user.target to After= in $SERVICE_NAME, which suppresses the implicit multi-user.target->$SERVICE_NAME edge; verify after a reboot with journalctl -b -g 'ordering cycle'"
      echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
      echo "!!! ERROR: $ORDER_MSG" >&2
      echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
      add_order_warn "$ORDER_MSG"
    else
      log "boot ordering: no known bad After= edges (only a reboot proves autostart); After=$AFTER_UNIT"
    fi
  fi

  log "enabling and restarting $SERVICE_NAME"
  rc=0
  remote "sudo -n systemctl enable $SERVICE_NAME && sudo -n systemctl restart $SERVICE_NAME" || rc=$?
  transport_check "$rc" "enable/restart" "$SWAPPED"
  [[ "$rc" == 0 ]] || die "enabling or restarting $SERVICE_NAME failed (exit $rc); $SWAPPED"

  # The InvocationID identifies exactly the process systemd just started, so the journal
  # check cannot match an older run of the same SHA.
  rc=0; INV="$(remote "systemctl show -p InvocationID --value $SERVICE_NAME")" || rc=$?
  transport_check "$rc" "reading InvocationID" "$SWAPPED"
  [[ "$rc" == 0 ]] || die "systemctl show -p InvocationID $SERVICE_NAME failed on $HOST (exit $rc, output '$INV'); check: sudo -n systemctl status $SERVICE_NAME; $SWAPPED"
  case "$INV" in
    "" | *[!0-9a-f]*) die "$SERVICE_NAME has no InvocationID after restart (got '$INV'); check: sudo -n systemctl status $SERVICE_NAME; $SWAPPED" ;;
  esac
  # The identity line is printed before Koin, DRM and EGL initialise, so seeing it only
  # proves the process started. Once it is seen on attempt N, keep polling and accept only
  # when attempt >= N+2 (about 4 s later), the InvocationID is unchanged and the unit is
  # active. A crash in that window shows up as a changed or empty InvocationID (systemd
  # restarted it, or it is dead) or as a non-active state, and fails the deploy.
  log "waiting for invocation $INV to log the identity line"
  # Only the last remote command's exit status reaches ssh, so each status query echoes its
  # own exit code and the journal follows a fixed five-line header: state, __rc1=<is-active
  # exit>, InvocationID, __rc2=<show exit>, __end_state. Empty output still takes its line.
  # INV was validated as hex above, so splicing it into the command is safe.
  POLL_CMD="st=\$(systemctl is-active $SERVICE_NAME); rc1=\$?; echo \"\$st\"; echo \"__rc1=\$rc1\"
    inv=\$(systemctl show -p InvocationID --value $SERVICE_NAME); rc2=\$?; echo \"\$inv\"; echo \"__rc2=\$rc2\"
    echo __end_state
    journalctl _SYSTEMD_INVOCATION_ID='$INV' --no-pager -o cat"
  JOURNAL=""; OK=0; RESULT=""; SEEN=0; attempt=0; LIMIT=10
  while :; do
    attempt=$((attempt + 1))
    [[ "$attempt" == 1 ]] || sleep 2
    rc=0
    POLL_OUT="$(remote "$POLL_CMD")" || rc=$?
    transport_check "$rc" "polling $SERVICE_NAME" "$SWAPPED"
    [[ "$rc" == 0 ]] || { RESULT="journalctl failed on $HOST (exit $rc): $POLL_OUT"; break; }
    STATE="$(printf '%s\n' "$POLL_OUT" | sed -n '1p')"
    RC1="$(printf '%s\n' "$POLL_OUT" | sed -n '2p')"
    NOW_INV="$(printf '%s\n' "$POLL_OUT" | sed -n '3p')"
    RC2="$(printf '%s\n' "$POLL_OUT" | sed -n '4p')"
    MARK="$(printf '%s\n' "$POLL_OUT" | sed -n '5p')"
    JOURNAL="$(printf '%s\n' "$POLL_OUT" | sed '1,5d')"
    [[ "$RC1" == __rc1=* && "$RC2" == __rc2=* && "$MARK" == "__end_state" ]] \
      || { RESULT="unexpected poll output from $HOST: $POLL_OUT"; break; }
    RC1="${RC1#__rc1=}"; RC2="${RC2#__rc2=}"
    # A failed InvocationID read says nothing about the unit: fail closed instead of guessing.
    [[ "$RC2" == 0 ]] || { RESULT="systemctl show -p InvocationID failed on $HOST (exit $RC2, output '$NOW_INV'); state '$STATE'"; break; }
    if [[ "$NOW_INV" != "$INV" ]]; then
      RESULT="invocation $INV ended or changed (unit inactive, failed, or restarted): state '$STATE', InvocationID now '$NOW_INV'"; break
    fi
    # is-active exits 0 only for active (3 for activating, inactive, failed, ...), so a
    # non-zero exit is expected while activating; the state text decides everything else.
    case "$STATE" in
      active)     [[ "$RC1" == 0 ]] || { RESULT="systemctl is-active reported 'active' but exited $RC1"; break; } ;;
      activating) ;;
      *)          RESULT="$SERVICE_NAME is '$STATE' (expected active; is-active exit $RC1)"; break ;;
    esac
    # No grep -q: it would exit early and a SIGPIPE'd printf would read as "no match" under pipefail.
    if [[ "$SEEN" == 0 ]] && printf '%s\n' "$JOURNAL" | grep -xF -- "$IDENTITY_EXPECTED" >/dev/null; then
      SEEN=$attempt
      [[ "$LIMIT" -ge $((SEEN + 2)) ]] || LIMIT=$((SEEN + 2))
      log "identity seen on attempt $attempt; confirming the invocation stays active"
    fi
    if [[ "$SEEN" != 0 && "$attempt" -ge $((SEEN + 2)) && "$STATE" == "active" ]]; then
      OK=1; break
    fi
    if [[ "$attempt" -ge "$LIMIT" ]]; then
      if [[ "$SEEN" == 0 ]]; then
        RESULT="$SERVICE_NAME is '$STATE' but the journal for invocation $INV never showed the identity line ($attempt polls)"
      else
        RESULT="$SERVICE_NAME is '$STATE' after the identity line (seen on attempt $SEEN) but was not confirmed active"
      fi
      break
    fi
  done
  if [[ "$OK" != 1 ]]; then
    echo "--- journal for invocation $INV ---"
    printf '%s\n' "$JOURNAL"
    echo "--- journalctl -u $SERVICE_NAME -n 50 ---"
    remote "journalctl -u $SERVICE_NAME -n 50 --no-pager" || true
    die "${RESULT:-post-restart check failed}; $SWAPPED"
  fi
  echo "--- journal for invocation $INV (last 15 lines) ---"
  printf '%s\n' "$JOURNAL" | tail -n 15

  if [[ "$UI" == tui ]]; then
    # The private tmux session is optional: the first deploy intentionally runs direct until the
    # owner installs tmux. Check the direct and shared-terminal arrangements separately.
    TUI_WIRING_CMD='service='"$SERVICE_NAME"'
config='"$RELEASES"'/current/bitchat-tui.tmux.conf
socket=bitchat-tui
session=bitchat-tui
pid=$(systemctl show -p MainPID --value "$service") || exit 1
[ -n "$pid" ] && [ "$pid" != 0 ] || exit 1
cgroup=$(systemctl show -p ControlGroup --value "$service") || exit 1
[ -n "$cgroup" ] || exit 1
cgroup_procs=
for candidate in /sys/fs/cgroup"$cgroup"/cgroup.procs /sys/fs/cgroup/*"$cgroup"/cgroup.procs; do
  if [ -r "$candidate" ]; then cgroup_procs=$candidate; break; fi
done
[ -n "$cgroup_procs" ] || exit 1
in_cgroup() { grep -qx "$1" "$cgroup_procs"; }
tty_of() { ps -o tty= -p "$1" | tr -d " "; }
in_cgroup "$pid" || exit 1
tmux_bin=$(command -v tmux 2>/dev/null || true)
if [ -n "$tmux_bin" ] && "$tmux_bin" -u -L "$socket" -f "$config" has-session -t "$session" >/dev/null 2>&1; then
  main_cmd=$(tr "\0" " " < "/proc/$pid/cmdline")
  case "$main_cmd" in *bitchat-tui-launcher*) ;; *) exit 1 ;; esac
  "$tmux_bin" -u -L "$socket" -f "$config" list-clients -t "$session" -F "#{client_tty}" | grep -qx /dev/tty1 || exit 1
  pane_pid=$("$tmux_bin" -u -L "$socket" -f "$config" display-message -p -t "$session" "#{pane_pid}") || exit 1
  case "$pane_pid" in ""|*[!0-9]*) exit 1 ;; esac
  pane_tty=$(tty_of "$pane_pid")
  case "$pane_tty" in pts/*) ;; *) exit 1 ;; esac
  in_cgroup "$pane_pid" || exit 1
  server_pid=
  relay_pid=
  while IFS= read -r candidate; do
    command=$(ps -o comm= -p "$candidate" | tr -d " ")
    [ "$command" = "tmux:server" ] && server_pid=$candidate
    [ "$command" = "systemd-cat" ] && relay_pid=$candidate
  done < "$cgroup_procs"
  [ -n "$server_pid" ] && [ -n "$relay_pid" ] || exit 1
  echo "tmux wiring: launcher $pid tty $(tty_of "$pid"), server $server_pid, pane $pane_pid /dev/$pane_tty, systemd-cat $relay_pid"
  systemd-cgls --no-pager "$cgroup"
else
  exe=$(readlink -f "/proc/$pid/exe") || exit 1
  case "$exe" in */bitchat-tui.kexe) ;; *) exit 1 ;; esac
  [ "$(tty_of "$pid")" = tty1 ] || exit 1
  echo "direct wiring: MainPID $pid is $exe on tty1"
fi'
    log "checking TUI terminal wiring"
    rc=0; WIRING_OUT="$(remote "$TUI_WIRING_CMD")" || rc=$?
    transport_check "$rc" "TUI terminal wiring" "$SWAPPED"
    [[ "$rc" == 0 ]] || die "TUI terminal wiring check failed (exit $rc): $WIRING_OUT; $SWAPPED"
    printf '%s\n' "$WIRING_OUT"
  fi
fi

# --- 11a. bluetoothd must stay off everything a phone exposes but bitchat --
# Read-only and never fatal: messaging works either way, but without the drop-in bluetoothd's profile
# plugins read protected attributes on every iPhone this device connects to, and the phone shows a
# pairing dialog on every reconnect (README, "Pairing prompts on iPhones"). This checks the device, not
# the release, so it runs whatever --no-restart did. Installing needs the password, hence a printed
# command and not a sudo -n here.
log "checking bluetoothd against $BT_DROPIN"
BT_WARN=""
BT_APPLY="sudo systemctl daemon-reload && sudo systemctl restart bluetooth.service && sudo systemctl restart $SERVICE_NAME"
BT_INSTALL="ssh -t $HOST 'sudo install -D -m 644 -o root -g root $RELEASES/current/$BT_DROPIN_NAME $BT_DROPIN && $BT_APPLY'"
# The running command line comes from /proc, which needs no privileges; its NULs become spaces.
BT_CMD='d='"'$BT_DROPIN'"'
  if cmp -s "$d" '"'$REMOTE_DIR/$BT_DROPIN_NAME'"'; then echo __dropin=current
  elif [ -e "$d" ]; then echo __dropin=differs
  else echo __dropin=missing; fi
  pid=$(systemctl show -p MainPID --value bluetooth.service 2>/dev/null) || pid=
  if [ -n "$pid" ] && [ "$pid" != 0 ] && [ -r "/proc/$pid/cmdline" ]; then
    echo "__cmd=$(tr "\0" " " < "/proc/$pid/cmdline")"
  else
    echo "__cmd="
  fi'
rc=0; BT_OUT="$(remote "$BT_CMD")" || rc=$?
transport_check "$rc" "bluetoothd check" "$SWAPPED"
BT_STATE="$(printf '%s\n' "$BT_OUT" | sed -n 's/^__dropin=//p')"
BT_RUNNING="$(printf '%s\n' "$BT_OUT" | sed -n 's/^__cmd=//p' | sed 's/ *$//')"
if [[ "$rc" != 0 || -z "$BT_STATE" ]]; then
  BT_WARN="could not check bluetoothd on $HOST (exit $rc, output '$BT_OUT')"
elif [[ "$BT_STATE" != current ]]; then
  BT_WARN="$BT_DROPIN is $BT_STATE on $HOST, so iPhones it connects to keep getting pairing dialogs; check the ExecStart path against systemctl cat bluetooth.service, then install it with: $BT_INSTALL"
elif [[ "$BT_RUNNING" != "$BT_EXEC" ]]; then
  BT_WARN="$BT_DROPIN is installed but bluetoothd on $HOST runs '${BT_RUNNING:-nothing, or /proc is unreadable}', not '$BT_EXEC'; apply it with: ssh -t $HOST '$BT_APPLY'"
else
  log "bluetoothd runs with $BT_DROPIN"
fi
[[ -z "$BT_WARN" ]] || echo "WARNING: $BT_WARN" >&2

# --- 11b. flush everything written since the upload -------------------------
# The current symlink, the selected home link and /etc/systemd/system/$SERVICE_NAME are all
# written after the sync in step 8, and a release the device cannot find at boot is as broken
# as one with empty files. Runs whatever --no-restart did, because the symlink swap happens
# either way.
log "flushing the switch to disk on $HOST"
sync_remote "after switch" "$SWAPPED"

# --- 12. summary ------------------------------------------------------------
echo
echo "release:  $HOST:$REMOTE_DIR"
echo "current -> $CURRENT"
if [[ "$PREVIOUS" == "(none)" ]]; then
  echo "previous: (none)"
elif [[ "$PREVIOUS" == "$REMOTE_DIR" ]]; then
  echo "previous: $PREVIOUS (same release)"
else
  echo "previous: $PREVIOUS"
  if [[ "$UI" == tui ]]; then
    echo "rollback: ssh $HOST \"ln -sfn '$PREVIOUS' '$RELEASES/current.tmp' && mv -T '$RELEASES/current.tmp' '$RELEASES/current' && cp '$RELEASES/current/$SERVICE_NAME' '$OWNER_UNIT_FILE' && sudo -n install -m 644 -o root -g root '$OWNER_UNIT_FILE' /etc/systemd/system/$SERVICE_NAME && sudo -n systemctl daemon-reload && sudo -n systemctl restart $SERVICE_NAME\""
  else
    echo "rollback: ssh $HOST \"ln -sfn '$PREVIOUS' '$RELEASES/current.tmp' && mv -T '$RELEASES/current.tmp' '$RELEASES/current' && sudo -n systemctl restart $SERVICE_NAME\""
  fi
fi
echo "identity: $IDENTITY"
echo "service:  $STATE"
if [[ "$HOME_LINK_OK" == 1 ]]; then
  echo "home:     ~/$HOME_LINK_NAME -> current (run with the service stopped: sudo systemctl stop $SERVICE_NAME)"
else
  echo "home:     ~/$HOME_LINK_NAME not updated (see the warning above)"
fi
if [[ "$UI" == tui ]]; then
  if [[ "$HOME_ATTACH_LINK_OK" == 1 ]]; then
    echo "attach:   ~/$ATTACH_LINK_NAME -> current"
  else
    echo "attach:   ~/$ATTACH_LINK_NAME not updated (see the warning above)"
  fi
fi
[[ -z "$ORDER_WARN" ]] || echo "WARNING:  $ORDER_WARN"
[[ -z "$BT_WARN" ]] || echo "WARNING:  $BT_WARN"
echo "autostart is only proven by a reboot: journalctl -b -u $SERVICE_NAME; journalctl -b -g 'ordering cycle'"
