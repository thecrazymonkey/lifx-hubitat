# LIFX Hubitat LAN Driver + Manager App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Control a LIFX bulb from Hubitat over the local LAN UDP protocol only (on/off, poll state, color, color temperature, level), with an app that adds bulbs by manual IP or broadcast discovery.

**Architecture:** A shared Hubitat *library* (`ivank.LifxProtocol`) holds all pure protocol code (packet build/parse, scaling, hex helpers, HubAction factory). The *driver* (one device per bulb) and the *app* (adds/updates bulbs, creates child devices) both `#include` it. The driver talks UDP directly to its bulb; the app never proxies control traffic.

**Tech Stack:** Hubitat Groovy (driver/app/library), LIFX LAN protocol over UDP 56700, plain Groovy 4 + `GroovyShell` for off-hub unit tests.

**Spec:** `docs/superpowers/specs/2026-10-07-lifx-hubitat-design.md`

**Notes on this directory:** `/Users/ivan/git/lifx` is not a git repository, so there are no commit steps. Each task ends with a green test run instead.

**Deviation from the spec (approved design simplification):** There is no separate "Change IP" page or "Remove" button. *Adding by IP an address whose MAC already belongs to a child updates that child's IP*; "Discover" also updates IPs of existing children found at a new address (this is the "Re-scan"). Removing a bulb is done from the Hubitat device page. Edit the spec's "App" section to match (Task 4, Step 3).

## Global Constraints

- LAN protocol only: UDP port `56700`; no HTTP calls to LIFX cloud anywhere.
- LIFX header is 36 bytes, little-endian; `protocol`=1024, `addressable`=1, `origin`=0.
- Message types: GetService=2, StateService=3, GetLabel=23, StateLabel=25, SetPower=21, StatePower=22, GetColor=101, SetColor=102, StateLight=107.
- Scaling: Hubitat hue/saturation/level 0–100 ↔ LIFX 0–65535. Kelvin clamp 1500–9000, sent as-is.
- Driver DNI = bulb MAC, uppercase hex, no separators; MACs are compared case-insensitively.
- Driver capabilities: Switch, SwitchLevel, ColorControl, ColorTemperature, ColorMode, Refresh, Initialize.
- Namespace `ivank`; library name `LifxProtocol`; driver name `LIFX LAN Bulb`; app name `LIFX LAN Manager`.
- Out of scope: multizone/tile/matrix, infrared, waveforms, firmware, groups/locations.
- Can't run a hub or bulb here: automated tests cover pure helpers + Groovy parse of driver/app; the rest is the README on-hub checklist.

## Review Focus

1. Truncated / garbage / odd-length hex from the network → `lifxParsePacket` returns `null`, never throws (Task 1 tests).
2. `size` header field larger than the data actually received → `null` (Task 1 tests).
3. Out-of-range input (level 150, -5, kelvin 100, 20000) → clamped, not wrapped into 16-bit garbage (Task 1 tests).
4. Bulb label padded with NUL bytes / empty label → trimmed string, fallback name `LIFX <last 6 of MAC>` (Task 1 tests + app fallback).
5. Invalid IP typed in the app (`256.1.1.1`, `abc`, `1.2.3`, empty) → rejected with a message, nothing sent (Task 1 tests + Task 3 app).

---

## File Structure

| File | Responsibility |
|---|---|
| `lib/lifx-protocol.groovy` | Hubitat library `ivank.LifxProtocol`: pure helpers + `lifxHubAction` |
| `test/lifx-protocol-test.groovy` | Off-hub unit tests for the library |
| `test/parse-check.groovy` | Verifies driver/app/library are syntactically valid Groovy |
| `drivers/lifx-bulb-driver.groovy` | Device driver |
| `apps/lifx-manager-app.groovy` | Manager app |
| `README.md` | Install steps + on-hub test checklist |

---

### Task 1: Protocol library (pure helpers) with tests

**Files:**
- Create: `lib/lifx-protocol.groovy`
- Create: `test/lifx-protocol-test.groovy`

