#!/bin/sh
# Build a config fragment from some sections of config/local-llm.config, for
# bisecting boot hangs. Sections are the "# --- name" blocks, numbered from 1.
# Usage: scripts/bisect-config.sh            list the sections
#        scripts/bisect-config.sh 1 4 6      write out/bisect.config with those
#        scripts/bisect-config.sh 1 4 CONFIG_X   ...plus "# CONFIG_X is not set"
P=$(cd "$(dirname "$0")/.." && pwd)
F=$P/config/local-llm.config
if [ $# -eq 0 ]; then
	grep -n '^# --- ' "$F" | awk -F'# --- ' '{n++; print n": "$2}'
	exit 0
fi
awk -v want=" $(echo "$*" | tr -s " " | sed "s/CONFIG_[A-Z0-9_]*//g") " '
	/^# --- / { n++; keep = index(want, " " n " ") > 0; if (keep) print; next }
	n == 0 || keep' "$F" > "$P/out/bisect.config"
for a in "$@"; do
	case "$a" in CONFIG_*) echo "# $a is not set" >> "$P/out/bisect.config" ;; esac
done
grep -c '^# CONFIG\|^CONFIG' "$P/out/bisect.config" | sed 's/$/ options in out\/bisect.config/'
