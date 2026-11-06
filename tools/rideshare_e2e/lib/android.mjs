// Android driver over adb + uiautomator for the real installed MainActivity (no instrumentation of the app).
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { EVID, log, sleep } from "./common.mjs";

export const ADB = "C:/Users/Bayra/AppData/Local/Android/Sdk/platform-tools/adb.exe";
export const PKG = "com.yuldash.app";
export let SERIAL = process.env.ANDROID_SERIAL || "emulator-5554";
export const ADB_PORT = process.env.ADB_PORT || "5037";
export function setSerial(s) { SERIAL = s; }
let shotDir = join(EVID, "runs", "_shots");
export function setShotDir(d) { shotDir = d; mkdirSync(d, { recursive: true }); }

export function adb(args, { timeout = 60000, binary = false, quiet = false } = {}) {
  const out = execFileSync(ADB, ["-P", ADB_PORT, "-s", SERIAL, ...args.map(String)], { timeout, windowsHide: true, maxBuffer: 64 * 1024 * 1024 });
  if (!quiet) log("adb", { args: args.slice(0, 6).map(String) });
  return binary ? out : out.toString("utf8");
}
export const sh = (cmd, o) => adb(["shell", cmd], o);

function decode(s) { return s.replace(/&#(\d+);/g, (m, d) => (d === "10" || d === "13" ? m : String.fromCodePoint(Number(d)))).replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&#10;/g, "\n").replace(/&#13;/g, "").replace(/&amp;/g, "&"); }
export function parse(xml) {
  const nodes = [];
  for (const m of xml.matchAll(/<node ([^>]*?)\/?>/g)) {
    const a = {};
    for (const am of m[1].matchAll(/([\w-]+)="([^"]*)"/g)) a[am[1]] = decode(am[2]);
    const b = (a.bounds || "").match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
    if (b) a.box = b.slice(1).map(Number);
    nodes.push(a);
  }
  return nodes;
}

let dumpN = 0;
export function dump({ label = null, keep = false } = {}) {
  for (let attempt = 0; attempt < 6; attempt++) {
    const remote = `/data/local/tmp/rsacc-${Date.now()}-${dumpN++}.xml`;
    let res = "";
    // Never overlap UiAutomation sessions: a leftover dump from a killed run wedges the next ones.
    try { sh("pkill -f 'uiautomator dump' ; true", { quiet: true, timeout: 10000 }); } catch {}
    try { res = sh(`uiautomator dump ${remote}`, { quiet: true, timeout: 25000 }); } catch (e) { res = String(e.message); }
    if (res.includes("UI hierchary dumped to:") && res.includes(remote)) {
      const xml = adb(["exec-out", "cat", remote], { quiet: true });
      sh(`rm -f ${remote}`, { quiet: true });
      if (label || keep) writeFileSync(join(shotDir, `${label || "dump-" + Date.now()}.xml`), xml);
      return parse(xml);
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 700);
  }
  throw new Error("uiautomator dump failed repeatedly");
}

export const label = (n) => (n.text || "") + (n["content-desc"] ? ` {${n["content-desc"]}}` : "");
export function texts(nodes) { return nodes.filter((n) => n.text || n["content-desc"]).map((n) => `${label(n)}${n.clickable === "true" ? " [c]" : ""}${n.enabled === "false" ? " [off]" : ""}${n.checked === "true" ? " [x]" : ""} @${n.box ? n.box.join(",") : ""}`); }
function matches(n, want) {
  const vals = [n.text || "", n["content-desc"] || ""];
  return vals.some((v) => (want instanceof RegExp ? want.test(v) : v === want));
}
export function find(nodes, want, { index = 0, clickableAncestor = true } = {}) {
  const hits = nodes.filter((n) => matches(n, want) && n.box && n.box[2] > n.box[0] && n.box[3] > n.box[1]);
  return hits[index] || null;
}
export function center(n) { return [Math.round((n.box[0] + n.box[2]) / 2), Math.round((n.box[1] + n.box[3]) / 2)]; }
export function tapXY(x, y) { sh(`input tap ${x} ${y}`, { quiet: true }); log("android.tap", { x, y }); }

/** Wait until `want` is on screen (fresh dumps), then tap its center. */
export async function tapText(want, { index = 0, timeout = 20000, what = null } = {}) {
  const n = await waitFor(want, { index, timeout });
  const [x, y] = center(n);
  sh(`input tap ${x} ${y}`, { quiet: true });
  log("android.tap", { want: String(want), what, at: [x, y] });
  await sleep(500);
  return n;
}
export async function waitFor(want, { index = 0, timeout = 20000, gone = false } = {}) {
  const t0 = Date.now();
  let nodes = [];
  while (Date.now() - t0 < timeout) {
    nodes = dump();
    const n = find(nodes, want, { index });
    if (!gone && n) return n;
    if (gone && !n) return true;
    await sleep(600);
  }
  const err = new Error(`android: ${gone ? "still visible" : "not visible"}: ${want}`);
  err.screen = texts(nodes).slice(0, 80);
  throw err;
}
export async function has(want) { return !!find(dump(), want); }

export function screencap(name) {
  mkdirSync(shotDir, { recursive: true });
  const png = adb(["exec-out", "screencap", "-p"], { binary: true, quiet: true });
  const p = join(shotDir, name.endsWith(".png") ? name : name + ".png");
  writeFileSync(p, png);
  log("android.shot", { file: p.replace(EVID, "test-results/release") });
  return p;
}

/** Unicode-safe text input into the focused (or N-th) EditText via accessibility SET_TEXT. */
export function typeText(value, index = null) {
  if (value === "") { clearFocused(); return; }
  const b64 = Buffer.from(value, "utf8").toString("base64");
  const args = ["shell", "uiautomator", "runtest", "qa-uitext.jar", "-c", "qa.UiTextTest", "-e", "value", b64];
  if (index !== null) args.push("-e", "index", String(index));
  let out = "";
  for (let attempt = 0; attempt < 6; attempt++) {
    out = adb(args, { quiet: true, timeout: 60000 });
    if (/OK \(1 test\)/.test(out) && !/FAILURES|Exception/.test(out) && /QA_TEXT_SET/.test(out)) break;
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 1000);
  }
  if (!/OK \(1 test\)/.test(out) || /FAILURES|Exception/.test(out)) {
    const why = out.split(/\r?\n/).find((l) => /AssertionFailedError|Exception/.test(l)) || out.slice(-300);
    throw new Error("typeText failed: " + why);
  }
  log("android.type", { chars: value.length, index });
}
/** Select-all + delete in the focused field (Compose supports Ctrl+A). */
export function clearFocused() {
  sh("input keycombination KEYCODE_CTRL_LEFT KEYCODE_A", { quiet: true });
  sh("input keyevent KEYCODE_DEL", { quiet: true });
  log("android.clear", {});
}
export async function typeInto(want, value, { index = 0 } = {}) {
  await tapText(want, { index, what: "focus field" });
  await sleep(400);
  typeText(value);
  await sleep(500);
}
export function key(k) { sh(`input keyevent ${k}`, { quiet: true }); log("android.key", { k }); }
export function back() { key("KEYCODE_BACK"); }
export function imeShown() { return /mInputShown=true/.test(sh("dumpsys input_method | grep -m1 mInputShown", { quiet: true })); }
/** Hide the soft keyboard only if it is shown (a bare BACK would navigate away). */
export function hideKeyboard() { if (imeShown()) { back(); Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 400); } }

