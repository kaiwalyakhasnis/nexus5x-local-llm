#!/bin/sh
# Test this project's ramdisk (busybox init, llmd, llama.cpp) on QEMU with the
# QEMU-variant 3.10 bullhead kernel from ~/bullhead-qemu (the trimmed phone kernel
# needs real Snapdragon hardware). That kernel has no virtio-net, so the model goes
# in as a read-only virtio disk that /etc/rc copies to /tmp/model.gguf.
# Usage: scripts/run-qemu.sh [model.gguf]     shell on this terminal; quit: Ctrl-A then X
P=$(cd "$(dirname "$0")/.." && pwd)
QK=${QK:-$HOME/bullhead-qemu/kobj/arch/arm64/boot/Image}
DISK= SIZE=
if [ -n "$1" ]; then
	DISK="-drive file=$1,if=none,format=raw,readonly=on,id=model -device virtio-blk-device,drive=model"
	SIZE="MODEL_SIZE=$(stat -L -c %s "$1")"
fi
exec qemu-system-aarch64 -M virt,gic-version=2,virtualization=on -cpu cortex-a57 \
	-smp "${SMP:-6}" -m "${MEM:-1800}" \
	-display none -serial null -monitor none \
	-chardev stdio,id=con,mux=on,signal=off \
	-device virtio-serial-device -device virtconsole,chardev=con \
	$DISK \
	-kernel "$QK" -initrd "$P/out/ramdisk.cpio.gz" \
	-append "console=hvc0 GLIBC_TUNABLES=glibc.pthread.rseq=0 $SIZE"
