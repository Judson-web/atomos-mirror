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
  const scripts = [...kotlin.matchAll(/<script>([\\s\\S]*?)<\\/script>/g)].map(match => match[1]);
  assert.equal(scripts.length, 2, "expected dashboard and WebRTC receiver scripts");
  scripts.forEach((script, index) => assert.doesNotThrow(() => new Function(script), "dashboard script " + index));
});

test("pairing uses approved expiring QR tokens instead of PINs", () => {
  const http = read("app/src/main/java/com/atomos/mirror/LocalReceiverServer.kt");
  const service = read("app/src/main/java/com/atomos/mirror/MirrorReceiverService.kt");
  const desktop = read("desktop/main.cjs");
  assert.doesNotMatch(http, /put\\("pairingPin"/);
  assert.doesNotMatch(service, /setAttribute\\("pin"/);
  assert.doesNotMatch(desktop, /pin:\\s*String\\(txt\\.pin/);
  assert.ok(http.includes("/api/pairing/challenge"));
  assert.ok(http.includes("/api/pairing/session"));
  assert.ok(http.includes("generate-pair-qr"));
  assert.doesNotMatch(http, /am-pin-input|six-digit PIN/);
  assert.match(service, /approvePairingToken/);
  assert.match(desktop, /generate-qr/);
});

test("signalling limits a session to one sender and one receiver", () => {
  const signal = read("app/src/main/java/com/atomos/mirror/SignalServer.kt");
  assert.match(signal, /MAX_PEERS = 2/);
  assert.match(signal, /it\\.role == role/);
  assert.match(signal, /peer\\.role != source\\.role/);
  assert.match(signal, /MAX_AUTH_FAILURES = 5/);
  assert.match(signal, /challengeApproved/);
  assert.match(signal, /CHALLENGE_TTL_MS = 180_000L/);
  assert.match(signal, /allowedForRole/);
  const androidSender = read("app/src/main/java/com/atomos/mirror/AndroidScreenSender.kt");
  const desktopSender = read("desktop/renderer.js");
  assert.match(androidSender, /receiverPresent/);
  assert.match(androidSender, /pairingToken/);
  assert.match(desktopSender, /hasCounterpart/);
  assert.match(desktopSender, /pairingToken/);
  assert.match(desktopSender, /preparePairing/);
});
