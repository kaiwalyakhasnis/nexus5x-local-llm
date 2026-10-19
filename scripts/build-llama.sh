#!/bin/sh
# Cross-build llama.cpp for the Nexus 5X: static aarch64 glibc binaries, CPU backend only.
# Snapdragon 808 = 2x Cortex-A57 + 4x Cortex-A53, ARMv8.0-A (NEON, crc, crypto; no dotprod/fp16 math).
# Output: out/llama/bin/llama (stripped), the unified binary: llama serve|cli|bench|completion|...
# One binary instead of four keeps the ramdisk ~6 MB smaller (shared code).
set -eu
P=$(cd "$(dirname "$0")/.." && pwd)
SRC=$P/out/src/llama.cpp
B=$P/out/llama-build
JOBS=${JOBS:-4}
CMAKE=$P/out/venv/bin/cmake
export PATH="$P/out/venv/bin:$PATH"

[ -d "$SRC" ] || { echo "missing $SRC (scripts/fetch-sources.sh)"; exit 1; }

"$CMAKE" -S "$SRC" -B "$B" -G Ninja \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
	-DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
	-DCMAKE_C_FLAGS="-mcpu=cortex-a57" -DCMAKE_CXX_FLAGS="-mcpu=cortex-a57" \
	-DCMAKE_EXE_LINKER_FLAGS="-static" \
	-DBUILD_SHARED_LIBS=OFF -DGGML_STATIC=ON \
	-DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
	-DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
	-DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=ON
"$CMAKE" --build "$B" -j "$JOBS" --target llama-app

mkdir -p "$P/out/llama/bin"
aarch64-linux-gnu-strip -o "$P/out/llama/bin/llama" "$B/bin/llama"
ls -la "$P/out/llama/bin"
