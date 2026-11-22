#!/usr/bin/env bash
# Builds the ad blocker (adblock/, Brave's adblock-rust) as libdvadblock.so
# for every Android ABI and drops the results into
# android/app/src/main/jniLibs/<abi>/.
#
# Usage: scripts/build_rust.sh [abi ...]   (default: arm64-v8a armeabi-v7a x86_64)
# Env:   ANDROID_NDK_HOME  NDK root (default: 28.2.13676358 or the newest under $ANDROID_HOME/ndk)
#        ANDROID_API       min API level for the NDK clang (default: 24)
# Needs: rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/adblock"
OUT_DIR="$ROOT/android/app/src/main/jniLibs"
API="${ANDROID_API:-24}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  if [[ -d "$SDK/ndk/28.2.13676358" ]]; then
    ANDROID_NDK_HOME="$SDK/ndk/28.2.13676358"
  else
    ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  fi
fi
[[ -d "${ANDROID_NDK_HOME:-}" ]] || { echo "NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }

case "$(uname -s)" in
  Linux)  HOST_TAG=linux-x86_64 ;;
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *) echo "unsupported host: $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"

ABIS=("$@")
[[ ${#ABIS[@]} -gt 0 ]] || ABIS=(arm64-v8a armeabi-v7a x86_64)

for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a)   TARGET=aarch64-linux-android;   CC_TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) TARGET=armv7-linux-androideabi; CC_TRIPLE=armv7a-linux-androideabi ;;
    x86_64)      TARGET=x86_64-linux-android;    CC_TRIPLE=x86_64-linux-android ;;
    *) echo "unknown ABI: $abi" >&2; exit 1 ;;
  esac
  VAR="$(echo "$TARGET" | tr 'a-z-' 'A-Z_')"
  echo "==> $abi ($TARGET)"
  # 16 KB page alignment is required for Android 15+ devices.
  env "CARGO_TARGET_${VAR}_LINKER=$TOOLCHAIN/${CC_TRIPLE}${API}-clang" \
      "CARGO_TARGET_${VAR}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
      "CC_${TARGET//-/_}=$TOOLCHAIN/${CC_TRIPLE}${API}-clang" \
      "AR_${TARGET//-/_}=$TOOLCHAIN/llvm-ar" \
      cargo build --manifest-path "$SRC/Cargo.toml" --release --lib --locked --target "$TARGET"
  mkdir -p "$OUT_DIR/$abi"
  cp "$SRC/target/$TARGET/release/libdvadblock.so" "$OUT_DIR/$abi/libdvadblock.so"
  ls -la "$OUT_DIR/$abi/libdvadblock.so" | awk '{printf "    %.1fM  %s\n", $5/1048576, $9}'
done
