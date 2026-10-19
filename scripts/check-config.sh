#!/bin/sh
# Report config/local-llm.config lines that Kconfig overrode in out/kobj/.config
# (an option selected by something still enabled, or a missing dependency).
P=$(cd "$(dirname "$0")/.." && pwd)
CFG=${1:-$P/out/kobj/.config}
grep -E '^(# )?CONFIG_' "$P/config/local-llm.config" | while read -r l; do
	s=$(echo "$l" | grep -oE 'CONFIG_[A-Z0-9_]+')
	case "$l" in *"is not set") want=n ;; *) want=${l#*=} ;; esac
	have=$(grep -E "^$s=" "$CFG" | cut -d= -f2)
	[ "$want" = "${have:-n}" ] || echo "$s: wanted $want, got ${have:-n}"
done
