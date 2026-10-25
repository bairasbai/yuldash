// Pure checks: do not open databases, ports, browser or emulator.
import assert from "node:assert/strict";
import { redact, redactText, redactUiXml } from "./lib/redact.mjs";
import { assertBinding } from "./lib/provenance.mjs";
import { registeredPathContains, assertRegisteredOrdinaryPath } from "./lib/registered-path.mjs";
import { resolve, join, dirname } from "node:path";
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
const xml = redactUiXml(`<hierarchy><node text="&#128522; &#x1F60A; ${phone}; code &#49;&#50;&#51;&#52;&#53;&#54; &quot;&lt;&amp;&apos;&#10;&#9;" /></hierarchy>`);
assert(xml.includes("😊 😊") && !xml.includes(phone) && !xml.includes("123456"));
assert(xml.includes("&lt;redacted-phone&gt;") && xml.includes("&lt;redacted-code&gt;"));
assert(xml.includes("&quot;&lt;&amp;&apos;&#10;&#9;") && !xml.includes("&#<"));
assert(!redactUiXml('<node text="&#43;&#55;&#57;&#57;&#57;&#48;&#51;&#48;&#49;&#49;&#57;&#57;" />').includes("+7999"));
assert.throws(() => redactUiXml('<node text="&#0;" />'), /Invalid XML/);
const rawRoot = resolve("test-registered-root"), canonicalRoot = resolve("test-canonical-root"), rawFile = join(rawRoot, "child", "file"), canonicalFile = join(canonicalRoot, "child", "file");
const info = (link = false, dev = 1n, ino = 7n) => ({ dev, ino, isSymbolicLink: () => link });
const redirect = path => registeredPathContains(path, rawRoot) ? join(canonicalRoot, path.slice(rawRoot.length)) : path;
const fs = { lstat: () => info(), realpath: redirect };
assert.equal(assertRegisteredOrdinaryPath(rawFile, rawRoot, fs), canonicalFile);
for (const path of [rawRoot + "-lookalike/file", join(rawRoot, "..", "foreign"), resolve("foreign")]) {
  assert(!registeredPathContains(path, rawRoot));
  assert.throws(() => assertRegisteredOrdinaryPath(path, rawRoot, fs), /outside/);
}
for (const bad of [rawFile, dirname(rawFile), canonicalFile, dirname(canonicalFile)]) {
  assert.throws(() => assertRegisteredOrdinaryPath(rawFile, rawRoot, { ...fs, lstat: path => info(path === bad) }), /link/);
}
assert.throws(() => assertRegisteredOrdinaryPath(rawFile, rawRoot, { ...fs, realpath: path => path === rawFile ? join(canonicalRoot, "other") : redirect(path) }), /redirected/);
assert.throws(() => assertRegisteredOrdinaryPath(rawFile, rawRoot, { ...fs, realpath: path => path === dirname(canonicalFile) ? resolve("foreign") : redirect(path) }), /redirect/);
for (const [dev, ino] of [[2n, 7n], [1n, 8n], [1n, 0n], [1, 7]]) {
  assert.throws(() => assertRegisteredOrdinaryPath(rawFile, rawRoot, { ...fs, lstat: path => path === canonicalFile ? info(false, dev, ino) : info() }), /same identifiable file/);
}
assert.throws(() => assertRegisteredOrdinaryPath(rawFile, rawRoot, { ...fs, lstat: () => { throw new Error("missing file"); } }), /missing/);
console.log("Pure helper checks passed: matching build accepted; foreign/stale provenance rejected; sensitive text scrubbed");
