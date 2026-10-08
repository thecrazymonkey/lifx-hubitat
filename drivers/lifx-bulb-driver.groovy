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
    if ((state.missed ?: 0) >= 3 && device.currentValue("healthStatus") != "offline") {   // 3 earlier polls unanswered
        log.warn "${device.displayName}: no reply to ${state.missed} polls, marking offline"
        sendEvent(name: "healthStatus", value: "offline")
    }
    state.missed = (state.missed ?: 0) + 1
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
    if (!state.source) state.source = lifxNewSource()
    // Get replies come regardless; a Set's State reply can carry pre-change values, so don't request one.
    String hex = lifxBuildPacket(type, [mac: device.deviceNetworkId, source: state.source, sequence: state.seq, resRequired: type == 101, payload: payload])
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
    sendEvent(name: "switch", value: s.on ? "on" : "off")
    sendEvent(name: "level", value: lifxRawToPct(s.b), unit: "%")
    sendEvent(name: "hue", value: lifxRawToPct(s.h))
    sendEvent(name: "saturation", value: lifxRawToPct(s.s), unit: "%")
    sendEvent(name: "colorTemperature", value: s.k, unit: "K")
    sendEvent(name: "colorMode", value: (s.s == 0) ? "CT" : "RGB")
    debug "state: ${s}"
}
