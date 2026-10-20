#!/bin/sh
# Build the trimmed kernel from ./kernel (branch "local-llm") out-of-tree into ./out/kobj
# with GCC 4.9 (current GCCs can't build this 3.10 kernel).
# Usage: scripts/build-kernel.sh                 (incremental Image.gz-dtb)
#        scripts/build-kernel.sh menuconfig      (edit out/kobj/.config)
#        FRAG=config/stock.config KOBJ=out/kobj-stock scripts/build-kernel.sh   (baseline)
#        FRAG=config/local-llm.config KOBJ=out/kobj-noaudio scripts/build-kernel.sh   (no mic/speaker)
# The PC runs hot: default -j4, override with JOBS=n.
set -e
P=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$P/local.env" ] && . "$P/local.env"
# GCC 4.9 from AOSP (scripts/fetch-sources.sh), or the copy in a LineageOS 15.1 tree (LINEAGE=)
if [ -n "${LINEAGE:-}" ]; then
	KCROSS=${KCROSS:-$LINEAGE/prebuilts/gcc/linux-x86/aarch64/aarch64-linux-android-4.9/bin/aarch64-linux-android-}
else
	KCROSS=${KCROSS:-$P/out/toolchains/aarch64-linux-android-4.9/bin/aarch64-linux-android-}
fi
KOBJ=${KOBJ:-$P/out/kobj}
case $KOBJ in /*) ;; *) KOBJ=$P/$KOBJ ;; esac
FRAG=${FRAG:-config/local-llm.config config/audio.config}   # one or more fragments, merged in order
FRAGS=
for f in $FRAG; do case $f in /*) FRAGS="$FRAGS $f" ;; *) FRAGS="$FRAGS $P/$f" ;; esac; done
stale=0
[ -f "$KOBJ/.config" ] || stale=1
for f in $FRAGS; do [ "$f" -nt "$KOBJ/.config" ] && stale=1; done
[ -x "${KCROSS}gcc" ] || { echo "no kernel compiler at ${KCROSS}gcc (run scripts/fetch-sources.sh, or set LINEAGE= or KCROSS=)" >&2; exit 1; }
mkdir -p "$KOBJ"
MK="make -C $P/kernel O=$KOBJ ARCH=arm64 CROSS_COMPILE=$KCROSS"
# lineageos_bullhead_defconfig + config/local-llm.config -> out/kobj/.config (redone when the fragment changes)
if [ $stale = 1 ]; then
	$MK lineageos_bullhead_defconfig
	(cd "$KOBJ" && ARCH=arm64 "$P/kernel/scripts/kconfig/merge_config.sh" -m -O "$KOBJ" .config $FRAGS)
	$MK olddefconfig
fi
$MK -j"${JOBS:-4}" "${@:-Image.gz-dtb}"
