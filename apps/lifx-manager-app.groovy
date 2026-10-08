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
            if (kids) kids.each { paragraph "<b>${esc(it.displayName)}</b> &mdash; ${esc(it.deviceNetworkId)} &mdash; ${esc(it.getSetting('ip') ?: 'no IP')}" }
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
            if (state.probeMessage) paragraph esc(state.probeMessage)
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
            Map options = unmanagedFound().collectEntries { mac, f -> [(mac): esc("${f.label ?: 'LIFX ' + mac[-6..-1]} (${f.ip})")] }
            if (options) {
                input "toAdd", "enum", title: "Select bulbs to add", options: options, multiple: true
                input "addSelected", "button", title: "Add selected"
            } else paragraph scanning ? "Waiting for replies..." : "Nothing new found."
        }
    }
}

void appButtonHandler(String btn) {
    // The button fires before the page's input values are saved to settings, so run the action a moment later.
    String action = [probeIp: "probeManualIp", scan: "startScan", addSelected: "addSelected"][btn]
    if (action) runIn(1, action)
}

// ---- actions ---------------------------------------------------------------------------

void probeManualIp() {
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

void startScan() {
    String addr = settings.broadcastAddr ?: defaultBroadcast()
    if (!lifxIsValidIp(addr)) return
    state.found = [:]
    state.scanUntil = now() + 6000
    sendTo(2, addr, [tagged: true])
}

void addSelected() {
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
    if (!state.source) state.source = lifxNewSource()
    String hex = lifxBuildPacket(type, opts + [source: state.source, sequence: state.seq, resRequired: true])
    debug "tx type ${type} -> ${ip}"
    sendHubCommand(lifxHubAction(hex, ip, "lifxCallback"))
}

// Reply shape for app callbacks is the least-verified part of the Hubitat API: see README test 1.
void lifxCallback(def response) {
    // Replies are unauthenticated UDP: only trust them while we have an outstanding probe/scan.
    if (now() >= (state.scanUntil ?: 0L) && now() >= (state.probeUntil ?: 0L)) { debug "ignored unsolicited reply"; return }
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

// Labels/IPs come from the network or the user; escape before rendering as HTML.
private String esc(def v) {
    return (v as String).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "&#39;")
}

private void debug(String msg) { if (settings.logEnable) log.debug "LIFX app: ${msg}" }

void installed() { }
void updated() { }