**Interfaces:**
- Produces (all top-level methods in the library; later tasks call them by these exact names/signatures):
  - `Integer lifxClamp(def v, int lo, int hi)`
  - `String lifxHexLE(long value, int bytes)` — little-endian hex
  - `long lifxReadLE(String hex, int byteOffset, int bytes)`
  - `Integer lifxPctToRaw(def pct)` / `Integer lifxRawToPct(def raw)`
  - `Integer lifxClampKelvin(def k)`
  - `String lifxBuildPacket(int type, Map opts = [:])` — opts: `mac` (12 hex chars or null), `source` (long), `sequence` (int), `tagged` (bool), `resRequired` (bool), `payload` (hex string)
  - `Map lifxParsePacket(String hex)` → `[size, source, mac (lowercase), sequence, type, payload]` or `null`
  - `String lifxPayloadSetPower(boolean on, long durationMs)`
  - `String lifxPayloadSetColor(Map hsbk, long durationMs)` — `hsbk` keys `h,s,b,k` raw ints
  - `Map lifxParseStateLight(String payload)` → `[h, s, b, k, on, label]` raw ints / boolean / String, or `null`
  - `Map lifxParseStateService(String payload)` → `[service, port]` or `null`
  - `String lifxParseLabel(String payload)` — StateLabel payload → string
  - `Boolean lifxParseStatePower(String payload)` → on? or `null`
  - `Boolean lifxIsValidIp(String s)`
  - `String lifxHexToIp(String hex)` — `"C0A80105"` → `"192.168.1.5"`
  - `String lifxBroadcastFor(String ip)` — `"192.168.1.5"` → `"192.168.1.255"`
  - `def lifxHubAction(String hex, String ip, String callback = null)` — builds the UDP `HubAction` (not unit-tested; needs Hubitat)

- [ ] **Step 1: Install Groovy (once)**

Run: `brew install groovy` (or `mise use -g groovy@4`)
Run: `groovy --version`
Expected: prints `Groovy Version: 4.x` (any 3.x/4.x works).

- [ ] **Step 2: Write the failing tests**

Create `test/lifx-protocol-test.groovy`:

```groovy
// Run: groovy test/lifx-protocol-test.groovy lib/lifx-protocol.groovy
// Loads the Hubitat library as a plain script (the library(...) metadata call is stripped).
String libPath = args ? args[0] : "lib/lifx-protocol.groovy"
String src = new File(libPath).text.replaceFirst(/(?s)library\s*\(.*?\)\s*\n/, "")
def lib = new GroovyShell().parse(src)

int passed = 0
def check = { String name, Closure body ->
    try { body(); passed++ } catch (Throwable t) { println "FAIL: ${name}\n  ${t.message}"; System.exit(1) }
}

check("hexLE little endian") {
    assert lib.lifxHexLE(0x0102, 2) == "0201"
    assert lib.lifxHexLE(65535, 2) == "ffff"
    assert lib.lifxHexLE(500, 4) == "f4010000"
    assert lib.lifxHexLE(0, 1) == "00"
}
check("readLE") {
    assert lib.lifxReadLE("0201", 0, 2) == 0x0102
    assert lib.lifxReadLE("00f4010000", 1, 4) == 500
}
check("pct <-> raw roundtrip for 0..100") {
    (0..100).each { assert lib.lifxRawToPct(lib.lifxPctToRaw(it)) == it }
    assert lib.lifxPctToRaw(0) == 0
    assert lib.lifxPctToRaw(100) == 65535
    assert lib.lifxPctToRaw(50) == 32768
    assert lib.lifxRawToPct(65535) == 100
}
check("clamping (review focus 3)") {
    assert lib.lifxPctToRaw(150) == 65535
    assert lib.lifxPctToRaw(-5) == 0
    assert lib.lifxPctToRaw(null) == 0
    assert lib.lifxClampKelvin(100) == 1500
    assert lib.lifxClampKelvin(20000) == 9000
    assert lib.lifxClampKelvin(2700) == 2700
}
check("GetService broadcast matches LIFX documented bytes") {
    String expected = "24000034" + "00000000" + ("00" * 8) + ("00" * 6) + "00" + "00" + ("00" * 8) + "0200" + "0000"
    assert lib.lifxBuildPacket(2, [tagged: true, source: 0, sequence: 0]) == expected
    assert expected.length() == 72
}
check("unicast packet: tagged=0, mac, res_required, sequence, payload") {
    String p = lib.lifxBuildPacket(21, [mac: "D073D5001234", source: 1, sequence: 7, resRequired: true,
                                       payload: lib.lifxPayloadSetPower(true, 500)])
    assert p.startsWith("2a00" + "0014" + "01000000" + "d073d5001234" + "0000")
    assert p.substring(44, 46) == "01"        // flags byte 22: res_required
    assert p.substring(46, 48) == "07"        // sequence byte 23
    assert p.substring(64, 68) == "1500"      // type 21
    assert p.endsWith("ffff" + "f4010000")
}
check("parsePacket roundtrip") {
    String p = lib.lifxBuildPacket(102, [mac: "D073D5001234", source: 99, sequence: 5, payload: "aabb"])
    Map r = lib.lifxParsePacket(p)
    assert r.type == 102 && r.mac == "d073d5001234" && r.source == 99 && r.sequence == 5
    assert r.size == 38 && r.payload == "aabb"
}
check("parsePacket rejects bad input (review focus 1, 2)") {
    assert lib.lifxParsePacket(null) == null
    assert lib.lifxParsePacket("") == null
    assert lib.lifxParsePacket("zz" * 40) == null
    assert lib.lifxParsePacket("2400") == null
    String full = lib.lifxBuildPacket(3, [payload: "01" + "7cdd0000"])
    assert lib.lifxParsePacket(full.substring(0, full.length() - 2)) == null   // size says more than we have
    assert lib.lifxParsePacket(full + "f") == null                             // odd length
}
check("SetColor payload") {
    String pl = lib.lifxPayloadSetColor([h: 0x1234, s: 0xffff, b: 0x8000, k: 2700], 1000)
    assert pl == "00" + "3412" + "ffff" + "0080" + "8c0a" + "e8030000"
    assert pl.length() == 26
}
check("StateLight parse + label NUL trimming (review focus 4)") {
    String label = "Desk".bytes.encodeHex().toString().padRight(64, "0")
    String pl = "3412" + "ffff" + "0080" + "8c0a" + "0000" + "ffff" + label + ("00" * 8)
    assert pl.length() == 104
    Map s = lib.lifxParseStateLight(pl)
    assert s.h == 0x1234 && s.s == 65535 && s.b == 0x8000 && s.k == 2700 && s.on == true && s.label == "Desk"
    assert lib.lifxParseStateLight(pl.replace("ffff" + label, "0000" + label)).on == false
    assert lib.lifxParseStateLight("0011") == null
    assert lib.lifxParseStateLight(pl.substring(0, 24) + ("00" * 32) + ("00" * 8)).label == ""
}
check("StateService / StateLabel / StatePower") {
    assert lib.lifxParseStateService("01" + "7cdd0000") == [service: 1, port: 56700]
    assert lib.lifxParseStateService("01") == null
    assert lib.lifxParseLabel("4b69746368656e" + ("00" * 25)) == "Kitchen"
    assert lib.lifxParseStatePower("ffff") == true
    assert lib.lifxParseStatePower("0000") == false
    assert lib.lifxParseStatePower("") == null
}
check("IP helpers (review focus 5)") {
    assert lib.lifxIsValidIp("192.168.1.5")
    ["256.1.1.1", "abc", "1.2.3", "", null, "1.2.3.4.5", " 1.2.3.4"].each { assert !lib.lifxIsValidIp(it), "should reject '${it}'" }
    assert lib.lifxHexToIp("C0A80105") == "192.168.1.5"
    assert lib.lifxHexToIp("zz") == null
    assert lib.lifxBroadcastFor("192.168.1.5") == "192.168.1.255"
}

println "OK: ${passed} checks passed"
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `groovy test/lifx-protocol-test.groovy lib/lifx-protocol.groovy`
Expected: FAIL — `FileNotFoundException: lib/lifx-protocol.groovy` (library not written yet).

- [ ] **Step 4: Write the library**

Create `lib/lifx-protocol.groovy`:

```groovy
library(
    author: "ivan",
    category: "utility",
    description: "LIFX LAN protocol helpers",
    name: "LifxProtocol",
    namespace: "ivank",
    documentationLink: ""
)

