// Scenario runner: step log, assertions, provenance (HEAD, installed APK, PWA build), result file, exit code.
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { EVID, REPO, log, setRunLog, q, sleep } from "./common.mjs";
import * as A from "./android.mjs";
import { setPwaShotDir } from "./pwa.mjs";
import { redact } from "./redact.mjs";
import { sourceSnapshot, treeFiles, sha256, sha256File, distHash, assertBinding } from "./provenance.mjs";
export { sha256File } from "./provenance.mjs";

export function provenance({ android = true } = {}) {
  const snapshot = sourceSnapshot(REPO);
  const binding = JSON.parse(readFileSync(process.env.RSACC_BUILD_BINDING || join(EVID, "build-binding.json"), "utf8"));
  const builtApk = sha256File(join(REPO, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk"));
  const pwaDist = distHash(REPO);
  const out = { ...snapshot, pwa_dist_sha256: pwaDist, harness_sha256: sha256(JSON.stringify(treeFiles(REPO, join(REPO, "tools/rideshare_e2e")))) };
  let installedApk = builtApk;
  if (android) {
    const apkPath = A.sh(`pm path ${A.PKG}`, { quiet: true }).split("\n").find((l) => l.startsWith("package:"))?.slice(8).trim();
    out.installed_apk_sha256 = apkPath ? A.sh(`sha256sum ${apkPath}`, { quiet: true }).split(/\s+/)[0] : null;
    out.built_apk_sha256 = sha256File(join(REPO, "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk"));
    out.apk_matches_build = out.installed_apk_sha256 === out.built_apk_sha256;
    installedApk = out.installed_apk_sha256;
    out.android_serial = A.SERIAL;
    out.android_display = A.sh("wm size", { quiet: true }).trim().replace(/\n/g, "; ") + " / " + A.sh("wm density", { quiet: true }).trim().replace(/\n/g, "; ");
  }
  assertBinding(binding, snapshot, builtApk, pwaDist, installedApk);
  return out;
}

export class Scenario {
  constructor(id, meta = {}) {
    this.id = id;
    this.meta = meta;
    this.dir = join(EVID, "runs", id);
    if (!/^[a-zA-Z0-9_-]+$/.test(id)) throw new Error("Scenario id must be a plain unique run label");
    try { readFileSync(join(this.dir, "result.json")); throw new Error("Run evidence already exists; choose a new unique run tag"); } catch (e) { if (e.code !== "ENOENT") throw e; }
    mkdirSync(join(this.dir, "shots"), { recursive: true });
    setRunLog(join(this.dir, "steps.jsonl"));
    A.setShotDir(join(this.dir, "shots"));
    setPwaShotDir(join(this.dir, "shots"));
    this.steps = [];
    this.checks = [];
    this.started = new Date();
    this.facts = {};
  }
  async step(name, fn) {
    const t0 = Date.now();
    log("step.start", { name });
    try {
      const r = await fn();
      this.steps.push({ name, ok: true, ms: Date.now() - t0 });
      log("step.ok", { name, ms: Date.now() - t0 });
      return r;
    } catch (e) {
      this.steps.push({ name, ok: false, ms: Date.now() - t0, error: String(e.message).split("\n")[0] });
      log("step.fail", { name, error: String(e.message).split("\n").slice(0, 3).join(" | "), screen: e.screen || null });
      throw e;
    }
  }
  check(cond, what, data = {}) {
    this.checks.push({ what, ok: !!cond, ...data });
    log(cond ? "check.ok" : "check.fail", { what, ...data });
    if (!cond) throw new Error("CHECK FAILED: " + what);
  }
  finish(ok, error = null, extra = {}) {
    const result = {
      scenario: this.id, ...this.meta, result: ok ? "passed" : "failed", error,
      started_at: this.started.toISOString(), finished_at: new Date().toISOString(),
      steps: this.steps, checks: this.checks, checks_passed: this.checks.filter((c) => c.ok).length,
      checks_failed: this.checks.filter((c) => !c.ok).length, facts: this.facts, ...extra,
    };
    writeFileSync(join(this.dir, "result.json"), JSON.stringify(redact(result), null, 2));
    return result;
  }
}

/** Standard main(): run body, always write result, exit 0 only on full pass. */
export async function runScenario(sc, body, cleanup = async () => {}) {
  let ok = false, error = null;
  try {
    sc.facts.provenance_start = provenance({ android: sc.meta.android !== false });
    await body(sc);
    ok = true;
  } catch (e) {
    error = String(e.message).split("\n").slice(0, 4).join(" | ");
    try { if (sc.meta.android !== false) A.screencap("FAIL-android"); } catch {}
    try { for (const [i, pg] of (sc.pages || []).entries()) await pg.screenshot({ path: join(sc.dir, "shots", `FAIL-pwa-${i}.png`) }); } catch {}
  } finally {
    try { await cleanup(ok); } catch (e) { ok = false; error = "Cleanup failed: " + String(e.message); log("cleanup.error", { error }); }
  }
  try {
    sc.facts.provenance_end = provenance({ android: sc.meta.android !== false });
    if (JSON.stringify(sc.facts.provenance_start) !== JSON.stringify(sc.facts.provenance_end)) throw new Error("Source/build provenance changed during scenario");
  } catch (e) { ok = false; error = String(e.message); }
  const r = sc.finish(ok, error);
  console.log(JSON.stringify(redact({ scenario: sc.id, result: r.result, checks_passed: r.checks_passed, error })));
  process.exit(ok ? 0 : 1);
}

export { q, sleep, log };
