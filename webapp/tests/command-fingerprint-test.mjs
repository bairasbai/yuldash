import assert from "node:assert/strict";
import { createHash, webcrypto } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

const fixtures = JSON.parse(readFileSync(new URL("../../tools/fixtures/rs05-command-fingerprint.json", import.meta.url)));
const compiled = await build({
  entryPoints: [fileURLToPath(new URL("../src/utils/commandFingerprint.ts", import.meta.url))],
  bundle: true,
  write: false,
  format: "esm",
});
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
const { commandFingerprint } = await import("data:text/javascript;base64," + Buffer.from(compiled.outputFiles[0].text).toString("base64"));

let checked = 0;
for (const example of fixtures) {
  const { scope, target_id: target, wire_body: wire, canonical_body: canonical } = example;
  const body = wire === null ? undefined : JSON.parse(wire);
  const expected = '{"body":' + canonical + ',"scope":' + JSON.stringify(scope) + ',"target_id":' + JSON.stringify(target) + "}";
  const snapshot = await commandFingerprint(scope, target, body);
  assert.equal(snapshot.payloadHash, createHash("sha256").update(expected).digest("hex"), example.name);
  assert.equal(snapshot.wireBody, JSON.stringify(body), example.name);
  checked += 2;
}

const snapshot = await commandFingerprint("ride.create", null, {
  starts_at: new Date("2030-01-01T10:00:00.120Z"),
  waypoints: [{ city: "Өфө", lat: -0 }, undefined],
  dropped: undefined,
});
const publication = fixtures.find(example => example.scope === "ride.create");
assert.deepEqual(JSON.parse(snapshot.wireBody), JSON.parse(publication.canonical_body));
checked++;

for (const invalid of [{ bad: Infinity }, { bad: NaN }, { bad: "\ud800" }, { bad: "я".repeat(64 * 1024) }]) {
  await assert.rejects(commandFingerprint("ride.create", null, invalid), TypeError);
  checked++;
}
console.log(checked + " passed");
