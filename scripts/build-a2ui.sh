#!/bin/sh
# Build the a2ui screen (a2ui/: an embedded-compose app, drawn with
# embedded-compose-a2ui) for the Nexus 5X. Needs checkouts of
# github.com/kaiwalyakhasnis/embedded-compose and .../embedded-compose-a2ui,
# next to this repo by default; or in local.env: EMBEDDED_COMPOSE=<dir>,
# EC_A2UI=<dir>. Kotlin/Native and the JDK come with embedded-compose's ./ec.
# Output in out/a2ui/: a2ui (the binary), Roboto-*.ttf (for /res/fonts) and
# font.png (minui's font, for /res/images). scripts/a2ui-phone.sh runs it.
set -eu
P=$(cd "$(dirname "$0")/.." && pwd)
[ -f "$P/local.env" ] && . "$P/local.env"
EC=${EMBEDDED_COMPOSE:-$P/../embedded-compose}
[ -x "$EC/ec" ] || { echo "no embedded-compose checkout at $EC (set EMBEDDED_COMPOSE in local.env)"; exit 1; }
[ -n "${EC_A2UI:-}" ] && export EC_A2UI
APP_DIR="$P/a2ui" "$EC/ec" binary nexus5x
O=$P/out/a2ui
mkdir -p "$O"
cp "$EC/out/nexus5x-a2ui/native/embedded-compose" "$O/a2ui"
cp "$EC/third_party/aosp/external/roboto-fonts/Roboto-Regular.ttf" "$EC/third_party/aosp/external/roboto-fonts/Roboto-Medium.ttf" "$O/"
cp "$EC/third_party/aosp/bootable/recovery/fonts/18x32.png" "$O/font.png"
echo "built $O/a2ui ($(wc -c < "$O/a2ui") bytes)"