// ---- numeric / hex helpers ------------------------------------------------

Integer lifxClamp(def v, int lo, int hi) {
    int n = (v == null) ? lo : (v as int)
    return Math.max(lo, Math.min(hi, n))
}

String lifxHexLE(long value, int bytes) {
    return (0..<bytes).collect { int i -> String.format("%02x", (value >> (8 * i)) & 0xFF) }.join()
}

long lifxReadLE(String hex, int byteOffset, int bytes) {
    long v = 0
    for (int i = bytes - 1; i >= 0; i--) {
        int pos = (byteOffset + i) * 2
        v = (v << 8) | Integer.parseInt(hex.substring(pos, pos + 2), 16)
    }
    return v
}

// Integer-only rounding: avoids BigDecimal surprises from Groovy's `/`.
Integer lifxPctToRaw(def pct) {
    int p = lifxClamp(pct, 0, 100)
    return (p * 65535 + 50).intdiv(100) as int
}

Integer lifxRawToPct(def raw) {
    int r = lifxClamp(raw, 0, 65535)
    return (r * 100 + 32767).intdiv(65535) as int
}

Integer lifxClampKelvin(def k) {
    return lifxClamp(k, 1500, 9000)
}

// ---- packets ----------------------------------------------------------------

String lifxBuildPacket(int type, Map opts = [:]) {
    String payload = opts.payload ?: ""
    int size = 36 + payload.length().intdiv(2)
    int tagged = opts.tagged ? 1 : 0
    int protoField = 1024 | (1 << 12) | (tagged << 13)      // protocol | addressable | tagged | origin=0
    String mac = ((opts.mac ?: "000000000000") as String).toLowerCase()
    int flags = opts.resRequired ? 1 : 0                    // bit0 res_required; bit1 ack_required unused
    long source = (opts.source ?: 0) as long
    int sequence = (opts.sequence ?: 0) as int

    return lifxHexLE(size, 2) + lifxHexLE(protoField, 2) + lifxHexLE(source, 4) +
           mac + "0000" +                                   // target: 6-byte serial + 2 zero bytes
           "000000000000" +                                 // reserved (6)
           lifxHexLE(flags, 1) + lifxHexLE(sequence, 1) +
           "0000000000000000" +                             // reserved (8)
           lifxHexLE(type, 2) + "0000" +                    // type + reserved (2)
           payload
}

