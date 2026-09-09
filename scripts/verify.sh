#!/usr/bin/env bash
# Verification gates for bitchatKmp.
#
#   scripts/verify.sh            # quick: domain tests + desktop compile
#   scripts/verify.sh desktop    # quick + packageDmg
#   scripts/verify.sh android    # :apps:droid:assembleDebug
#   scripts/verify.sh ios        # :iosdi debug frameworks for iosSimulatorArm64 and iosArm64 (the only iOS targets)
#   scripts/verify.sh embedded   # -Pembedded.enabled=true linuxArm64 link + compose resources
#   scripts/verify.sh full       # all of the above (desktop packaging included)
#
# Env: WARN=1 adds --warning-mode all; GRADLE_ARGS adds arbitrary flags.
# GRADLE_ARGS is word-split; values containing spaces are not supported.
#
# Packaging on a Homebrew JDK: Compose's packageDmg refuses Homebrew JDKs
# (compose-multiplatform#3107) because the bundle may depend on Homebrew libraries
# absent on other Macs. Prefer a Corretto/Temurin JDK 21 (JAVA_HOME). To build a
# local-only dmg anyway, opt out explicitly:
#   GRADLE_ARGS='-Pcompose.desktop.packaging.checkJdkVendor=false' scripts/verify.sh desktop
set -euo pipefail
SELF="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$0")/.."

MODE="${1:-quick}"
# Non-embedded modes pin the flag off so a ~/.gradle/gradle.properties override cannot
# silently change what is being verified.
BASE=(--console=plain ${GRADLE_ARGS:-})
[[ "${WARN:-0}" == "1" ]] && BASE+=(--warning-mode all)

gradle()          { echo "== ./gradlew ${BASE[*]} -Pembedded.enabled=false $*"; ./gradlew "${BASE[@]}" -Pembedded.enabled=false "$@"; }
gradle_embedded() { echo "== ./gradlew ${BASE[*]} -Pembedded.enabled=true $*";  ./gradlew "${BASE[@]}" -Pembedded.enabled=true "$@"; }

case "$MODE" in
  quick)    gradle :domain:jvmTest :apps:desktop:compileKotlin ;;
  desktop)  gradle :domain:jvmTest :apps:desktop:compileKotlin :apps:desktop:packageDmg ;;
  android)  gradle :apps:droid:assembleDebug ;;
  ios)      gradle :iosdi:linkDebugFrameworkIosSimulatorArm64 :iosdi:linkDebugFrameworkIosArm64 ;;
  embedded) gradle_embedded :apps:embedded:linkDebugExecutableLinuxArm64 ;;
  full)     for m in desktop android ios embedded; do "$SELF" "$m"; done ;;
  *) echo "usage: $0 [quick|desktop|android|ios|embedded|full]" >&2; exit 2 ;;
esac
echo "verify.sh $MODE: OK"
