// Pure checks: do not open databases, ports, browser or emulator.
import assert from "node:assert/strict";
import { redact, redactText } from "./lib/redact.mjs";
import { assertBinding } from "./lib/provenance.mjs";
const snapshot = { head: "commit", source_sha256: "source", dirty_sha256: "dirty" };
const binding = { source: snapshot, apk_sha256: "apk", pwa_dist_sha256: "dist" };
assert.doesNotThrow(() => assertBinding(binding, snapshot, "apk", "dist", "apk"));
assert.throws(() => assertBinding(binding, snapshot, "apk", "dist", "foreign-apk"), /APK/);
assert.throws(() => assertBinding(binding, { ...snapshot, source_sha256: "new-source" }, "apk", "dist"), /Source changed/);
assert.throws(() => assertBinding(binding, { ...snapshot, dirty_sha256: "new-dirty" }, "apk", "dist"), /snapshot changed/);
assert.throws(() => assertBinding(binding, snapshot, "apk", "stale-dist"), /PWA/);
const phone = "+7" + "9990301199", token = "syntheticsecretlink123456789";
const clean = redactText(`GET /t/${token}?token=${token}; ${phone}; boarding code 123456`);
assert(!clean.includes(phone) && !clean.includes(token) && !clean.includes("123456"));
assert.equal(redact({ phone, boarding_code: "123456", exit_code: 0 }).exit_code, 0);
assert.equal(redact({ nested: [{ url: `/t/${token}` }] }).nested[0].url, "/t/<redacted>");
console.log("Pure helper checks passed: matching build accepted; foreign/stale provenance rejected; sensitive text scrubbed");
