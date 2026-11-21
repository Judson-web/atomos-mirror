const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "../..");
const read = file => fs.readFileSync(path.join(root, file), "utf8");

test("Electron entry points parse without executing privileged APIs", () => {
  for (const file of ["desktop/main.cjs", "desktop/preload.cjs", "desktop/renderer.js"]) {
    assert.doesNotThrow(() => new Function(read(file)), file);
  }
});

test("Android-hosted browser dashboard scripts parse", () => {
  const kotlin = read("app/src/main/java/com/atomos/mirror/LocalReceiverServer.kt");
  const scripts = [...kotlin.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
  assert.equal(scripts.length, 2, "expected dashboard and WebRTC receiver scripts");
  scripts.forEach((script, index) => assert.doesNotThrow(() => new Function(script), "dashboard script " + index));
});

test("pairing PIN is not exposed through status API or mDNS", () => {
  const http = read("app/src/main/java/com/atomos/mirror/LocalReceiverServer.kt");
  const service = read("app/src/main/java/com/atomos/mirror/MirrorReceiverService.kt");
  const desktop = read("desktop/main.cjs");
  assert.doesNotMatch(http, /put\("pairingPin"/);
  assert.doesNotMatch(service, /setAttribute\("pin"/);
  assert.doesNotMatch(desktop, /pin:\s*String\(txt\.pin/);
  assert.match(http, /am-pin-input/);
});

test("signalling limits a session to one sender and one receiver", () => {
  const signal = read("app/src/main/java/com/atomos/mirror/SignalServer.kt");
  assert.match(signal, /MAX_PEERS = 2/);
  assert.match(signal, /it\.role == role/);
  assert.match(signal, /peer\.role != source\.role/);
  assert.match(signal, /MAX_PIN_FAILURES = 5/);
});
