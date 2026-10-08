#!/usr/bin/env bash
# Builds dist/lifx-lan-bundle.zip for Hubitat Bundle Manager (Bundles -> Import).
# Format: install.txt/update.txt = namespace, bundle name, then "<library|driver|app> <file>" (libraries first).
set -euo pipefail
cd "$(dirname "$0")"
out=dist/lifx-lan-bundle.zip
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
cp lib/lifx-protocol.groovy drivers/lifx-bulb-driver.groovy apps/lifx-manager-app.groovy "$stage"/
printf '%s\n' ivank "LIFX LAN" \
  "library lifx-protocol.groovy" "driver lifx-bulb-driver.groovy" "app lifx-manager-app.groovy" > "$stage/install.txt"
cp "$stage/install.txt" "$stage/update.txt"
mkdir -p dist && rm -f "$out"
(cd "$stage" && zip -q -X "$OLDPWD/$out" install.txt update.txt *.groovy)
unzip -l "$out"
