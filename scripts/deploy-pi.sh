#!/usr/bin/env bash
# Build, ship, verify and restart the embedded binary on the Orange Pi.
#
# Release layout on the device:
#   /opt/bitchat/releases/<sha12>[-dirty]-<build>-<digest8>/   binary, compose-resources/,
#                                                              SHA256SUMS, BUILD_INFO, bitchat.service
#   /opt/bitchat/releases/current -> <release dir>             swapped atomically (ln + mv -T)
#   /opt/bitchat/bitchat.service                               sterling-owned copy that the
#                                                              sudoers rule lets us install
#
# <digest8> is the first 8 hex chars of sha256 over the SHA256SUMS lines minus the
# ./BUILD_INFO line, so a release name is a pure function of the shipped payload
# (executable, compose-resources/, unit) and an existing release directory is never
# rewritten with a different payload.
#
# Identity comes from the bitchat-embedded.build-info sidecar that every link task writes
# next to the executable (apps/embedded/build.gradle.kts). Its kexe_sha256 must match the
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
DEFAULT_HOST="sterling@192.168.4.58"
RELEASES="/opt/bitchat/releases"
RSYNC="${RSYNC:-rsync}"

usage() {
  cat <<USAGE
Usage: scripts/deploy-pi.sh [options]

Builds the linuxArm64 embedded binary, stages it with compose-resources/,
a SHA256SUMS manifest, BUILD_INFO and bitchat.service, uploads it to
\$PI_HOST:$RELEASES/<sha12>[-dirty]-<build>-<digest8>/,
verifies it on the device, swaps $RELEASES/current and
restarts bitchat.service.

Options:
  --release          link the release binary (default: debug)
  --debug            link the debug binary
  --no-build         reuse the existing link output (its build-info sidecar must match)
  --no-restart       upload, verify and switch, but do not install/restart the unit
  --dry-run          build and stage locally, print what would be uploaded, no ssh
  --host USER@HOST   target (default: \$PI_HOST or $DEFAULT_HOST)
  -h, --help

Env: PI_HOST (target), RSYNC (rsync binary, default: rsync).
USAGE
}
die() { echo "deploy-pi.sh: $*" >&2; exit 1; }
log() { echo "== $*"; }

# --- 1. options -------------------------------------------------------------
BUILD=debug; DO_BUILD=1; DO_RESTART=1; DRY_RUN=0; HOST="${PI_HOST:-$DEFAULT_HOST}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --release)    BUILD=release ;;
    --debug)      BUILD=debug ;;
    --no-build)   DO_BUILD=0 ;;
    --no-restart) DO_RESTART=0 ;;
    --dry-run)    DRY_RUN=1 ;;
    --host)       [[ $# -ge 2 ]] || { echo "deploy-pi.sh: --host needs USER@HOST" >&2; usage >&2; exit 2; }
                  HOST="$2"; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            echo "deploy-pi.sh: unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done
case "$BUILD" in debug) BUILD_CAP=Debug ;; release) BUILD_CAP=Release ;; esac
TASK="link${BUILD_CAP}ExecutableLinuxArm64"
OUT_DIR="$REPO/apps/embedded/build/bin/linuxArm64/${BUILD}Executable"
BINARY="$OUT_DIR/bitchat-embedded.kexe"
SIDECAR="$OUT_DIR/bitchat-embedded.build-info"
UNIT_FILE="$REPO/apps/embedded/systemd/bitchat.service"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=60)
# -n: ssh must never read this script's stdin. Only here, never in the rsync -e string.
remote() { ssh -n "${SSH_OPTS[@]}" "$HOST" "$@"; }
# ssh and rsync exit 255 when the transport itself failed, not the remote command.
# The optional third argument is appended to the message (used once current is swapped).
transport_check() { [[ "$1" != 255 ]] || die "ssh transport failure talking to $HOST ($2)${3:+; $3}"; }

# --- 2. build ---------------------------------------------------------------
if [[ "$DO_BUILD" == 1 ]]; then
  log "./gradlew -Pembedded.enabled=true :apps:embedded:$TASK --console=plain"
  ./gradlew -Pembedded.enabled=true ":apps:embedded:$TASK" --console=plain
fi

# --- 3. identity (from the sidecar the link task wrote next to the binary) --
[[ -f "$BINARY" ]]  || die "missing $BINARY; run without --no-build (or ./gradlew -Pembedded.enabled=true :apps:embedded:$TASK)"
[[ -f "$SIDECAR" ]] || die "no build-info sidecar next to the binary; run without --no-build"
[[ -d "$OUT_DIR/compose-resources" ]] || die "missing $OUT_DIR/compose-resources; the app resolves it beside the executable, relink with :apps:embedded:$TASK"
[[ -f "$UNIT_FILE" ]] || die "missing $UNIT_FILE"
field() { sed -n "s/^$1=//p" "$SIDECAR"; }
VERSION="$(field version)"; GIT_SHA="$(field git_sha)"; GIT_BRANCH="$(field git_branch)"
GIT_DIRTY="$(field git_dirty)"; BUILT_AT="$(field built_at)"; SIDECAR_BUILD="$(field build)"
KEXE_SHA256="$(field kexe_sha256)"; IDENTITY_EXPECTED="$(field identity)"
[[ -n "$VERSION" && -n "$GIT_SHA" && -n "$GIT_BRANCH" && -n "$GIT_DIRTY" && -n "$BUILT_AT" \
   && -n "$SIDECAR_BUILD" && -n "$KEXE_SHA256" && -n "$IDENTITY_EXPECTED" ]] \
  || die "could not parse version/git_sha/git_branch/git_dirty/built_at/build/kexe_sha256/identity from $SIDECAR"
