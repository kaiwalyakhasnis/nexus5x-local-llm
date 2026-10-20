#!/bin/sh
# Fetch everything the build needs into kernel/ and out/ (both untracked):
#   kernel/                 LineageOS's bullhead kernel (lineage-15.1, 3.10) + patches/ on branch local-llm
#   out/src/llama.cpp       llama.cpp v0.7.0
#   out/src/tinyalsa        tinyalsa v2.0.0 + patches/tinyalsa (llm-ui's voice chat)
#   out/src/whisper.cpp     whisper.cpp v1.9.5 (voice pack: speech -> text)
#   out/src/espeak-ng       espeak-ng 1.52.0 (voice pack: text -> speech)
#   out/busybox/busybox     Ubuntu's static arm64 busybox 1.36.1
#   out/venv                cmake + ninja for the llama.cpp build
#   out/toolchains/...      GCC 4.9 for the kernel, from AOSP (skipped with LINEAGE= in local.env)
# Safe to re-run: what is already there is kept.
set -e
P=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$P/local.env" ] && . "$P/local.env"
cd "$P"
KERNEL_BASE=65a266ae3d341b04bcf70250c7fa1f28cf687425   # lineage-15.1 head (android-8.1.0_r0.122)
BUSYBOX_DEB=busybox-static_1.36.1-6ubuntu3_arm64.deb

if [ ! -d kernel ]; then
	git clone --depth 1 -b lineage-15.1 https://github.com/LineageOS/android_kernel_lge_bullhead kernel
	[ "$(git -C kernel rev-parse HEAD)" = $KERNEL_BASE ] ||
		echo "note: lineage-15.1 moved past $KERNEL_BASE; the patches may need a look"
	git -C kernel checkout -q -b local-llm
	git -C kernel am -q "$P"/patches/*.patch
fi

[ -d out/src/llama.cpp ] ||
	git clone --depth 1 --branch v0.7.0 https://github.com/ggml-org/llama.cpp out/src/llama.cpp
if [ ! -d out/src/tinyalsa ]; then
	git clone --depth 1 --branch v2.0.0 https://github.com/tinyalsa/tinyalsa out/src/tinyalsa
	git -C out/src/tinyalsa apply "$P"/patches/tinyalsa/*.patch
fi
[ -d out/src/whisper.cpp ] ||
	git clone --depth 1 --branch v1.9.5 https://github.com/ggml-org/whisper.cpp out/src/whisper.cpp
[ -d out/src/espeak-ng ] ||
	git clone --depth 1 --branch 1.52.0 https://github.com/espeak-ng/espeak-ng out/src/espeak-ng

if [ ! -x out/busybox/busybox ]; then
	mkdir -p out/downloads out/busybox
	[ -f out/downloads/$BUSYBOX_DEB ] ||
		curl -fo out/downloads/$BUSYBOX_DEB http://ports.ubuntu.com/pool/main/b/busybox/$BUSYBOX_DEB
	rm -rf out/busybox/deb && mkdir out/busybox/deb
	(cd out/busybox/deb && ar x "$P/out/downloads/$BUSYBOX_DEB" && tar xf data.tar.*)
	cp out/busybox/deb/usr/bin/busybox out/busybox/busybox
	rm -rf out/busybox/deb
fi

if [ ! -x out/venv/bin/cmake ]; then
	python3 -m venv out/venv
	out/venv/bin/pip -q install cmake ninja
fi

if [ -z "${LINEAGE:-}" ] && [ -z "${KCROSS:-}" ] &&
   [ ! -x out/toolchains/aarch64-linux-android-4.9/bin/aarch64-linux-android-gcc ]; then
	mkdir -p out/toolchains
	git clone --depth 1 -b oreo-mr1-release \
		https://android.googlesource.com/platform/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9 \
		out/toolchains/aarch64-linux-android-4.9
fi
echo "sources ready"
