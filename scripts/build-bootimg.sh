#!/bin/sh
# Pack out/boot.img: trimmed kernel (out/kobj) + ramdisk (rootfs/ + busybox + llama.cpp).
# Boot it without flashing: fastboot boot out/boot.img
# scripts/build-bootimg.sh probe: out/probe-boot.img, a boot probe (tools/probe/init)
# scripts/build-bootimg.sh ramdisk: only out/ramdisk.cpio.gz (for scripts/run-qemu.sh)
set -e
P=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$P/local.env" ] && . "$P/local.env"
KOBJ=${KOBJ:-$P/out/kobj}
case $KOBJ in /*) ;; *) KOBJ=$P/$KOBJ ;; esac
S=$P/out/ramdisk

rm -rf "$S" && mkdir -p "$S/bin"
if [ "$1" = probe ]; then
	# boot probe: busybox + llm-reboot + tools/probe/init only -> out/probe-boot.img
	OUT=$P/out/probe-boot.img
	cp "$P/out/busybox/busybox" "$S/bin/busybox"
	aarch64-linux-gnu-gcc -O2 -static -s -o "$S/bin/llm-reboot" "$P/tools/llm-reboot.c"
	cp "$P/tools/probe/init" "$S/init" && chmod 755 "$S/init"
else
	OUT=${BOOTIMG:-$P/out/boot.img}
	cp -a "$P/rootfs/." "$S/"
	cp "$P/out/busybox/busybox" "$S/bin/busybox"
	ln -s bin/busybox "$S/init"
	cp "$P/out/llama/bin/llama" "$S/bin/"
	make -s -C "$P/ui" >/dev/null && cp "$P/out/ui/llm-ui" "$S/bin/"
	aarch64-linux-gnu-gcc -O2 -static -s -o "$S/bin/llm-reboot" "$P/tools/llm-reboot.c"
	aarch64-linux-gnu-gcc -O2 -static -s -o "$S/bin/gguf-name" "$P/tools/gguf-name.c"
	chmod 755 "$S/bin/"* "$S/etc/rc" "$S/etc/"*.sh
fi
(cd "$S" && find . | LC_ALL=C sort | cpio -o -H newc -R 0:0 --quiet) > "$P/out/ramdisk.cpio"
if [ "$1" = ramdisk ]; then   # QEMU test needs only this
	gzip -9 -c "$P/out/ramdisk.cpio" > "$P/out/ramdisk.cpio.gz"
	ls -la "$P/out/ramdisk.cpio.gz"; exit 0
fi
# LZMA (CONFIG_RD_LZMA) packs ~25% smaller than gzip: it keeps the image under
# the size where fastboot boot falls back to Android.
RD=$P/out/ramdisk.cpio.lzma
lzma -9 -c "$P/out/ramdisk.cpio" > "$RD"

# Stock bullhead cmdline minus the UART console and the Android-only bits.
# GLIBC_TUNABLES ends up in /init's environment: glibc >= 2.35 registers rseq
# (syscall 293) in every process, which this 3.10 kernel lacks.
CMDLINE="boot_cpus=0-5 lpm_levels.sleep_disabled=1 msm_poweroff.download_mode=0 GLIBC_TUNABLES=glibc.pthread.rseq=0"
python3 "$P/scripts/mkbootimg.py" \
	--kernel "$KOBJ/arch/arm64/boot/Image.gz-dtb" \
	--ramdisk "$RD" \
	--cmdline "$CMDLINE" \
	--base 0x00000000 --pagesize 4096 \
	--ramdisk_offset 0x02000000 --tags_offset 0x01E00000 \
	--output "$OUT"
ls -la "$KOBJ/arch/arm64/boot/Image.gz-dtb" "$RD" "$OUT"
# fastboot boot of a 16.2 MB image fell back to Android (boot reason kernel_panic); 13 MB worked.
[ "$(stat -c %s "$OUT")" -lt 15000000 ] || echo "WARNING: boot.img over ~15 MB, fastboot boot may fall back to Android"
