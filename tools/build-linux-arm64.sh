#!/usr/bin/env bash
# Builds CustomHUD on the linux-aarch64 dev container with the CarLight toolchain
# (/config/carlight-toolchain). local.properties holds the Mac SDK path and takes precedence
# over ANDROID_HOME, so the build runs in a copy with its own local.properties.
# Output APKs are copied back to app/build/outputs/apk/linux/ (unsigned / debug-signed).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${TOOLCHAIN:-/config/carlight-toolchain}"
WORK="${WORK:-${TMPDIR:-/tmp}/customhud-linux-build}"

# shellcheck disable=SC1091
source "$TOOLCHAIN/env.sh"
mkdir -p "$WORK"
rm -rf "$WORK/app/src"
(cd "$ROOT" && tar --exclude='./.gradle' --exclude='./.kotlin' --exclude='*/build' --exclude='./reference' \
    --exclude='./local.properties' -cf - app/src app/build.gradle.kts app/proguard-rules.pro build.gradle.kts \
    settings.gradle.kts gradle.properties gradle gradlew) | tar -xf - -C "$WORK"
echo "sdk.dir=$ANDROID_HOME" > "$WORK/local.properties"

cd "$WORK"
if [ "$#" -eq 0 ]; then set -- testDebugUnitTest lintDebug assembleDebug assembleRelease; fi
./gradlew --no-daemon --offline "$@"

OUT="$ROOT/app/build/outputs/apk/linux"
mkdir -p "$OUT"
cp app/build/outputs/apk/debug/*.apk app/build/outputs/apk/release/*.apk "$OUT/" 2>/dev/null || true
ls -l "$OUT"
