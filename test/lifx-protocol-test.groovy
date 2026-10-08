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
