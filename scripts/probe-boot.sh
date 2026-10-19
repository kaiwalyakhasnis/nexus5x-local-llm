#!/bin/sh
# Boot a kernel with the probe ramdisk and report whether it reached userspace.
# Usage: scripts/probe-boot.sh out/kobj-bisect      (phone in fastboot or Android)
# OK   = the probe rebooted into fastboot (phone is left there)
# HANG = the apps watchdog reset it into Android
P=$(cd "$(dirname "$0")/.." && pwd)
KOBJ=$(cd "${1:-$P/out/kobj}" && pwd)
KOBJ=$KOBJ "$P/scripts/build-bootimg.sh" probe >/dev/null || exit 1
if ! timeout 2 fastboot devices | grep -q .; then
	adb reboot bootloader
	for i in $(seq 1 60); do timeout 2 fastboot devices | grep -q . && break; sleep 1; done
fi
timeout 60 fastboot boot "$P/out/probe-boot.img" >/dev/null 2>&1 || { echo "fastboot boot failed"; exit 1; }
sleep 8
for i in $(seq 1 90); do
	timeout 2 fastboot devices | grep -q . && { echo "OK: userspace reached ($KOBJ)"; exit 0; }
	lsusb | grep -q 18d1:4ee7 && { echo "HANG: Android booted instead ($KOBJ)"; exit 2; }
	sleep 1
done
echo "UNKNOWN: neither fastboot nor Android after 100 s"; exit 3
