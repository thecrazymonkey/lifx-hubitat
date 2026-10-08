# LIFX LAN driver + manager app for Hubitat — design

## Goal
Control a LIFX bulb from Hubitat over the **local LAN protocol only** (UDP 56700). No cloud, no LIFX account.

## Deliverables
- `lifx-bulb-driver.groovy` — device driver (one device per bulb)
- `lifx-manager-app.groovy` — app that adds bulbs (broadcast discovery or manual IP) and maintains their IPs
- `README.md` — install + on-hub test checklist

## Acceptance criteria
1. App adds a bulb by **manual IP** (unicast, no broadcast) or by **broadcast discovery**; creates a child device per bulb (DNI = MAC, IP stored on the child).
2. Device: `on`, `off`.
3. Device: `setLevel` 0–100, `setColorTemperature` 1500–9000 K, `setColor` / `setHue` / `setSaturation`.
4. `refresh` and scheduled polling update `switch`, `level`, `hue`, `saturation`, `colorTemperature`, `colorMode` from the bulb's real state.
5. App can change a bulb's IP and re-scan (match by MAC, update IP).
6. No HTTP calls to LIFX cloud anywhere.
7. Out of scope: multizone/tile/matrix, infrared, waveforms, firmware, groups/locations.

Verification: cannot be run here (no hub/bulb). Code is written to the protocol spec; user runs the checklist in the README on their hub.

## Architecture
App creates/updates devices only. Each child driver sends UDP directly to its bulb's `ip:56700` (no proxying through the app).

### Protocol (LIFX LAN, little-endian)
36-byte header + payload. Header: `size`(u16) · `protocol`=1024/`addressable`=1/`tagged`/`origin`=0 (u16 bitfield at bytes 2–3) · `source`(u32) · `target`(6-byte MAC + 2 zero) · reserved(6) · flags byte 22 (`res_required` bit0, `ack_required` bit1) · `sequence`(u8) · reserved(8) · `type`(u16) · reserved(2).
Source: https://lan.developer.lifx.com/docs/packet-contents

| Purpose | Msg | Type | Payload |
|---|---|---|---|
| Discover / probe | GetService | 2 | none (`tagged`=1, target 0 for broadcast; unicast probe uses `tagged`=0) |
| Probe reply | StateService | 3 | service u8, port u32; header `target` = bulb MAC |
| Name | GetLabel / StateLabel | 23 / 25 | label 32 bytes |
| Power | SetPower | 21 | level u16 (0 / 65535), duration u32 ms |
| Color | SetColor | 102 | reserved u8, hue u16, sat u16, bri u16, kelvin u16, duration u32 ms |
| Poll | GetColor → StateLight | 101 → 107 | hue, sat, bri, kelvin (u16 each), reserved u16, power u16, label 32B, reserved 8B |

Scaling: Hubitat hue 0–100 ↔ 0–65535, sat 0–100 ↔ 0–65535, level 0–100 ↔ 0–65535. Kelvin sent as-is. When setting color temperature: sat=0, kelvin=value. `setLevel`/`setHue`/`setSaturation` read the cached HSBK, change one field, send `SetColor`.

### Hubitat transport
- Send: `hubitat.device.HubAction(hexString, Protocol.LAN, [type: LAN_TYPE_UDPCLIENT, destinationAddress: "ip:56700", encoding: HEX_STRING, ...])`.
- **Driver** receives replies in `parse(description)` (matched to the device by DNI/IP).
- **App** must pass `callback: "<method>"` on the HubAction, otherwise the reply is discarded (Hubitat HubAction docs: https://docs2.hubitat.com/developer/hubaction-object). Broadcast discovery replies in the app are the least-verified piece; README test #1 covers it, and manual-IP is the fallback path.
- The driver's DNI is the MAC; replies are also matched on header `target` MAC so a reused IP can't update the wrong device.

### App
- Main page: list of managed bulbs (label, MAC, IP).
- "Add / update by IP": user enters an IP → unicast GetService → reply header gives the MAC; if the MAC already belongs to a child its IP is updated, otherwise GetLabel is requested and the child is created (`addChildDevice`, DNI = uppercase MAC, IP set via the driver's `setIp`). Invalid IP → message, nothing sent. No reply in 8 s → "no response".
- "Discover": broadcast GetService (default = hub /24 `.255`, editable) for ~6 s, list unmanaged bulbs, user picks which to add. Managed bulbs found at a new IP get their IP updated (this is the "re-scan").
- Removal is done from the Hubitat device page (no app button).

### Driver
- Capabilities: Switch, SwitchLevel, ColorControl, ColorTemperature, ColorMode, Refresh, Initialize.
- Preferences: IP (kept in sync with app), poll interval (off/1/5/15/30 min, default 1), default transition time (ms, default 500), debug logging (auto-off after 30 min).
- Commands send the action, then a `GetColor` ~300 ms later to confirm state.
- Sequence counter and `source` id are per-device state; `source` is a random value ≥ 2 (0 makes bulbs broadcast replies). `res_required=1` on Get only; a Set's State reply can carry pre-change values, so the follow-up `GetColor` confirms state.
- No reply to a poll: log a warning; `presence`-style attribute `healthStatus` set to offline after 3 missed polls.

## Error handling
- Invalid IP (not dotted quad) rejected in app UI.
- Malformed/short UDP replies ignored with a debug log.
- Out-of-range values are clamped.

## Testing (on hub, in README)
1. Add bulb by manual IP → child appears with correct label/MAC.
2. Add bulb via Discover.
3. on/off from device page; toggle the bulb from the LIFX app, `refresh`, confirm state follows.
4. setLevel 10/100, setColorTemperature 2700/6500, setColor red/blue; confirm bulb and attributes.
5. Change the bulb's IP in the router, run Re-scan, confirm child IP updates.
6. Unplug bulb, confirm warning logs and offline status; plug in, confirm recovery.

Pure helpers (header build/parse, HSBK scaling) are kept as standalone functions so they can be unit-checked with plain Groovy outside Hubitat if desired.