export async function swipeUp(frac = 0.5) {
  const [w, h] = screenSize();
  const x = Math.round(w / 2), y1 = Math.round(h * 0.75), y2 = Math.round(h * (0.75 - frac));
  sh(`input swipe ${x} ${y1} ${x} ${y2} 400`, { quiet: true });
  log("android.swipe", { dir: "up" });
  await sleep(600);
}
export async function swipeDown(frac = 0.5, { from = null } = {}) {
  const [w, h] = screenSize();
  // Start in the lower half by default: nested lists (home feed) live there; the hero above does not scroll them.
  const y1 = Math.round(h * (from ?? 0.5)), y2 = Math.min(Math.round(h * 0.86), Math.round(y1 + h * frac));
  sh(`input swipe ${Math.round(w / 2)} ${y1} ${Math.round(w / 2)} ${y2} 400`, { quiet: true });
  await sleep(600);
}
/** Scroll until `want` is visible away from the bars: first down the content, then back up. */
export async function scrollTo(want, { max = 8, index = 0 } = {}) {
  const [, h] = screenSize();
  const ok = () => { const n = find(dump(), want, { index }); return n && n.box[3] < h * 0.88 && n.box[1] > h * 0.08 ? n : null; };
  let n = ok();
  if (n) return n;
  for (let i = 0; i < max; i++) { await swipeUp(0.35); if ((n = ok())) return n; }
  for (let i = 0; i < max * 2; i++) { await swipeDown(0.35, { from: i % 2 ? 0.25 : 0.5 }); if ((n = ok())) return n; }
  throw new Error("android: not reachable by scrolling: " + want);
}
export async function scrollTap(want, opts = {}) {
  const n = await scrollTo(want, opts);
  const [x, y] = center(n);
  sh(`input tap ${x} ${y}`, { quiet: true });
  log("android.tap", { want: String(want), at: [x, y], scrolled: true });
  await sleep(500);
  return n;
}
let _size = null;
export function screenSize() {
  if (_size) return _size;
  const m = sh("wm size", { quiet: true }).match(/(\d+)x(\d+)\s*$/m);
  _size = [Number(m[1]), Number(m[2])];
  return _size;
}
export function resetSizeCache() { _size = null; }

export function pid() { const out = sh(`pidof ${PKG}`, { quiet: true }).trim(); return out ? Number(out.split(/\s+/)[0]) : null; }
export function forceStop() { sh(`am force-stop ${PKG}`); }
export function launch() { sh(`am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${PKG}/.MainActivity`); }
export function crashes(sinceEpochMs) {
  const out = adb(["logcat", "-d", "-v", "epoch", "-b", "crash"], { quiet: true });
  return out.split("\n").filter((l) => l.includes(PKG) && Number(l.trim().split(/\s+/)[0]) * 1000 >= sinceEpochMs);
}
export { sleep, log };
