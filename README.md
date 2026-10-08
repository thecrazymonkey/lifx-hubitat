# LIFX LAN for Hubitat

Local-only (UDP 56700) control of LIFX bulbs: on/off, level, color, color temperature, polling. No cloud, no account.

## Install
### Option A: bundle (easiest)
**Bundles → Import** → upload `dist/lifx-lan-bundle.zip` (installs library, driver and app in one go), then **Apps → Add user app → LIFX LAN Manager**. Rebuild with `./build-bundle.sh`. *The zip layout follows the community description of Hubitat bundles and is untested on a hub; if import fails, use option B.*

### Option B: manual (order matters)
1. **Developer tools → Libraries code → New Library** → paste `lib/lifx-protocol.groovy` → Save.
2. **Drivers code → New Driver** → paste `drivers/lifx-bulb-driver.groovy` → Save.
3. **Apps code → New App** → paste `apps/lifx-manager-app.groovy` → Save.
4. **Apps → Add user app → LIFX LAN Manager**.

Give each bulb a DHCP reservation in your router. If it moves anyway, enter its new IP under *Add / update bulb by IP* (or run *Discover*): the app matches by MAC and updates the device.

## Develop / test off-hub
Needs Groovy 3/4 (`brew install groovy`, or prefix with `mise x groovy@4 --`).
```
groovy -cp test/stubs test/lifx-protocol-test.groovy lib/lifx-protocol.groovy   # protocol unit tests
groovy -cp test/stubs test/parse-check.groovy                                   # syntax check of all sources
```
`test/stubs` holds minimal stand-ins for `hubitat.device.*` so the sources compile off-hub.

## On-hub test checklist
Turn on **Debug logging** in the app and the device first.
1. **Add by IP.** App → Add / update bulb by IP → enter IP → Probe. Expect "Added <label> at <ip>" within a few seconds and a new child device. *If nothing happens, open Logs: the app logs `ignored unparseable reply: ...` with the raw reply — this is where the app-callback reply shape would need adjusting in `lifxCallback`.*
2. **Discover.** Remove the child, then Discover → Scan; the bulb should appear; Add selected. (If broadcast replies don't reach the app on your hub, use test 1 instead.)
3. **On/off.** Device page → On / Off. Toggle the bulb from the LIFX app, press Refresh, confirm `switch` follows.
4. **Level/color.** setLevel 10 and 100; setColorTemperature 2700 and 6500; setColor red (hue 0, sat 100) and blue (hue 66, sat 100). Confirm the bulb and the attributes (`level`, `hue`, `saturation`, `colorTemperature`, `colorMode`).
5. **IP change.** Change the bulb's IP in the router; run Discover or Add-by-IP with the new address; confirm the device's IP preference updated and commands work.
6. **Offline.** Unplug the bulb; within ~3 poll intervals `healthStatus` → offline and a warning is logged. Plug in; it returns to online.
7. **Driver log check.** With debug on, a command should log `tx type 102 -> <ip>` and then a `state:` line from the reply. If you see `ignored unparseable packet`, the `parseLanMessage` payload format differs on your firmware — report the logged description.

## Known limits
ASCII labels only. Assumes a /24 LAN for the default broadcast address (editable on the Discover page). Single-zone bulbs only.
