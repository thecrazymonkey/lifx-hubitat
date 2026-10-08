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
