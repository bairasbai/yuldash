// Synthetic page only: no backend, account login, provider or payment requests.
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const scratch = mkdtempSync(join(tmpdir(), "yuldash-browser-"));
process.env.RSACC_SCRATCH = scratch;
const contexts = new Set();
try {
  const { openProfile, PROFILE_ROOT } = await import("../lib/pwa.mjs");
  assert.equal(PROFILE_ROOT, join(scratch, "pw"));
  await assert.rejects(openProfile("..", { fresh: true }), /single directory name/);

  async function open(name, options) {
    const profile = await openProfile(name, options);
    contexts.add(profile.ctx);
    return profile;
  }
  async function close(profile) {
    await profile.ctx.close();
    contexts.delete(profile.ctx);
  }

  let first = await open("first", { fresh: true });
  await first.page.setContent('<button onclick="this.textContent=\'Clicked\'">Smoke</button>');
  await first.page.getByRole("button", { name: "Smoke" }).click();
  assert.equal(await first.page.getByRole("button").textContent(), "Clicked");
  await first.ctx.addCookies([{
    name: "synthetic_smoke", value: "first-profile", domain: "127.0.0.1", path: "/",
    expires: Math.floor(Date.now() / 1000) + 3600,
  }]);
  assert.deepEqual(first.consoleErrors, []);
  assert.deepEqual(first.external, []);
  await close(first);

  first = await open("first");
  assert.equal((await first.ctx.cookies()).find((cookie) => cookie.name === "synthetic_smoke")?.value, "first-profile");
  const second = await open("second", { fresh: true, dark: true });
  assert.equal((await second.ctx.cookies()).length, 0);
  await close(second);
  await close(first);

  first = await open("first", { fresh: true });
  assert.equal((await first.ctx.cookies()).length, 0);
  await close(first);
  console.log("PASS: Chromium launch, page interaction, profile persistence/isolation and fresh reset (synthetic data only)");
} finally {
  await Promise.allSettled([...contexts].map((ctx) => ctx.close()));
  rmSync(scratch, { recursive: true, force: true });
}
