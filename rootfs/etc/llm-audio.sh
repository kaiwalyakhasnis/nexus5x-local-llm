#!/bin/sh
# Bring up the microphone and speaker (kernel built with config/audio.config):
#  - the phone's own `vendor` partition, mounted read-only (ext4 with noload:
#    not even a journal replay writes to it), holds the firmware of the ADSP
#    (Qualcomm's audio DSP) in /vendor/firmware; the kernel loads it from /lib/firmware
#  - writing 1 to /sys/kernel/boot_adsp/boot boots the ADSP (PIL), as Android does
#  - the sound card appears once the ADSP is up; then the mixer defaults from
#    Android's mixer_paths.xml are set (routes: /etc/audio/route-*.sh)
# Run by llmd once the voice pack (/tmp/voice: tinymix, whisper-cli, espeak-ng)
# has arrived. Prints "llm-audio: ready" (and creates /run/audio-ready) or why not.
export PATH=/tmp/voice/bin:$PATH
[ -e /run/audio-ready ] && exit 0
B=/dev/block/bootdevice/by-name
A=/etc/audio
log() { echo "llm-audio: $*"; echo "llm-audio: $*" > /dev/kmsg; }

[ -e /sys/kernel/boot_adsp/boot ] || { log "kernel without audio support"; exit 1; }
mkdir -p $B /vendor /lib
for p in /sys/block/mmcblk0/mmcblk0p*; do
	n=$(sed -n 's/^PARTNAME=//p' $p/uevent)
	[ -n "$n" ] && ln -sf /dev/${p##*/} $B/$n
done
grep -q " /vendor " /proc/mounts ||
	mount -t ext4 -o ro,noload $B/vendor /vendor || { log "cannot mount the vendor partition"; exit 1; }
ln -sfn /vendor/firmware /lib/firmware

echo 1 > /sys/kernel/boot_adsp/boot
state=
for i in $(seq 1 60); do
	for d in /sys/bus/msm_subsys/devices/*; do
		[ "$(cat $d/name 2>/dev/null)" = adsp ] && state=$(cat $d/state)
	done
	[ "$state" = ONLINE ] && break
	sleep 0.5
done
[ "$state" = ONLINE ] || { log "ADSP not online (state: ${state:-none})"; exit 1; }
log "ADSP online"

for i in $(seq 1 40); do
	grep -q "\[" /proc/asound/cards 2>/dev/null && break
	sleep 0.5
done
grep -q "\[" /proc/asound/cards 2>/dev/null || { log "no sound card"; exit 1; }
log "sound card: $(sed -n 's/^ *0 \[\([^]]*\)\].*/\1/p' /proc/asound/cards)"

sh $A/mixer-defaults.sh > /tmp/mixer-defaults.log 2>&1
log "ready ($(grep -c -i error /tmp/mixer-defaults.log) mixer errors)"
touch /run/audio-ready