Map lifxParsePacket(String hex) {
    if (!hex || hex.length() < 72 || hex.length() % 2 != 0 || !(hex ==~ /(?i)[0-9a-f]+/)) return null
    int size = lifxReadLE(hex, 0, 2) as int
    if (size < 36 || hex.length() < size * 2) return null
    return [
        size    : size,
        source  : lifxReadLE(hex, 4, 4),
        mac     : hex.substring(16, 28).toLowerCase(),
        sequence: lifxReadLE(hex, 23, 1) as int,
        type    : lifxReadLE(hex, 32, 2) as int,
        payload : hex.substring(72, size * 2)
    ]
}

// ---- payloads ---------------------------------------------------------------

String lifxPayloadSetPower(boolean on, long durationMs) {
    return lifxHexLE(on ? 65535 : 0, 2) + lifxHexLE(durationMs, 4)
}

String lifxPayloadSetColor(Map hsbk, long durationMs) {
    return "00" + lifxHexLE(hsbk.h, 2) + lifxHexLE(hsbk.s, 2) + lifxHexLE(hsbk.b, 2) +
           lifxHexLE(hsbk.k, 2) + lifxHexLE(durationMs, 4)
}

private String lifxHexToString(String hex) {
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i + 1 < hex.length(); i += 2) {
        int c = Integer.parseInt(hex.substring(i, i + 2), 16)
        if (c == 0) break
        sb.append((char) c)                                  // ASCII labels only
    }
    return sb.toString()
}

Map lifxParseStateLight(String payload) {
    if (!payload || payload.length() < 104) return null      // 52 bytes
    return [
        h    : lifxReadLE(payload, 0, 2) as int,
        s    : lifxReadLE(payload, 2, 2) as int,
        b    : lifxReadLE(payload, 4, 2) as int,
        k    : lifxReadLE(payload, 6, 2) as int,
        on   : lifxReadLE(payload, 10, 2) != 0,
        label: lifxHexToString(payload.substring(24, 88))
    ]
}

Map lifxParseStateService(String payload) {
    if (!payload || payload.length() < 10) return null
    return [service: lifxReadLE(payload, 0, 1) as int, port: lifxReadLE(payload, 1, 4) as int]
}

String lifxParseLabel(String payload) {
    return payload ? lifxHexToString(payload) : ""
}

Boolean lifxParseStatePower(String payload) {
    if (!payload || payload.length() < 4) return null
    return lifxReadLE(payload, 0, 2) != 0
}

// ---- network helpers --------------------------------------------------------

Boolean lifxIsValidIp(String s) {
    if (!s || !(s ==~ /\d{1,3}(\.\d{1,3}){3}/)) return false
    return s.split(/\./).every { (it as int) <= 255 }
}

String lifxHexToIp(String hex) {
    if (!hex || hex.length() != 8 || !(hex ==~ /(?i)[0-9a-f]{8}/)) return null
    return (0..3).collect { Integer.parseInt(hex.substring(it * 2, it * 2 + 2), 16) }.join(".")
}

String lifxBroadcastFor(String ip) {
    return ip.substring(0, ip.lastIndexOf(".")) + ".255"     // assumes a /24 LAN
}

