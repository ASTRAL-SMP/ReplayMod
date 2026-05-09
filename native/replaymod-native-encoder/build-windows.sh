#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build/windows"
mkdir -p "$OUT"

# Prefer the newest nv-codec-headers checkout we can find. NVENC API ≥ 12 is
# required to address Blackwell (RTX 50 series) drivers, which reject older
# NVENCAPI_VERSION values with NV_ENC_ERR_INVALID_PARAM during initialization.
DEFAULT_FFNV_CODEC_HEADERS=
for candidate in \
    /tmp/nv-codec-headers-13/include \
    /tmp/nv-codec-headers-12/include \
    /mnt/wslg/distro/tmp/nv-codec-headers-12/include \
    /tmp/nv-codec-headers-11/include \
    /mnt/wslg/distro/tmp/nv-codec-headers-11/include \
    /tmp/nv-codec-headers/include \
    /mnt/wslg/distro/tmp/nv-codec-headers/include
do
  if [[ -f "$candidate/ffnvcodec/nvEncodeAPI.h" ]]; then
    DEFAULT_FFNV_CODEC_HEADERS="$candidate"
    break
  fi
done

: "${FFNV_CODEC_HEADERS:=$DEFAULT_FFNV_CODEC_HEADERS}"
: "${JAVA_INCLUDE:=${JAVA_HOME:-}/include}"
: "${ZIG:=zig}"

if [[ -z "$FFNV_CODEC_HEADERS" || ! -f "$FFNV_CODEC_HEADERS/ffnvcodec/nvEncodeAPI.h" ]]; then
  echo "FFNV_CODEC_HEADERS must point to nv-codec-headers/include" >&2
  exit 1
fi

if [[ -z "$JAVA_INCLUDE" || ! -f "$JAVA_INCLUDE/jni.h" ]]; then
  echo "JAVA_INCLUDE must point to a JDK include directory" >&2
  exit 1
fi

if ! command -v "$ZIG" >/dev/null 2>&1; then
  echo "zig is required (set ZIG=path/to/zig). On Nix, run inside \`nix develop\`." >&2
  exit 1
fi

# Why zig instead of mingw-w64 GCC: nixpkgs builds its mingw-w64 GCC with
# `--enable-threads=mcf`, leaving libstdc++ — and therefore every cross-built
# C++ DLL — with a runtime dependency on `mcfgthread-12.dll`. That DLL is not
# included in any Windows release; on the user's PC LoadLibrary fails with
# "Can't find dependent libraries". `zig c++` ships its own self-contained
# libc++ and links against UCRT (`api-ms-win-crt-*`), which is included in
# Windows 10/11 by default. The output DLL therefore has no extra runtime
# dependency and loads on stock Windows.
"$ZIG" c++ -target x86_64-windows-gnu \
  -std=c++17 -O2 -DNOMINMAX -DWIN32_LEAN_AND_MEAN -shared \
  -I"$FFNV_CODEC_HEADERS" \
  -I"$JAVA_INCLUDE" \
  -I"$JAVA_INCLUDE/linux" \
  "$ROOT/replaymod_native_encoder.cpp" \
  -o "$OUT/replaymod_native_encoder.dll"

ls -lh "$OUT/replaymod_native_encoder.dll"
