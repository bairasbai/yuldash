// Shared scenario plumbing: open clients, ensure accounts, DB lookups, wire log access.
import { openProfile, pwaLogin, PWA } from "./pwa.mjs";
import * as AF from "./android-flows.mjs";
import { q, q1, log, sleep, API } from "./common.mjs";
import { ACC } from "../accounts.mjs";

export const A = AF.A;
export { AF, ACC, q, q1, log, sleep };

export async function wireFrom() { const r = await fetch("http://127.0.0.1:8099/log"); return (await r.json()).length; }
export async function wireSince(n) { const r = await fetch(`http://127.0.0.1:8099/log?from=${n}`); return await r.json(); }
export async function fault(body) { const r = await fetch("http://127.0.0.1:8099/fault", { method: "POST", body: JSON.stringify(body) }); return await r.json(); }

export async function standHealthy(sc) {
  const api = await fetch(API + "/health").then((r) => r.status).catch(() => 0);
  const proxy = await fetch("http://127.0.0.1:8000/health").then((r) => r.status).catch(() => 0);
  const pwa = await fetch(PWA + "/").then((r) => r.status).catch(() => 0);
  sc.check(api === 200 && proxy === 200 && pwa === 200, "stand up: API, wire proxy, PWA preview", { api, proxy, pwa });
}

/** Open a PWA profile and make sure it is logged in as `acc` (real login flow on first use). */
export async function pwaClient(acc, opts = {}) {
  const c = await openProfile(acc.profile, opts);
  c.page._tag = acc.profile;
  await c.page.goto(PWA + "/", { waitUntil: "domcontentloaded" });
  await sleep(3000);
  let guest = /intro|onboarding|login|splash/.test(c.page.url());
  if (!guest) {
    await c.page.goto(PWA + "/profile", { waitUntil: "domcontentloaded" });
    await sleep(2000);
    guest = (await c.page.getByText("Войди в Юлдаш").count()) > 0;
  }
  if (guest) await pwaLogin(c.page, { ...acc, lang: opts.lang || "ru" });
  await c.page.goto(PWA + "/map", { waitUntil: "domcontentloaded" });
  await sleep(1500);
  log("pwa.client", { profile: acc.profile, url: c.page.url() });
  return c;
}

/** Make sure the Android app is logged in as `acc`: greeting on home names the user. */
export async function androidAs(acc) {
  await AF.ensureApp();
  await sleep(1500);
  let nodes = A.dump();
  if (A.find(nodes, "Войти через Telegram")) { await AF.login(acc); return; }
  await AF.goHome();
  nodes = A.dump();
  const greet = nodes.find((n) => /^(Доброе утро|Добрый день|Добрый вечер|Доброй ночи|Хәйерле)/.test(n.text || ""));
  if (greet && greet.text.includes(acc.firstName)) { log("android.as", { name: acc.firstName, already: true }); return; }
  await AF.logout();
  await AF.login(acc);
}

export function userIdOf(acc) { return q1("SELECT id FROM user WHERE telegram_id = ?", String(acc.telegramId))?.id; }
export function lastRideOf(acc) { return q1("SELECT * FROM ride WHERE driver_id = ? ORDER BY id DESC LIMIT 1", userIdOf(acc)); }
export function bookingsOfRide(rideId) { return q("SELECT * FROM booking WHERE ride_id = ? ORDER BY id", rideId); }
export function ridesOf(acc) { return q("SELECT * FROM ride WHERE driver_id = ? ORDER BY id", userIdOf(acc)); }

/** HH:MM of `minutes` from now in Ufa local time (device/browser timezone of the stand). */
export function ufaClock(minutes) {
  const t = new Date(Date.now() + minutes * 60000 + 5 * 3600000);
  return { hh: t.getUTCHours(), mm: t.getUTCMinutes(), day: t.getUTCDate(), date: new Date(Date.now() + minutes * 60000) };
}