[[ "$SIDECAR_BUILD" == "$BUILD" ]] \
  || die "sidecar says build=$SIDECAR_BUILD but --$BUILD was selected; relink with :apps:embedded:$TASK"
LOCAL_SHA256="$(shasum -a 256 "$BINARY" | cut -d' ' -f1)"
[[ "$LOCAL_SHA256" == "$KEXE_SHA256" ]] || die "sidecar does not match the executable; rebuild"
SHA12="${GIT_SHA:0:12}"
log "identity: $IDENTITY_EXPECTED"

# --- 4. stage the payload ---------------------------------------------------
TMP="${TMPDIR:-/tmp}"; TMP="${TMP%/}"
STAGE="$(mktemp -d "$TMP/bitchat-deploy.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
# -p keeps the link-time mtimes so rsync of an unchanged release is a no-op.
cp -p "$BINARY" "$STAGE/bitchat-embedded.kexe"
chmod 755 "$STAGE/bitchat-embedded.kexe"
cp -Rp "$OUT_DIR/compose-resources" "$STAGE/compose-resources"
cp -p "$UNIT_FILE" "$STAGE/bitchat.service"
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

# --- 9. verify on the device: checksums, then the exact identity line -------
log "verifying checksums and --version on $HOST"
rc=0; VERSION_OUT="$(remote "cd '$REMOTE_DIR' && sha256sum --quiet -c SHA256SUMS && ./bitchat-embedded.kexe --version")" || rc=$?
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

# --- 11. install unit, restart, check this invocation's journal -------------
STATE="not restarted (--no-restart)"
if [[ "$DO_RESTART" == 1 ]]; then
  log "installing bitchat.service and restarting"
  rc=0
  remote "cat '$REMOTE_DIR/bitchat.service' > /opt/bitchat/bitchat.service || exit 42
    sudo -n install -m 644 -o root -g root /opt/bitchat/bitchat.service /etc/systemd/system/bitchat.service \
      && sudo -n systemctl daemon-reload \
      && sudo -n systemctl enable bitchat.service \
      && sudo -n systemctl restart bitchat.service" || rc=$?
  transport_check "$rc" "install/restart" "$SWAPPED"
  if [[ "$rc" == 42 ]]; then
    die "/opt/bitchat/bitchat.service is missing or not writable by sterling; create it once with: sudo install -o sterling -g sterling -m 644 /dev/null /opt/bitchat/bitchat.service; $SWAPPED"
  elif [[ "$rc" != 0 ]]; then
    die "installing or restarting bitchat.service failed (exit $rc); $SWAPPED"
  fi

  # The InvocationID identifies exactly the process systemd just started, so the journal
  # check cannot match an older run of the same SHA.
  rc=0; INV="$(remote "systemctl show -p InvocationID --value bitchat.service")" || rc=$?
  transport_check "$rc" "reading InvocationID" "$SWAPPED"
  [[ "$rc" == 0 ]] || die "systemctl show -p InvocationID bitchat.service failed on $HOST (exit $rc, output '$INV'); check: sudo -n systemctl status bitchat.service; $SWAPPED"
  case "$INV" in
    "" | *[!0-9a-f]*) die "bitchat.service has no InvocationID after restart (got '$INV'); check: sudo -n systemctl status bitchat.service; $SWAPPED" ;;
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
  POLL_CMD="st=\$(systemctl is-active bitchat.service); rc1=\$?; echo \"\$st\"; echo \"__rc1=\$rc1\"
    inv=\$(systemctl show -p InvocationID --value bitchat.service); rc2=\$?; echo \"\$inv\"; echo \"__rc2=\$rc2\"
    echo __end_state
    journalctl _SYSTEMD_INVOCATION_ID='$INV' --no-pager -o cat"
  JOURNAL=""; OK=0; RESULT=""; SEEN=0; attempt=0; LIMIT=10
  while :; do
    attempt=$((attempt + 1))
    [[ "$attempt" == 1 ]] || sleep 2
    rc=0
    POLL_OUT="$(remote "$POLL_CMD")" || rc=$?
    transport_check "$rc" "polling bitchat.service" "$SWAPPED"
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
      *)          RESULT="bitchat.service is '$STATE' (expected active; is-active exit $RC1)"; break ;;
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
        RESULT="bitchat.service is '$STATE' but the journal for invocation $INV never showed the identity line ($attempt polls)"
      else
        RESULT="bitchat.service is '$STATE' after the identity line (seen on attempt $SEEN) but was not confirmed active"
      fi
      break
    fi
  done
  if [[ "$OK" != 1 ]]; then
    echo "--- journal for invocation $INV ---"
    printf '%s\n' "$JOURNAL"
    echo "--- journalctl -u bitchat.service -n 50 ---"
    remote "journalctl -u bitchat.service -n 50 --no-pager" || true
    die "${RESULT:-post-restart check failed}; $SWAPPED"
  fi
  echo "--- journal for invocation $INV (last 15 lines) ---"
  printf '%s\n' "$JOURNAL" | tail -n 15
fi

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
  echo "rollback: ssh $HOST \"ln -sfn '$PREVIOUS' '$RELEASES/current.tmp' && mv -T '$RELEASES/current.tmp' '$RELEASES/current' && sudo -n systemctl restart bitchat.service\""
fi
echo "identity: $IDENTITY"
echo "service:  $STATE"
