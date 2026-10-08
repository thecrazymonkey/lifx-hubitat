// Run: groovy -cp test/stubs test/parse-check.groovy
// Confirms each Hubitat source file is syntactically valid Groovy. Hubitat-only classes
// (hubitat.device.*) are stubbed in test/stubs; everything else resolves dynamically.
def files = ["lib/lifx-protocol.groovy", "drivers/lifx-bulb-driver.groovy", "apps/lifx-manager-app.groovy"]
files.each { path ->
    String src = new File(path).text.readLines().collect { it.startsWith("#include") ? "" : it }.join("\n")
    try { new GroovyShell().parse(src, new File(path).name); println "parsed ${path}" }
    catch (Throwable t) { println "FAIL ${path}: ${t.message}"; System.exit(1) }
}
