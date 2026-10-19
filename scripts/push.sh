#!/bin/sh
# Copy a file to the running phone (RAM only; gone after reboot) over a one-shot nc.
# Usage: scripts/push.sh local-file /phone/path [mode]
#   e.g. scripts/push.sh rootfs/bin/llm-serve /bin/llm-serve 755
set -e
P=$(cd "$(dirname "$0")/.." && pwd)
PHONE=${PHONE:-172.16.42.1}
[ -f "$1" ] && [ -n "$2" ] || { echo "usage: $0 local-file /phone/path [mode]" >&2; exit 1; }
"$P/scripts/phone.py" "setsid sh -c 'nc -l -p 9001 > $2.part && mv $2.part $2 && chmod ${3:-644} $2' </dev/null >/dev/null 2>&1 & true" >/dev/null
sleep 1
nc -N "$PHONE" 9001 < "$1"
sleep 1
want=$(md5sum < "$1" | cut -d' ' -f1)
got=$("$P/scripts/phone.py" "md5sum < $2" | grep -oE '^[0-9a-f]{32}' | tail -1)
[ "$want" = "$got" ] && echo "pushed $1 -> $2" || { echo "checksum mismatch for $2 ($got != $want)" >&2; exit 1; }
