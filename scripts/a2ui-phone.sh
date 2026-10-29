#!/bin/sh
# Run the a2ui screen on the phone in place of llm-ui, in RAM only (a reboot
# brings llm-ui back): copies out/a2ui/ (scripts/build-a2ui.sh) over, puts a
# small /bin/llm-ui that starts it (the original is kept as /bin/llm-ui.real),
# and restarts the screen. Run it again after a rebuild.
# Usage: scripts/a2ui-phone.sh          start (or update) a2ui
#        scripts/a2ui-phone.sh --back   back to llm-ui
set -eu
P=$(cd "$(dirname "$0")/.." && pwd)
O=$P/out/a2ui
phone() { "$P/scripts/phone.py" "$1" > /dev/null; }
if [ "${1:-}" = --back ]; then
	phone '[ -e /bin/llm-ui.real ] && mv /bin/llm-ui.real /bin/llm-ui; kill $(pidof a2ui) 2>/dev/null; true'
	echo "back to llm-ui"
	exit 0
fi
[ -f "$O/a2ui" ] || { echo "no $O/a2ui: run scripts/build-a2ui.sh first"; exit 1; }
phone 'mkdir -p /res/images /res/fonts /dev/graphics; ln -sf /dev/fb0 /dev/graphics/fb0'
"$P/scripts/push.sh" "$O/a2ui" /tmp/a2ui 755
"$P/scripts/push.sh" "$O/font.png" /res/images/font.png
for f in Roboto-Regular.ttf Roboto-Medium.ttf; do "$P/scripts/push.sh" "$O/$f" "/res/fonts/$f"; done
# The screen program is started (and restarted) as /bin/llm-ui. EC_DENSITY:
# the panel's 420 dpi; GLIBC_TUNABLES: glibc's rseq, which the 3.10 kernel
# lacks. The backlight stays off after the panel's unblank until written again.
phone "[ -e /bin/llm-ui.real ] || mv /bin/llm-ui /bin/llm-ui.real; printf '%s\\n' '#!/bin/sh' \
'export EC_DENSITY=2.625 GLIBC_TUNABLES=glibc.pthread.rseq=0' \
'(sleep 3; echo 0 > /sys/class/leds/lcd-backlight/brightness; echo 80 > /sys/class/leds/lcd-backlight/brightness) &' \
'exec /tmp/a2ui > /tmp/a2ui.log 2>&1' > /bin/llm-ui; chmod 755 /bin/llm-ui; kill \$(pidof llm-ui a2ui) 2>/dev/null; true"
sleep 4
"$P/scripts/phone.py" 'pidof a2ui > /dev/null && echo "a2ui running (log: /tmp/a2ui.log)" || echo "a2ui did not start: cat /tmp/a2ui.log"' | tail -1
