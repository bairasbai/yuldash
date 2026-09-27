import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { checkPublicExport, publicPages } from "../scripts/check-public-export.mjs";

function fixture(t, markup = '<a href="/help">Help</a><a href="/yuldash.apk" download="">Download</a>') {
  const root = mkdtempSync(join(tmpdir(), "yuldash-public-export-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  for (const route of publicPages) {
    mkdirSync(join(root, route), { recursive: true });
    writeFileSync(join(root, route, "index.html"), markup);
  }
  return root;
}

test("ordinary build checks routes but explicitly leaves APK to release validation", (t) => {
  assert.deepEqual(checkPublicExport(fixture(t)), { errors: [], downloads: ["/yuldash.apk"] });
});

test("release refuses missing APK before replacing the live website", (t) => {
  const result = checkPublicExport(fixture(t), { release: true });
  assert.ok(result.errors.some((error) => error.includes("Missing or empty download: /yuldash.apk")));
});

test("deploy invokes fail-closed release validation before packing or uploading", () => {
  const script = readFileSync(new URL("../deploy.sh", import.meta.url), "utf8");
  assert.match(script, /^set -euo pipefail$/m);
  const build = script.indexOf("\nnpm run build\n");
  const check = script.indexOf("\nnode scripts/check-public-export.mjs --release\n");
  const pack = script.indexOf("\ntar czf ");
  const upload = script.indexOf('\nretry "scp tarball"');
  assert.ok(build >= 0 && check > build && pack > check && upload > pack);
});

test("release refuses empty APK and accepts a supplied nonempty file", (t) => {
  const root = fixture(t);
  writeFileSync(join(root, "yuldash.apk"), "");
  assert.ok(checkPublicExport(root, { release: true }).errors.length);
  writeFileSync(join(root, "yuldash.apk"), "synthetic APK bytes, not a signature check");
  assert.deepEqual(checkPublicExport(root, { release: true }).errors, []);
});

test("missing public route and broken local link fail the build check", (t) => {
  const root = fixture(t, '<a href="/missing?from=home#section">Missing</a>');
  rmSync(join(root, "ba/index.html"));
  const { errors } = checkPublicExport(root);
  assert.ok(errors.includes("Missing public page: /ba"));
  assert.ok(errors.some((error) => error.includes("Missing or empty local link: /missing")));
});

test("skip links must have a real target on every page", (t) => {
  const root = fixture(t, '<a href="#top">Skip to content</a>');
  assert.equal(checkPublicExport(root).errors.length, publicPages.length);
  for (const route of publicPages) {
    writeFileSync(join(root, route, "index.html"), '<a href="#top">Skip</a><main id="top" tabindex="-1">Content</main>');
  }
  assert.deepEqual(checkPublicExport(root).errors, []);
});

test("external links are not requested; encoded traversal and invalid URLs fail closed", (t) => {
  const root = fixture(t, '<a href="https://example.invalid">Outside</a><a href="//example.invalid">Outside</a><a href="/%2e%2e/private">Escape</a><a href="/%ZZ">Invalid</a>');
  const { errors } = checkPublicExport(root);
  assert.equal(errors.length, 2);
  assert.ok(errors.some((error) => error.includes("leaves export directory")));
  assert.ok(errors.some((error) => error.includes("Invalid local link")));
});
