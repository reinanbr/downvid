#!/usr/bin/env bash
# Builds the Go core (go/dvcore) as libdvcore.so for every Android ABI and
# drops the results into android/app/src/main/jniLibs/<abi>/.
#
# Usage: scripts/build_go.sh [abi ...]   (default: arm64-v8a armeabi-v7a x86_64)
# Env:   ANDROID_NDK_HOME  NDK root (default: newest under $ANDROID_HOME/ndk)
#        ANDROID_API       min API level for the NDK clang (default: 24)
#        DV_VERSION        version string embedded in the library
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_SRC="$ROOT/go"
OUT_DIR="$ROOT/android/app/src/main/jniLibs"
API="${ANDROID_API:-24}"
VERSION="${DV_VERSION:-$(git -C "$ROOT" describe --tags --always 2>/dev/null || echo 0.1.0-dev)}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  # Prefer the NDK the Flutter Gradle plugin uses; fall back to the newest.
  if [[ -d "$SDK/ndk/28.2.13676358" ]]; then
    ANDROID_NDK_HOME="$SDK/ndk/28.2.13676358"
  else
    ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
[[ -d "${ANDROID_NDK_HOME:-}" ]] || { echo "NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }

case "$(uname -s)" in
  Linux)  HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;  # NDK ships universal binaries under this tag
  *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"

ABIS=("$@")
[[ ${#ABIS[@]} -gt 0 ]] || ABIS=(arm64-v8a armeabi-v7a x86_64)

echo "NDK:     $ANDROID_NDK_HOME"
echo "API:     $API"
echo "Version: $VERSION"

for abi in "${ABIS[@]}"; do
  unset GOARM
  case "$abi" in
    arm64-v8a)   GOARCH=arm64; CC_TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) GOARCH=arm;   CC_TRIPLE=armv7a-linux-androideabi; export GOARM=7 ;;
    x86_64)      GOARCH=amd64; CC_TRIPLE=x86_64-linux-android ;;
    x86)         GOARCH=386;   CC_TRIPLE=i686-linux-android ;;
    *) echo "unknown ABI: $abi" >&2; exit 1 ;;
  esac

  mkdir -p "$OUT_DIR/$abi"
  echo "==> $abi (GOARCH=$GOARCH)"
  (
    cd "$GO_SRC"
    # 16 KB page alignment is required for Android 15+ devices.
    CGO_ENABLED=1 GOOS=android GOARCH=$GOARCH \
    CC="$TOOLCHAIN/${CC_TRIPLE}${API}-clang" \
    CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
    go build -trimpath -buildmode=c-shared \
      -ldflags "-s -w -X main.Version=$VERSION" \
      -o "$OUT_DIR/$abi/libdvcore.so" ./dvcore
  )
  # The generated header is not used (ffigen reads go/include/dvcore.h).
  rm -f "$OUT_DIR/$abi/libdvcore.h"
  ls -lh "$OUT_DIR/$abi/libdvcore.so" | awk '{print "    " $5 "  " $9}'
done
