import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, sep } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const driverUrl = new URL("../lib/pwa.mjs", import.meta.url).href;
const commonUrl = new URL("../lib/common.mjs", import.meta.url).href;

function outsideCheckout(t, code, overrides = {}) {
  const cwd = mkdtempSync(join(tmpdir(), "yuldash-import-"));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const env = { ...process.env };
  delete env.RSACC_SCRATCH;
  delete env.RSACC_CHROMIUM_PATH;
  delete env.NODE_TEST_CONTEXT;
  const child = spawnSync(process.execPath, ["--input-type=module", "-e", code], {
    cwd, env: { ...env, ...overrides }, encoding: "utf8", timeout: 20_000,
  });
  assert.ifError(child.error);
  assert.equal(child.status, 0, child.stderr);
  return JSON.parse(child.stdout);
}

test("driver imports outside the checkout and keeps profiles in the configured scratch directory", (t) => {
  const scratch = mkdtempSync(join(tmpdir(), "yuldash-paths-"));
  t.after(() => rmSync(scratch, { recursive: true, force: true }));
  const actual = outsideCheckout(t, `
    const driver = await import(${JSON.stringify(driverUrl)});
    const common = await import(${JSON.stringify(commonUrl)});
    console.log(JSON.stringify({ scratch: common.SCRATCH, profiles: driver.PROFILE_ROOT,
      chromium: driver.CHROMIUM, openProfile: typeof driver.openProfile }));
  `, { RSACC_SCRATCH: scratch });
  assert.equal(actual.scratch, scratch);
  assert.equal(actual.profiles, join(scratch, "pw"));
  assert.equal(actual.openProfile, "function");
  assert.ok(actual.chromium.length > 0);
});

test("default scratch path is portable and independent of the current working directory", (t) => {
  const code = `const { SCRATCH } = await import(${JSON.stringify(commonUrl)});
    console.log(JSON.stringify(SCRATCH));`;
  const first = outsideCheckout(t, code);
  const second = outsideCheckout(t, code);
  assert.equal(first, second);
  assert.ok(first.startsWith(join(tmpdir(), "yuldash-e2e") + sep));
});

test("an explicit browser path is honoured without a machine-specific fallback", (t) => {
  const chromium = join(tmpdir(), "synthetic-chromium");
  const actual = outsideCheckout(t, `const { CHROMIUM } = await import(${JSON.stringify(driverUrl)});
    console.log(JSON.stringify(CHROMIUM));`, { RSACC_CHROMIUM_PATH: chromium });
  assert.equal(actual, chromium);
});
