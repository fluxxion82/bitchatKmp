#!/usr/bin/env bash
# Verification gates for bitchatKmp.
#
#   scripts/verify.sh            # quick: domain tests + desktop compile
#   scripts/verify.sh desktop    # quick + packageDmg + macOS arm64 BLE dylib link (on macOS arm64)
#   scripts/verify.sh android    # :apps:droid:assembleDebug
#   scripts/verify.sh ios        # :iosdi debug frameworks for iosSimulatorArm64 and iosArm64 (the only iOS targets)
#   scripts/verify.sh embedded   # -Pembedded.enabled=true linuxArm64 link + compose resources
#   scripts/verify.sh tui        # -Pembedded.enabled=true :presentation:tui JVM tests + linuxArm64 compile + TUI link
#   scripts/verify.sh desktop-tui # JVM desktop TUI tests and installDist
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
# Non-embedded modes pin the embedded and tui flags off so a ~/.gradle/gradle.properties override
# cannot silently change what is being verified; embedded modes pin tui off (the two exclude each other).
BASE=(--console=plain ${GRADLE_ARGS:-})
[[ "${WARN:-0}" == "1" ]] && BASE+=(--warning-mode all)

gradle()          { echo "== ./gradlew ${BASE[*]} -Pembedded.enabled=false -Ptui.enabled=false $*"; ./gradlew "${BASE[@]}" -Pembedded.enabled=false -Ptui.enabled=false "$@"; }
gradle_embedded() { echo "== ./gradlew ${BASE[*]} -Pembedded.enabled=true -Ptui.enabled=false $*";  ./gradlew "${BASE[@]}" -Pembedded.enabled=true -Ptui.enabled=false "$@"; }
gradle_tui()      { echo "== ./gradlew ${BASE[*]} -Pembedded.enabled=false -Ptui.enabled=true $*"; ./gradlew "${BASE[@]}" -Pembedded.enabled=false -Ptui.enabled=true "$@"; }

case "$MODE" in
  quick)    gradle :domain:jvmTest :apps:desktop:compileKotlin :apps:desktop-common:test ;;
  desktop)
    desktop_tasks=(:domain:jvmTest :apps:desktop:compileKotlin :apps:desktop-common:test :apps:desktop:packageDmg)
    if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]]; then
      desktop_tasks+=(:data:remote:transport:bluetooth:linkDebugSharedMacosArm64)
    fi
    gradle "${desktop_tasks[@]}"
    ;;
  android)  gradle :apps:droid:assembleDebug ;;
  ios)      gradle :iosdi:linkDebugFrameworkIosSimulatorArm64 :iosdi:linkDebugFrameworkIosArm64 :data:crypto:macosArm64Test :data:crypto:iosSimulatorArm64Test ;;
  embedded) gradle_embedded :apps:embedded:linkDebugExecutableLinuxArm64 ;;
  tui)      gradle_embedded :presentation:tui:jvmTest :presentation:tui:compileKotlinLinuxArm64 :apps:embedded-tui:linkDebugExecutableLinuxArm64 ;;
  desktop-tui)
    gradle_tui :presentation:tui:jvmTest :presentation:tui:binding:jvmTest :apps:desktop-common:test :apps:desktop-tui:test :apps:desktop-tui:installDist
    version_line="$(apps/desktop-tui/build/install/bitchat-tui/bin/bitchat-tui --version)"
    [[ "$version_line" == bitchat-tui\ * ]] || { echo "desktop-tui --version did not start with bitchat-tui: $version_line" >&2; exit 1; }
    ;;
  full)     for m in desktop android ios embedded tui desktop-tui; do "$SELF" "$m"; done ;;
  *) echo "usage: $0 [quick|desktop|android|ios|embedded|tui|desktop-tui|full] (desktop links the macOS arm64 BLE dylib on macOS arm64)" >&2; exit 2 ;;
esac
echo "verify.sh $MODE: OK"