// Needs Hubitat at runtime; not unit-tested. `callback` is required for apps, ignored by drivers.
def lifxHubAction(String hex, String ip, String callback = null) {
    Map opts = [
        type              : hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
        destinationAddress: "${ip}:56700".toString(),
        encoding          : hubitat.device.HubAction.Encoding.HEX_STRING
    ]
    if (callback) opts.callback = callback
    return new hubitat.device.HubAction(hex, hubitat.device.Protocol.LAN, opts)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `groovy test/lifx-protocol-test.groovy lib/lifx-protocol.groovy`
Expected: `OK: 12 checks passed`. If a check fails, fix the library (not the test) unless the test's expected bytes contradict the packet layout in the spec.

---

### Task 2: Device driver

**Files:**
- Create: `drivers/lifx-bulb-driver.groovy`
- Create: `test/parse-check.groovy`

**Interfaces:**
- Consumes: every `lifx*` helper from Task 1 (exact names above).
- Produces: custom command `setIp(String ip)` — the app (Task 3) calls `child.setIp(ip)`; device command `refresh()`, `initialize()`; device DNI = uppercase MAC.

- [ ] **Step 1: Write the parse check (failing: files missing)**

Create `test/parse-check.groovy`:

```groovy
// Run: groovy test/parse-check.groovy
// Confirms each Hubitat source file is syntactically valid Groovy. Hubitat-only classes
// (hubitat.device.*) resolve at runtime, so parsing is enough to catch typos/brace errors.
def files = ["lib/lifx-protocol.groovy", "drivers/lifx-bulb-driver.groovy", "apps/lifx-manager-app.groovy"]
files.each { path ->
    String src = new File(path).text.readLines().collect { it.startsWith("#include") ? "" : it }.join("\n")
    try { new GroovyShell().parse(src, new File(path).name); println "parsed ${path}" }
    catch (Throwable t) { println "FAIL ${path}: ${t.message}"; System.exit(1) }
}
```

Run: `groovy test/parse-check.groovy`
Expected: FAIL on `drivers/lifx-bulb-driver.groovy` (file not found).

- [ ] **Step 2: Write the driver**

Create `drivers/lifx-bulb-driver.groovy`:

```groovy
#include ivank.LifxProtocol

metadata {
    definition(name: "LIFX LAN Bulb", namespace: "ivank", author: "ivan") {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"
        capability "Refresh"
        capability "Initialize"

        attribute "healthStatus", "enum", ["online", "offline"]

        command "setIp", [[name: "IP address*", type: "STRING"]]
    }
    preferences {
        input name: "ip", type: "text", title: "Bulb IP address", required: true
        input name: "pollMinutes", type: "enum", title: "Poll interval",
              options: ["0": "Off", "1": "1 minute", "5": "5 minutes", "15": "15 minutes", "30": "30 minutes"],
              defaultValue: "1"
        input name: "transitionMs", type: "number", title: "Default transition (ms)", defaultValue: 500
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: true
    }
}

// ---- lifecycle ---------------------------------------------------------------

void installed() { initialize() }

void updated() {
    if (settings.logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    unschedule("poll")
    Integer n = (settings.pollMinutes ?: "1") as int
    if (n > 0) schedule("0 */${n} * * * ?", "poll")
    state.missed = 0
    refresh()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void debug(String msg) { if (settings.logEnable) log.debug "${device.displayName}: ${msg}" }

void setIp(String newIp) {
    if (!lifxIsValidIp(newIp)) { log.warn "${device.displayName}: ignoring invalid IP '${newIp}'"; return }
    device.updateSetting("ip", [value: newIp, type: "text"])
    debug "IP set to ${newIp}"
    runInMillis(200, "refresh")
}

// ---- commands -------------------------------------------------------------------

void on()  { sendPower(true) }
void off() { sendPower(false) }

void setLevel(level, duration = null) {
    int pct = lifxClamp(level, 0, 100)
    if (pct == 0) { off(); return }
    Map c = hsbk(); c.b = lifxPctToRaw(pct)
    sendColor(c, toMs(duration), true)
}

void setColor(Map m) {
    Map c = hsbk()
    if (m.hue != null)        c.h = lifxPctToRaw(m.hue)
    if (m.saturation != null) c.s = lifxPctToRaw(m.saturation)
    if (m.level != null)      c.b = lifxPctToRaw(m.level)
    sendColor(c, toMs(null), true)
}

void setHue(hue) {
    Map c = hsbk(); c.h = lifxPctToRaw(hue)
    sendColor(c, toMs(null), true)
}

void setSaturation(saturation) {
    Map c = hsbk(); c.s = lifxPctToRaw(saturation)
    sendColor(c, toMs(null), true)
}

void setColorTemperature(kelvin, level = null, transitionTime = null) {
    Map c = hsbk()
    c.k = lifxClampKelvin(kelvin)
    c.s = 0
    if (level != null) c.b = lifxPctToRaw(level)
    sendColor(c, toMs(transitionTime), true)
}

void refresh() {
    send(101, "")                                   // GetColor -> StateLight
}

void poll() {
    state.missed = (state.missed ?: 0) + 1
    if (state.missed >= 3 && device.currentValue("healthStatus") != "offline") {
        log.warn "${device.displayName}: no reply to ${state.missed} polls, marking offline"
        sendEvent(name: "healthStatus", value: "offline")
    }
    refresh()
}

// ---- sending ----------------------------------------------------------------------

private Map hsbk() {
    Map s = state.hsbk ?: [h: 0, s: 0, b: 65535, k: 3500]
    return [h: s.h as int, s: s.s as int, b: s.b as int, k: s.k as int]
}

private int toMs(def seconds) {
    return (seconds != null) ? (int) (seconds * 1000) : ((settings.transitionMs ?: 500) as int)
}

private void sendPower(boolean on) {
    send(21, lifxPayloadSetPower(on, toMs(null)))
    runInMillis(toMs(null) + 300, "refresh")
}

private void sendColor(Map c, int ms, boolean turnOn) {
    state.hsbk = c
    send(102, lifxPayloadSetColor(c, ms))
    if (turnOn) send(21, lifxPayloadSetPower(true, ms), 150)
    runInMillis(ms + 300, "refresh")
}

// Builds the packet now (so the sequence number is fixed) and sends immediately or after delayMs.
private void send(int type, String payload, int delayMs = 0) {
    if (!lifxIsValidIp(settings.ip)) { log.warn "${device.displayName}: set a valid IP address in preferences"; return }
    state.seq = ((state.seq ?: 0) + 1) % 256
    String hex = lifxBuildPacket(type, [mac: device.deviceNetworkId, sequence: state.seq, resRequired: true, payload: payload])
    debug "tx type ${type} -> ${settings.ip}"
    if (delayMs > 0) runInMillis(delayMs, "sendPacket", [data: [hex: hex], overwrite: false])
    else sendPacket([hex: hex])
}

void sendPacket(Map data) {
    sendHubCommand(lifxHubAction(data.hex, settings.ip))
}

// ---- receiving ---------------------------------------------------------------------

void parse(String description) {
    Map msg = parseLanMessage(description)
    Map pkt = lifxParsePacket(msg?.payload as String)
    if (!pkt) { debug "ignored unparseable packet: ${description}"; return }
    if (!pkt.mac.equalsIgnoreCase(device.deviceNetworkId)) { debug "ignored packet from ${pkt.mac}"; return }

    switch (pkt.type) {
        case 107:                                               // StateLight
            Map s = lifxParseStateLight(pkt.payload)
            if (s) applyState(s)
            break
        case 22:                                                // StatePower
            Boolean on = lifxParseStatePower(pkt.payload)
            if (on != null) { markOnline(); sendEvent(name: "switch", value: on ? "on" : "off") }
            break
        default:
            debug "rx type ${pkt.type} (ignored)"
    }
}

private void markOnline() {
    state.missed = 0
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online")
}

private void applyState(Map s) {
    markOnline()
    state.hsbk = [h: s.h, s: s.s, b: s.b, k: s.k]
    int sat = lifxRawToPct(s.s)
    sendEvent(name: "switch", value: s.on ? "on" : "off")
    sendEvent(name: "level", value: lifxRawToPct(s.b), unit: "%")
    sendEvent(name: "hue", value: lifxRawToPct(s.h))
    sendEvent(name: "saturation", value: sat, unit: "%")
    sendEvent(name: "colorTemperature", value: s.k, unit: "K")
    sendEvent(name: "colorMode", value: (s.s == 0) ? "CT" : "RGB")
    debug "state: ${s}"
}
```

- [ ] **Step 3: Run parse check on library + driver**

Run: `groovy test/parse-check.groovy`
Expected: `parsed lib/lifx-protocol.groovy`, `parsed drivers/lifx-bulb-driver.groovy`, then FAIL on the app file (not written until Task 3). That is the expected stopping point for this task; the driver line must read `parsed`.

- [ ] **Step 4: Re-run unit tests (regression)**

Run: `groovy test/lifx-protocol-test.groovy lib/lifx-protocol.groovy`
Expected: `OK: 12 checks passed`

---

### Task 3: Manager app

**Files:**
- Create: `apps/lifx-manager-app.groovy`

**Interfaces:**
- Consumes: Task 1 helpers; driver `setIp(String)`, `refresh()`; driver type `ivank` / `LIFX LAN Bulb`.
- Produces: child devices with DNI = uppercase MAC.

Flow: every probe starts as a **GetService** (type 2). On StateService (3) we learn MAC and source IP (`msg.ip`, hex). `handleService(mac, ip)`:
- MAC already a child → update its IP if different (`child.setIp`).
- else IP is in `state.autoAdd` (user typed it) → send unicast **GetLabel** (23); on StateLabel (25) create the child.
- else (discovery) → record in `state.found`; user picks bulbs to add on the Discover page; label requested too so it can be shown.

- [ ] **Step 1: Write the app**

Create `apps/lifx-manager-app.groovy`:

```groovy
#include ivank.LifxProtocol

definition(
    name: "LIFX LAN Manager",
    namespace: "ivank",
    author: "ivan",
    description: "Add LIFX bulbs by IP or discovery and manage their IP addresses (LAN protocol only)",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "addByIpPage")
    page(name: "discoverPage")
}

// ---- pages ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "LIFX LAN Manager", install: true, uninstall: true) {
        section("Managed bulbs") {
            List kids = getChildDevices()
            if (kids) kids.each { paragraph "<b>${it.displayName}</b> &mdash; ${it.deviceNetworkId} &mdash; ${it.getSetting('ip') ?: 'no IP'}" }
            else paragraph "No bulbs yet."
        }
        section("Add or update") {
            href "addByIpPage", title: "Add / update bulb by IP address", description: "No broadcast needed. Re-entering an existing bulb's new IP updates it."
            href "discoverPage", title: "Discover bulbs (UDP broadcast)", description: "Also updates IPs of bulbs that moved."
        }
        section("Logging") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
        }
    }
}

def addByIpPage() {
    dynamicPage(name: "addByIpPage", title: "Add / update bulb by IP", refreshInterval: state.probeUntil && now() < state.probeUntil ? 3 : 0) {
        section {
            input "manualIp", "text", title: "Bulb IP address", required: false
            input "probeIp", "button", title: "Probe"
            if (state.probeMessage) paragraph state.probeMessage
        }
    }
}

def discoverPage() {
    boolean scanning = state.scanUntil && now() < state.scanUntil
    dynamicPage(name: "discoverPage", title: "Discover bulbs", refreshInterval: scanning ? 3 : 0) {
        section {
            input "broadcastAddr", "text", title: "Broadcast address", defaultValue: defaultBroadcast(), required: true
            input "scan", "button", title: scanning ? "Scanning..." : "Scan"
        }
        section("Found (not yet added)") {
            Map options = unmanagedFound().collectEntries { mac, f -> [(mac): "${f.label ?: 'LIFX ' + mac[-6..-1]} (${f.ip})"] }
            if (options) {
                input "toAdd", "enum", title: "Select bulbs to add", options: options, multiple: true
                input "addSelected", "button", title: "Add selected"
            } else paragraph scanning ? "Waiting for replies..." : "Nothing new found."
        }
    }
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "probeIp":     probeManualIp(); break
        case "scan":        startScan(); break
        case "addSelected": addSelected(); break
    }
}

// ---- actions ---------------------------------------------------------------------------

private void probeManualIp() {
    String ip = settings.manualIp?.trim()
    if (!lifxIsValidIp(ip)) { state.probeMessage = "'${ip ?: ''}' is not a valid IPv4 address."; return }   // review focus 5
    state.autoAdd = (state.autoAdd ?: []) + ip
    state.probeUntil = now() + 8000
    state.probeMessage = "Probing ${ip}..."
    unschedule("probeTimeout"); runIn(8, "probeTimeout")
    sendTo(2, ip, [tagged: false])
}

void probeTimeout() {
    if (state.probeMessage?.startsWith("Probing")) state.probeMessage = "No response from the bulb. Check the IP and that it is on the same network."
    state.autoAdd = []
}

private void startScan() {
    String addr = settings.broadcastAddr ?: defaultBroadcast()
    if (!lifxIsValidIp(addr)) return
    state.found = [:]
    state.scanUntil = now() + 6000
    sendTo(2, addr, [tagged: true])
}

private void addSelected() {
    (settings.toAdd ?: []).each { String mac ->
        Map f = state.found?.get(mac)
        if (f) createBulb(mac, f.ip, f.label)
    }
    app.removeSetting("toAdd")
}

private String defaultBroadcast() {
    String hubIp = location.hub?.localIP
    return lifxIsValidIp(hubIp) ? lifxBroadcastFor(hubIp) : "255.255.255.255"
}

private Map unmanagedFound() {
    return (state.found ?: [:]).findAll { mac, f -> !getChildDevice(mac.toUpperCase()) }
}

// ---- LAN I/O -----------------------------------------------------------------------------

private void sendTo(int type, String ip, Map opts = [:]) {
    state.seq = ((state.seq ?: 0) + 1) % 256
    String hex = lifxBuildPacket(type, opts + [sequence: state.seq, resRequired: true])
    debug "tx type ${type} -> ${ip}"
    sendHubCommand(lifxHubAction(hex, ip, "lifxCallback"))
}

// Reply shape for app callbacks is the least-verified part of the Hubitat API: see README test 1.
void lifxCallback(def response) {
    Map msg = null
    try { msg = parseLanMessage(response?.description) } catch (Exception e) { debug "parseLanMessage failed: ${e.message}" }
    String hex = (msg?.payload ?: response?.body) as String
    Map pkt = lifxParsePacket(hex)
    if (!pkt) { debug "ignored unparseable reply: ${response?.description}"; return }
    String ip = lifxHexToIp(msg?.ip as String) ?: (response?.ip as String)

    switch (pkt.type) {
        case 3:  handleService(pkt.mac, ip); break
        case 25: handleLabel(pkt.mac, ip, lifxParseLabel(pkt.payload)); break
        default: debug "rx type ${pkt.type} (ignored)"
    }
}

private void handleService(String mac, String ip) {
    if (!lifxIsValidIp(ip)) { debug "service reply from ${mac} without usable IP"; return }
    def child = getChildDevice(mac.toUpperCase())
    if (child) {
        if (child.getSetting("ip") != ip) {
            log.info "LIFX ${mac}: IP changed to ${ip}"
            child.setIp(ip)
        }
        if ((state.autoAdd ?: []).contains(ip)) { state.probeMessage = "${child.displayName} already exists; IP is ${ip}."; state.autoAdd = [] }
        return
    }
    state.found = (state.found ?: [:]) + [(mac): [ip: ip, label: null]]
    sendTo(23, ip, [mac: mac])                                  // ask for the label (creates the bulb if user typed this IP)
}

private void handleLabel(String mac, String ip, String label) {
    Map found = (state.found ?: [:])
    found[mac] = [ip: ip, label: label]
    state.found = found
    if ((state.autoAdd ?: []).contains(ip)) {
        createBulb(mac, ip, label)
        state.autoAdd = (state.autoAdd - ip)
        state.probeMessage = "Added ${label ?: mac} at ${ip}."
    }
}

private void createBulb(String mac, String ip, String label) {
    String dni = mac.toUpperCase()
    if (getChildDevice(dni)) return
    String name = label ?: "LIFX ${dni[-6..-1]}"                // review focus 4: fallback name
    def child = addChildDevice("ivank", "LIFX LAN Bulb", dni, [name: "LIFX LAN Bulb", label: name, isComponent: false])
    child.setIp(ip)
    log.info "Added ${name} (${dni}) at ${ip}"
}

private void debug(String msg) { if (settings.logEnable) log.debug "LIFX app: ${msg}" }

void installed() { }
void updated() { }
```

- [ ] **Step 2: Run parse check on all three files**

Run: `groovy test/parse-check.groovy`
Expected: `parsed lib/lifx-protocol.groovy`, `parsed drivers/lifx-bulb-driver.groovy`, `parsed apps/lifx-manager-app.groovy`.

- [ ] **Step 3: Regression run**

Run: `groovy test/lifx-protocol-test.groovy lib/lifx-protocol.groovy`
Expected: `OK: 12 checks passed`

---

### Task 4: README, on-hub checklist, spec sync

**Files:**
- Create: `README.md`
- Modify: `docs/superpowers/specs/2026-10-07-lifx-hubitat-design.md` (App section)

- [ ] **Step 1: Write README.md**

````markdown
# LIFX LAN for Hubitat

Local-only (UDP 56700) control of LIFX bulbs: on/off, level, color, color temperature, polling. No cloud, no account.

## Install (order matters)
1. **Developer tools → Libraries code → New Library** → paste `lib/lifx-protocol.groovy` → Save.
2. **Drivers code → New Driver** → paste `drivers/lifx-bulb-driver.groovy` → Save.
3. **Apps code → New App** → paste `apps/lifx-manager-app.groovy` → Save.
4. **Apps → Add user app → LIFX LAN Manager**.

Give each bulb a DHCP reservation in your router. If it moves anyway, enter its new IP under *Add / update bulb by IP* (or run *Discover*): the app matches by MAC and updates the device.

## Develop / test off-hub
```
groovy test/lifx-protocol-test.groovy lib/lifx-protocol.groovy   # protocol unit tests
groovy test/parse-check.groovy                                   # syntax check of all sources
```

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
````

- [ ] **Step 2: Fix the spec's App section**

In `docs/superpowers/specs/2026-10-07-lifx-hubitat-design.md`, replace the whole `### App` section with:

```markdown
### App
- Main page: list of managed bulbs (label, MAC, IP).
- "Add / update by IP": user enters an IP → unicast GetService → reply header gives the MAC; if the MAC already belongs to a child its IP is updated, otherwise GetLabel is requested and the child is created (`addChildDevice`, DNI = uppercase MAC, IP set via the driver's `setIp`). Invalid IP → message, nothing sent. No reply in 8 s → "no response".
- "Discover": broadcast GetService (default broadcast = hub /24 `.255`, editable) for ~6 s, list unmanaged bulbs, user picks which to add. Bulbs already managed but found at a new IP get their IP updated (this is the "re-scan").
- Removal is done from the Hubitat device page (no app button).
```

- [ ] **Step 3: Final verification**

Run: `groovy test/lifx-protocol-test.groovy lib/lifx-protocol.groovy && groovy test/parse-check.groovy`
Expected: `OK: 12 checks passed` then three `parsed ...` lines, exit status 0.

---

## Self-Review (against the spec)

- **Spec coverage:** manual IP (Task 3 `probeManualIp`/`handleService`), discovery (`startScan`), change IP / re-scan (`handleService` child branch), on/off/level/color/CT (Task 2 commands), refresh + scheduled polling (`poll`, `schedule`), health offline after 3 misses (`poll`), debug auto-off (`logsOff`), no cloud calls (none present), README checklist (Task 4). Spec's "Change IP"/"Remove" buttons intentionally replaced (see Deviation).
- **Placeholders:** none; every step has code or an exact command.
- **Type consistency:** `lifxBuildPacket` opts keys (`mac, source, sequence, tagged, resRequired, payload`), `hsbk` keys `h,s,b,k`, `lifxParsePacket` keys (`size, source, mac, sequence, type, payload`), and `setIp(String)` are identical across Tasks 1–3.
- **Review Focus:** each of the five items maps to a Task 1 test; item 5 also guarded in the app (`probeManualIp`) and driver (`send`/`setIp`).
- **Known unverified items (called out in README test 1/7):** shape of the app `callback` reply object and `parseLanMessage(...).payload` for raw UDP; `hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT` constant spelling. Neither can be confirmed without a hub.
