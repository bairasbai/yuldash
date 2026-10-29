// PROPOSAL V8 ONLY. 68 EXPECTED frames; source selectors checked, fresh review/build/runtime still required.
// node tools/rideshare_e2e/s06.mjs <uniqueTag>; external fixture seed is a separate root-owned setup.
import { readFileSync, lstatSync, realpathSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { Scenario, runScenario } from "./lib/scenario.mjs";
import { A, AF, ACC, q1, sleep, androidAs, userIdOf, standHealthy, wireFrom, wireSince, fault } from "./lib/stand.mjs";
import { REPO, DB_PATH, EVID, SCRATCH } from "./lib/common.mjs";
import { openProfile } from "./lib/pwa.mjs";
import { assertSavedOwnTripReady, expectedSavedTrip } from "./lib/offline-ready.mjs";
import { offlineControlGroups } from "./lib/offline-controls.mjs";
import { registeredPathContains, assertRegisteredOrdinaryPath } from "./lib/registered-path.mjs";
import * as P from "./lib/pwa-flows.mjs";

const TAILS = "C:/Users/Bayra/AppData/Local/yuldash/rideshare-claude-tails-20261024";
const OWNER = "RIDESHARE-CLAUDE-TAILS-20261024-chat01a16f71";
const PURPOSE = "synthetic-visual-state-not-user-action-not-03-acceptance";
const MANIFEST = join(TAILS, "visual-consent-fixtures.manifest.json");
const HELPER = join(TAILS, "visual-consent-fixtures.py");
const RECORDS = join(TAILS, "visual-consent-fixtures.records.jsonl");
const tag = process.argv[2] || new Date().toISOString().replace(/[^0-9]/g, "");
const sc = new Scenario(`W1-E2E-06-${tag}`, { requirement: "W1-E2E-06", scope: "44-base-plus-16-synthetic-consent-plus-8-android-cold-offline-states" });
A.setSerial(process.env.ANDROID_SERIAL || "emulator-5560");
const profiles = [{ lang: "ru", dark: false }, { lang: "ru", dark: true }, { lang: "ba", dark: false }, { lang: "ba", dark: true }];
const profileName = p => `${p.lang}-${p.dark ? "dark" : "light"}`;
const hash = s => createHash("sha256").update(s).digest("hex");
const normalized = p => resolve(p).replace(/\\/g, "/").toLowerCase();
const actors = [ACC.andDriver, ACC.andPassenger, ACC.pwaDriver, ACC.pwaPassenger];
const pairs = { A: { android: ACC.andPassenger, pwa: ACC.pwaDriver, androidRole: "passenger", pwaRole: "driver" }, B: { android: ACC.andDriver, pwa: ACC.pwaPassenger, androidRole: "driver", pwaRole: "passenger" } };
const nav = { map: /^Карта$/, profile: /^Профиль$/, trips: /^(Поездки|Сәфәрҙәр)$/, chat: /^Чат$/ };
const themeLabels = { system: /^(Как в системе|Системалағыса)$/, light: /^(Светлая|Яҡты)$/, dark: /^(Тёмная|Ҡараңғы)$/ };
const androidFonts = { normal: /^(Обычный|Ғәҙәти)$/, large: /^(Крупный|Эре)$/, xlarge: /^(Очень крупный|Бик эре)$/ };
const pwaFonts = { normal: /^(Обычный|Ғәҙәти)$/, large: /^(Крупный|Ҙур)$/, xlarge: /^(Очень крупный|Бик ҙур)$/ };
const consentText = {
  ru: { title: "Водитель просит исправить посадку", description: "Подтверди только если ты ещё не в машине.", yes: "Я ещё не сел", no: "Я в машине", pending: "Ждём подтверждения пассажира", onboard: "Пассажир в машине" },
  ba: { title: "Йөрөтөүсе ултырыу билдәһен төҙәтергә һорай", description: "Машинаға әле ултырмаған булһаң ғына раҫла.", yes: "Мин әле ултырманым", no: "Мин машиналамын", pending: "Пассажирҙың раҫлауын көтәбеҙ", onboard: "Пассажир машинала" },
};
const matrix = [], androidSaved = new Map(), clients = new Map();
let originalDevice, initialActor, currentActor, fixtureManifest;

function jsonFile(path) { return JSON.parse(readFileSync(path, "utf8").replace(/^\uFEFF/, "")); }
function ordinaryPath(path) {
  if (registeredPathContains(path, TAILS)) {
    assertRegisteredOrdinaryPath(path, TAILS);
    sc.check(true, "registered visual alias preserves the same owned file without links", { path });
    return;
  }
  for (let p = resolve(path); ; p = dirname(p)) {
    const s = lstatSync(p);
    sc.check(!s.isSymbolicLink() && normalized(realpathSync.native(p)) === normalized(p), "registered visual path has no symlink/junction", { path: p });
    if (dirname(p) === p) break;
  }
}
function fixtureGuards() {
  for (const path of [MANIFEST, HELPER, RECORDS, DB_PATH]) ordinaryPath(path);
  sc.check(process.env.RSACC_OWNER === OWNER && process.env.RSACC_VISUAL_REFRESH_APPROVED === PURPOSE, "root explicitly approved only owned synthetic refresh for visual 06");
  sc.check(normalized(DB_PATH) === normalized(join(TAILS, "stand/tails-api.db")) && normalized(SCRATCH) === normalized(join(TAILS, "stand")), "visual fixtures use exact owned SQLite, never old/production DB");
  sc.check(normalized(readFileSync(join(EVID, "current-db.txt"), "utf8").trim()) === normalized(DB_PATH), "read-only q and current stand database agree");
  const m = jsonFile(MANIFEST);
  sc.check(m.owner === OWNER && m.purpose === PURPOSE && m.result === "seeded" && normalized(m.database) === normalized(DB_PATH), "separate one-shot visual setup manifest is owned and seeded");
  sc.check(Object.keys(m.fixtures || {}).sort().join() === "A,B" && m.guards.helper_sha256 === hash(readFileSync(HELPER)), "fixture registration and reviewed helper bytes agree");
  sc.check(m.guards.server_bookings_sha256 === hash(readFileSync(join(REPO, "backend/app/routers/bookings.py"))), "fixture setup matches current server helper bytes");
  for (const [pair, spec] of Object.entries(pairs)) {
    const f = m.fixtures[pair];
    const passenger = pair === "A" ? ACC.andPassenger : ACC.pwaPassenger;
    const driver = pair === "A" ? ACC.pwaDriver : ACC.andDriver;
    sc.check([f.ride_id, f.booking_id, f.passenger_id, f.driver_id].every(n => Number.isSafeInteger(n) && n > 0), "fixture IDs are explicit positive integers", { pair });
    sc.check(f.passenger_id === userIdOf(passenger) && f.driver_id === userIdOf(driver) && userIdOf(spec.android) !== userIdOf(spec.pwa), "fixture pair and distinct actor identities agree", { pair });
  }
  sc.check(m.fixtures.A.ride_id !== m.fixtures.B.ride_id && m.fixtures.A.booking_id !== m.fixtures.B.booking_id, "two visual pairs have separate new ride/booking IDs");
  return m;
}
function epoch(value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+]00:00)?$/.test(value)) throw new Error("Invalid naive-UTC/UTC boarding timestamp");
  let v = value.replace(" ", "T").replace(/(?:Z|[+]00:00)$/, "");
  const [whole, fraction = ""] = v.split(".");
  v = `${whole}.${fraction.padEnd(6, "0")}`;
  return { canonical: v, ms: Date.parse(v + "Z") };
}
function fixtureState(pair, role, actor, phase) {
  const f = fixtureManifest.fixtures[pair];
  const row = q1("SELECT b.id,b.ride_id,b.passenger_id,b.status,b.onboard_at,b.boarding_undo_request_id,b.boarding_undo_requested_at,b.boarding_undo_answer,b.price,b.paid,b.payment_method,b.pay_amount,r.driver_id,r.status ride_status,r.comment,r.price ride_price FROM booking b JOIN ride r ON r.id=b.ride_id WHERE b.id=?", f.booking_id);
  sc.check(row && row.ride_id === f.ride_id && row.passenger_id === f.passenger_id && row.driver_id === f.driver_id && row.comment === `${OWNER}/${PURPOSE}/v1/${pair}`, "read-only projection binds exact owned visual rows", { pair, phase });
  sc.check(userIdOf(actor) === (role === "driver" ? row.driver_id : row.passenger_id), "current capture actor has the expected role", { pair, role, actor: actor.firstName, phase });
  sc.check(row.status === "onboard" && row.ride_status === "active" && row.boarding_undo_answer === null && !!row.boarding_undo_request_id && !!row.boarding_undo_requested_at, "visual request remains onboard and pending without answering", { pair, phase });
  sc.check(row.price === 0 && row.ride_price === 0 && row.paid === 0 && row.payment_method === "" && row.pay_amount === 0 && q1("SELECT COUNT(*) n FROM payment WHERE booking_id=?", f.booking_id).n === 0, "visual fixture has zero amount and no platform payment", { pair, phase });
  sc.check(q1("SELECT COUNT(*) n FROM booking WHERE ride_id=?", f.ride_id).n === 1, "visual ride contains only its one registered booking", { pair, phase });
  const onboard = epoch(row.onboard_at), requested = epoch(row.boarding_undo_requested_at);
  const [prefix, random, extra] = row.boarding_undo_request_id.split("~");
  sc.check(prefix === onboard.canonical && /^[A-Za-z0-9_-]{32}$/.test(random || "") && extra === undefined && requested.canonical === onboard.canonical, "synthetic request nonce is bound to the exact refreshed boarding epoch", { pair, phase });
  const age = Date.now() - onboard.ms;
  sc.check(Number.isFinite(age) && age >= 0 && age < 300000, "visual request is inside the real five-minute window", { pair, phase, age_ms: age });
  return { pair, booking_id: f.booking_id, ride_id: f.ride_id, actor_id: userIdOf(actor), role, status: row.status, pending: true, onboard_epoch_utc: onboard.canonical + "Z", request_sha256: hash(row.boarding_undo_request_id), window_left_ms: 300000 - age, checked_at: new Date().toISOString(), fixture_setup: PURPOSE };
}
function unchanged(before, after) {
  sc.check(before.booking_id === after.booking_id && before.actor_id === after.actor_id && before.role === after.role && before.onboard_epoch_utc === after.onboard_epoch_utc && before.request_sha256 === after.request_sha256, "pre/post screenshot retain the same role, boarding epoch and pending request", { pair: before.pair });
}
function refreshFixture(pair, profile) {
  fixtureManifest = fixtureGuards();
  const output = execFileSync(process.env.RSACC_VISUAL_PYTHON || "python", [HELPER, "refresh", "--pair", pair, "--profile", profileName(profile), "--apply-after-03-05"], { cwd: REPO, windowsHide: true, encoding: "utf8", timeout: 30000, maxBuffer: 256 * 1024 });
  const result = JSON.parse(output.trim());
  sc.check(result.result === "refreshed" && normalized(result.manifest) === normalized(MANIFEST), "external helper refreshed only the registered synthetic pair", { pair, profile: profileName(profile), fixture_setup: PURPOSE });
  const records = readFileSync(RECORDS, "utf8").trim().split(/\r?\n/).map(s => JSON.parse(s));
  const last = records.at(-1);
  sc.check(last.owner === OWNER && last.purpose === PURPOSE && last.command === "refresh" && last.pair === pair && last.profile === profileName(profile) && last.result === "refreshed", "refresh has its own owner/PID/profile journal", { pair, helper_pid: last.pid, helper_record_sha256: hash(JSON.stringify(last)) });
  return { record: RECORDS, record_sha256: hash(JSON.stringify(last)), helper_sha256: hash(readFileSync(HELPER)) };
}

async function androidHome() {
  for (let i = 0; i < 10; i++) {
    const nodes = A.dump();
    if (A.find(nodes, nav.map) && A.find(nodes, nav.profile)) { await A.tapText(nav.map); return; }
    A.back(); await sleep(500);
  }
  throw new Error("Authenticated Android bottom navigation unavailable");
}
async function androidSettings() {
  await androidHome(); await A.tapText(nav.profile); await sleep(400);
  try { await A.scrollTap(/^(Настройки|Көйләүҙәр)$/, { max: 24 }); }
  catch (error) {
    const label = `settings-lookup-failed-${Date.now()}`, diagnosticErrors = [];
    try { A.dump({ label }); } catch (diagnostic) { diagnosticErrors.push(`XML: ${diagnostic.message}`); }
    try { A.screencap(label); } catch (diagnostic) { diagnosticErrors.push(`PNG: ${diagnostic.message}`); }
    (sc.facts.android_settings_lookup_failures ||= []).push({ actor: currentActor?.firstName, at: new Date().toISOString(), error: error.message, label, diagnostic_errors: diagnosticErrors });
    throw error;
  }
  await A.scrollTo(/^(Язык|Тел)$/);
}
function visibleChoice(nodes, dictionary, kind, captureLabel) {
  const keys = Object.keys(dictionary).filter(k => !!A.find(nodes, dictionary[k]));
  const matches = Object.fromEntries(Object.entries(dictionary).map(([key, want]) =>
    [key, nodes.filter(n => !!A.find([n], want)).map(n => n.box)]));
  (sc.facts.android_settings_reads ||= []).push({ kind, capture_label: captureLabel, keys, key_count: keys.length, matches });
  if (keys.length !== 1) throw new Error(`Settings must display exactly one current value: ${kind}; keys=${keys.length}; XML=${captureLabel}`);
  const value = A.find(nodes, dictionary[keys[0]]), scrolls = nodes.filter(n => n.scrollable === "true" && n.box);
  sc.check(scrolls.length === 1, "settings current-value read has one actual scroll viewport", { kind, capture_label: captureLabel, scroll_count: scrolls.length });
  const viewport = scrolls[0].box;
  sc.check(value.box[0] >= viewport[0] && value.box[2] <= viewport[2] && value.box[1] >= viewport[1] && value.box[3] < viewport[3],
    "selected settings value is fully inside its actual scroll viewport", { kind, capture_label: captureLabel, box: value.box, viewport });
  return keys[0];
}
async function readAndroidChoice(dictionary, kind) {
  const before = A.dump(), scrolls = before.filter(n => n.scrollable === "true" && n.box);
  sc.check(scrolls.length === 1, "settings positioning has one actual scroll viewport", { kind, scroll_count: scrolls.length });
  const [, height] = A.screenSize(), maxBottom = Math.min(0.88, scrolls[0].box[3] / height);
  const values = new RegExp(Object.values(dictionary).map(want => `(?:${want.source})`).join("|"));
  // The heading can fit while its selected value is clipped below the real scroll boundary.
  await A.scrollTo(values, { maxBottom });
  const captureLabel = `settings-current-${kind}-${Date.now()}`;
  return visibleChoice(A.dump({ label: captureLabel }), dictionary, kind, captureLabel);
}
async function readAndroidPrefs() {
  await androidSettings();
  const nodes = A.dump();
  const ba = !!A.find(nodes, "Башҡортса"), ru = !!A.find(nodes, "Русский");
  sc.check(ba !== ru, "Android language value is unambiguous");
  const theme = await readAndroidChoice(themeLabels, "theme");
  const font = await readAndroidChoice(androidFonts, "font");
  return { lang: ba ? "ba" : "ru", theme, font };
}
async function applyAndroidPrefs(prefs) {
  await androidSettings();
  const ba = !!A.find(A.dump(), "Башҡортса");
  if (ba !== (prefs.lang === "ba")) await A.scrollTap(/^(Язык|Тел)$/);
  await A.scrollTap(/^Тема$/); await A.tapText(themeLabels[prefs.theme]);
  await A.scrollTap(/^(Размер текста|Текст ҙурлығы)$/); await A.tapText(androidFonts[prefs.font]);
  const actual = await readAndroidPrefs();
  sc.check(JSON.stringify(actual) === JSON.stringify(prefs), "Android language/theme/app-font really match selected values", { actor: currentActor?.firstName, ...actual });
}
async function identifyAndroid() {
  await androidHome();
  const greeting = /^(Доброе утро|Добрый день|Добрый вечер|Доброй ночи|Хәйерле|Тыныс төн)/;
  for (let i = 0; i < 5; i++) {
    const texts = A.dump().filter(n => greeting.test(n.text || "")).map(n => n.text);
    const matches = actors.filter(a => texts.some(t => t.includes(a.firstName)));
    if (matches.length === 1) return matches[0];
    await A.swipeDown(0.35, { from: 0.72 });
  }
  throw new Error("Initial/current Android actor cannot be verified through the real greeting UI");
}
async function selectAndroid(actor) {
  if (currentActor?.telegramId !== actor.telegramId) {
    if (currentActor) {
      const saved = androidSaved.get(currentActor.telegramId);
      if (saved) await applyAndroidPrefs(saved);
      // AF.logout/login use RU labels; retain saved original before this temporary UI toggle.
      await androidSettings(); if (A.find(A.dump(), "Башҡортса")) await A.scrollTap(/^(Язык|Тел)$/);
    }
    await androidAs(actor); currentActor = actor;
  }
  const identified = await identifyAndroid();
  sc.check(identified.telegramId === actor.telegramId, "Android real UI identifies the requested synthetic account", { actor: actor.firstName });
  if (!androidSaved.has(actor.telegramId)) androidSaved.set(actor.telegramId, await readAndroidPrefs());
}
async function nativeCapture(screen, profile, extra = {}) {
  const nodes = A.dump({ label: `${screen}-${profileName(profile)}` });
  const [w, h] = A.screenSize();
  const texts = nodes.filter(n => n.text || n["content-desc"]);
  sc.check(texts.length > 0 && texts.every(n => !n.box || (n.box[0] >= 0 && n.box[2] <= w + 1 && n.box[1] >= 0 && n.box[3] <= h + 1)), "Android accessible content remains inside the narrow display", { screen, ...profile });
  sc.check(Number(A.sh("settings get system font_scale", { quiet: true }).trim()) === 1.3, "Android real system font scale stays 1.3", { screen });
  const screenshot = A.screencap(`android-${screen}-${profileName(profile)}-320dp-font130`);
  matrix.push({ client: "android", screen, ...profile, actor_id: userIdOf(currentActor), width_dp: 320, system_font_scale: 1.3, app_font: "normal through UI", screenshot, ...extra, independent_visual_review: "not_executed" });
}

async function pwaSettings(page) { await page.goto(P.PWA + "/settings", { waitUntil: "domcontentloaded" }); await page.getByText(/^(Язык|Тел)$/, { exact: true }).waitFor(); }
async function readPwaPrefs(page) {
  await pwaSettings(page);
  const ba = await page.getByText("Башҡортса", { exact: true }).count(), ru = await page.getByText("Русский", { exact: true }).count();
  sc.check((ba === 1) !== (ru === 1), "PWA original language is unambiguous");
  const selected = async (title, labels) => {
    await page.getByText(title, { exact: true }).click();
    const found = [];
    for (const [key, name] of Object.entries(labels)) if (await page.getByRole("button", { name }).getAttribute("aria-pressed") === "true") found.push(key);
    await page.getByText(title, { exact: true }).click();
    sc.check(found.length === 1, "PWA settings expose exactly one selected original choice");
    return found[0];
  };
  return { lang: ba ? "ba" : "ru", theme: await selected("Тема", themeLabels), font: await selected(/^(Размер текста|Текст ҙурлығы)$/, pwaFonts) };
}
async function applyPwaPrefs(page, prefs) {
  await pwaSettings(page);
  const ba = await page.getByText("Башҡортса", { exact: true }).count() > 0;
  if (ba !== (prefs.lang === "ba")) await page.getByText(/^(Язык|Тел)$/, { exact: true }).click();
  await page.getByText("Тема", { exact: true }).click(); await page.getByRole("button", { name: themeLabels[prefs.theme] }).click();
  await page.getByText(/^(Размер текста|Текст ҙурлығы)$/, { exact: true }).click(); await page.getByRole("button", { name: pwaFonts[prefs.font] }).click();
  const actual = await readPwaPrefs(page);
  sc.check(JSON.stringify(actual) === JSON.stringify(prefs), "PWA language/theme/text-size values match through UI", actual);
}
async function pwaFor(actor) {
  if (clients.has(actor.profile)) return clients.get(actor.profile);
  const client = await openProfile(actor.profile, { width: 320, height: 760 });
  clients.set(actor.profile, { ...client, actor, saved: null }); sc.pages = [...clients.values()].map(c => c.page);
  client.page._tag = actor.profile;
  const me = client.page.waitForResponse(r => new URL(r.url()).pathname === "/me" && r.status() === 200, { timeout: 20000 }).then(r => r.json()).catch(() => null);
  // Profiles must already be authenticated by prior real scenarios; no login/API preparation here.
  await client.page.goto(P.PWA + "/profile", { waitUntil: "domcontentloaded" });
  const own = await me;
  sc.check(own?.id === userIdOf(actor), "PWA's real UI request authenticated the correct existing profile", { actor: actor.firstName, user_id: own?.id });
  await client.page.getByText(actor.firstName, { exact: true }).first().waitFor();
  const registered = clients.get(actor.profile); registered.saved = await readPwaPrefs(client.page);
  return registered;
}
async function browserCapture(page, actor, screen, profile, extra = {}) {
  await page.waitForFunction(() => document.body.innerText.trim().length > 0, null, { timeout: 20000 });
  await page.locator(".skeleton-card").first().waitFor({ state: "hidden", timeout: 20000 });
  await sleep(500);
  const d = await page.evaluate(() => {
    const zoom = Number(getComputedStyle(document.documentElement).zoom);
    const installActions = [...document.querySelectorAll(".install-sheet__actions > button")].flatMap(el => {
      const r = el.getBoundingClientRect(), s = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && s.visibility !== "hidden" && s.display !== "none"
        ? [{ left: r.left, right: r.right, width: r.width / zoom, height: r.height / zoom, client: el.clientWidth, scroll: el.scrollWidth }] : [];
    });
    return { width: innerWidth, scroll: document.documentElement.scrollWidth, text: document.body.innerText.length, theme: document.documentElement.dataset.theme, scale: document.documentElement.dataset.fontScale, zoom, installActions, state: document.querySelector(".state__icon--danger") ? "error" : document.querySelector(".state") ? "empty-or-state" : "content" };
  });
  sc.check(d.width === 320 && d.scroll <= 321 && d.text > 0, "PWA narrow viewport has content without horizontal overflow", { screen, width: d.width, scroll: d.scroll });
  if (d.installActions.length) sc.check(d.installActions.every(b => b.left >= -1 && b.right <= d.width + 1 && b.width >= 47.5 && b.height >= 47.5 && b.scroll <= b.client + 1), "actual visible install actions fit both viewport edges and retain readable 48px targets", { screen, buttons: d.installActions });
  sc.check(d.theme === (profile.dark ? "dark" : "light") && d.scale === "xlarge" && Math.abs(d.zoom - 1.3) < 0.01, "PWA really applies theme and xlarge zoom", { screen, ...d });
  const screenshot = await P.shot(page, `matrix-${screen}-${profileName(profile)}-320-xlarge.png`);
  matrix.push({ client: "pwa", screen, ...profile, actor_id: userIdOf(actor), width_px: 320, app_font: "xlarge through UI", state_observed: d.state, screenshot, ...extra, independent_visual_review: "not_executed" });
}

// Source contract checked against actual component, stop labels and Back accessibility label.
const OFFLINE_UI = {
  status: "verified-current-source", componentSource: "android/app/src/main/java/com/yuldash/app/OfflineActiveTripSnapshot.kt", componentSha256: "11c31a8804679691b014380f29cc9433c3149c8279c60500afc0e9347a4b4e4c",
  dependencies: [{"path": "android/app/src/main/java/com/yuldash/app/PickupStopPicker.kt", "sha256": "a08b22e777c6606e4430178a3a5cb6472e67555e39e820b013a26cfc4fdd55fa"}, {"path": "android/app/src/main/java/com/yuldash/app/YuldashApp.kt", "sha256": "d3bc1b74bdf9f93497b315ce3d2c1c8feb808107c8a57843512ac9bd76b8f2d8"}],
  header: { ru: "Последние сохранённые данные", ba: "Һуңғы һаҡланған мәғлүмәт" },
  warning: { ru: "Показываем сохранённую поездку. Состояние могло измениться.", ba: "Һаҡланған сәфәрҙе күрһәтәбеҙ. Хәл үҙгәргән булыуы мөмкин." },
  role: { passenger: { ru: "Ты пассажир", ba: "Һин пассажир" }, driver: { ru: "Ты водитель", ba: "Һин йөрөтөүсе" } },
  lastOnboard: { ru: "Последнее состояние: Пассажир сел", ba: "Һуңғы хәл: Пассажир ултырҙы" },
  savedTime: { ru: /^Сохранено:\s*\S/, ba: /^Һаҡланған ваҡыт:\s*\S/ },
  allowedControls: { ru: [/^Назад$/, /^Повторить$/], ba: [/^Артҡа$/, /^Ҡабатлау$/] },
};
let proxyConfirmed = false, faultRecoveryRequired = false;
function verifyOfflineUIContract() {
  sc.check(OFFLINE_UI.status === "verified-current-source" && typeof OFFLINE_UI.componentSource === "string" && /^[a-f0-9]{64}$/.test(OFFLINE_UI.componentSha256 || ""), "offline selectors remain pending until root verifies the actual finished component");
  sc.check(OFFLINE_UI.componentSource.startsWith("android/app/src/main/java/com/yuldash/app/") && !OFFLINE_UI.componentSource.includes(".."), "offline source contract identifies an ordinary Android component");
  const source = join(REPO, OFFLINE_UI.componentSource); ordinaryPath(source);
  sc.check(hash(readFileSync(source)) === OFFLINE_UI.componentSha256, "read-only UI component bytes match the reviewed selector contract");
  for (const dep of OFFLINE_UI.dependencies) { const path = join(REPO, dep.path); ordinaryPath(path); sc.check(hash(readFileSync(path)) === dep.sha256, "stop/Back selectors match their actual source dependency", { source: dep.path }); }
  for (const lang of ["ru", "ba"]) sc.check(OFFLINE_UI.savedTime[lang] instanceof RegExp && Array.isArray(OFFLINE_UI.allowedControls[lang]) && OFFLINE_UI.allowedControls[lang].length > 0 && OFFLINE_UI.allowedControls[lang].every(v => v instanceof RegExp), "offline timestamp and allowed controls were verified against real source", { lang });
}
async function recoverProxy(reason) {
  if (!proxyConfirmed) return;
  const result = await fault({ mode: "up", tag: `W1-06-${tag}-${reason}` });
  sc.check(result.ok === true && result.down === false, "owned proxy is restored before settings or the next profile", { reason });
  faultRecoveryRequired = false;
}
async function nativeBookingIntent(pair, profile, suffix) {
  const f = fixtureManifest.fixtures[pair], actor = pairs[pair].android;
  A.adb(["shell", "am", "start", "-W", "-n", `${A.PKG}/.MainActivity`, "--es", "type", "booking", "--es", "id", String(f.booking_id), "--es", "recipient_user_id", String(userIdOf(actor)), "--es", "yuldash_navigation_delivery_id", `${tag}-${pair}-${profileName(profile)}-${suffix}-${Date.now()}`]);
}
async function nativeLiveNavigation(pair, profile, suffix) {
  await nativeBookingIntent(pair, profile, suffix);
  // Existing online passenger booking links enter Booking first, not ActiveTrip.
  if (pairs[pair].androidRole === "passenger") await A.scrollTap(/^(Открыть поездку|Сәфәрҙе асыу)$/, { maxBottom: 0.96, max: 12 });
}
async function waitOwnNativeDetails(pair, cursor) {
  const id = fixtureManifest.fixtures[pair].booking_id, deadline = Date.now() + 30000;
  do {
    const found = (await wireSince(cursor)).filter(e => e.client === "android" && e.method === "GET" && e.status === 200 && new RegExp(`^/bookings/${id}/details(?:\\?|$)`).test(e.path));
    if (found.length) return found.map(e => ({ client: e.client, method: e.method, path: e.path, status: e.status }));
    await sleep(350);
  } while (Date.now() < deadline);
  throw new Error("No actual Android GET/details200 for this visual booking: role alone cannot warm full payload");
}
function offlineStops(pair, profile) {
  const f = fixtureManifest.fixtures[pair];
  const row = q1("SELECT b.passenger_id,b.pickup_stop,b.dropoff_stop,r.driver_id,r.from_city,r.to_city,length(COALESCE(b.boarding_code,'')) code_length FROM booking b JOIN ride r ON r.id=b.ride_id WHERE b.id=? AND r.id=?", f.booking_id, f.ride_id);
  sc.check(row && row.passenger_id === f.passenger_id && row.driver_id === f.driver_id && row.pickup_stop !== null && row.dropoff_stop !== null && row.code_length === 0, "visual offline route is explicit, owned and has no boarding-code payload", { pair });
  const stops = [row.pickup_stop || row.from_city, row.dropoff_stop || row.to_city];
  sc.check(stops.every(v => typeof v === "string" && v.length > 0) && stops[0] !== stops[1], "both personal stop labels are explicit; blank stop means the real route endpoint", { pair });
  // Match the full personal-stop nodes, not the same city borrowed from the route title.
  return [
    row.pickup_stop === "" ? (profile.lang === "ru" ? `Посадка: начало маршрута · ${row.from_city}` : `Ултырыу: юлдың башы · ${row.from_city}`)
      : (profile.lang === "ru" ? `Посадка: ${row.pickup_stop}` : `Ултырыу: ${row.pickup_stop}`),
    row.dropoff_stop === "" ? (profile.lang === "ru" ? `Высадка: конец маршрута · ${row.to_city}` : `Төшөү: юлдың аҙағы · ${row.to_city}`)
      : (profile.lang === "ru" ? `Высадка: ${row.dropoff_stop}` : `Төшөү: ${row.dropoff_stop}`),
  ];
}
// Bounded accessibility polling: no cache/prefs injection; only own transient UI dump XML.
async function offlineNodesBefore(deadline) {
  const remote = `/data/local/tmp/rsacc-visual-offline-${process.pid}-${Date.now()}.xml`;
  const timeout = () => Math.max(1, Math.min(8000, deadline - Date.now()));
  try {
    if (Date.now() >= deadline) return [];
    A.adb(["shell", "uiautomator", "dump", remote], { quiet: true, timeout: timeout() });
    if (Date.now() >= deadline) return [];
    return A.parse(A.adb(["exec-out", "cat", remote], { quiet: true, timeout: timeout() }));
  } catch { return []; }
  finally { try { A.adb(["shell", "rm", "-f", remote], { quiet: true, timeout: 1000 }); } catch {} }
}
function offlineRequiredLabels(pair, profile, stops) {
  return [OFFLINE_UI.header[profile.lang], OFFLINE_UI.warning[profile.lang], OFFLINE_UI.role[pairs[pair].androidRole][profile.lang], OFFLINE_UI.lastOnboard[profile.lang], ...stops];
}
function offlinePrivateAndActionChecks(nodes, pair, profile) {
  const values = nodes.map(n => [n.text || "", n["content-desc"] || ""].join(" "));
  const rendered = values.join("\n"), digits = rendered.replace(/[^0-9]/g, "");
  const knownPhones = actors.map(a => String(a.phone || "").replace(/[^0-9]/g, "")).filter(Boolean);
  sc.check(!knownPhones.some(v => digits.includes(v)) && !/(?:\+7|8)[\s()-]*(?:\d[\s()-]*){10}\b/.test(rendered), "cold read-only screen contains no participant phone", { pair });
  sc.check(!values.some(v => /код\s+(?:для\s+)?посадки|ултырыу\s+коды/i.test(v)), "cold read-only screen has no boarding-code control", { pair });
  sc.check(!nodes.some(n => /mapkit|mapview/i.test(n.class || "") || /^(Карта|Map)$/i.test(n["content-desc"] || n.text || "")), "cold read-only accessibility screen has no map surface/control", { pair });
  const allowed = OFFLINE_UI.allowedControls[profile.lang];
  const controls = offlineControlGroups(nodes, allowed);
  sc.check(controls.permitted, "cold read-only screen permits only source-reviewed Retry/Back controls, including disabled buttons", { pair, clickable_controls: controls.controls.length, action_groups: controls.controls });
  const text = consentText[profile.lang];
  sc.check(!A.find(nodes, text.yes) && !A.find(nodes, text.no) && !values.some(v => /Исправить посадку|Ултырыуҙы төҙәтергә/.test(v)), "offline snapshot offers no consent answer or boarding mutation", { pair });
}
async function waitOfflineReadOnly(pair, profile, stops) {
  const deadline = Date.now() + 120000, required = offlineRequiredLabels(pair, profile, stops);
  while (Date.now() < deadline) {
    const nodes = await offlineNodesBefore(deadline);
    const timestamp = nodes.find(n => OFFLINE_UI.savedTime[profile.lang].test(n.text || "") && n.box && n.box[2] > n.box[0] && n.box[3] > n.box[1]);
    if (required.every(label => !!A.find(nodes, label)) && timestamp) {
      const [w, h] = A.screenSize();
      const checked = [...required.map(label => ({ label, node: A.find(nodes, label) })), { label: "saved-time", node: timestamp }];
      for (const { label, node: n } of checked) sc.check(n.box[0] >= 0 && n.box[2] <= w && n.box[1] >= h * 0.03 && n.box[3] < h * 0.96, "read-only header/role/stops/last-stage/warning/time fit the same cold frame", { pair, role: pairs[pair].androidRole, label, box: [...n.box], viewport: [w, h], top_min: h * 0.03, bottom_max: h * 0.96 });
      offlinePrivateAndActionChecks(nodes, pair, profile);
      return { waited_until: new Date().toISOString(), selector_contract: "verified-current-source", visible_last_stage: "onboard", saved_time_text_present: true, saved_time_bounds: timestamp.box };
    }
    if (Date.now() < deadline) await sleep(Math.min(400, deadline - Date.now()));
  }
  throw new Error("Actual cold read-only header, role, both stops, saved onboard stage/time and warning did not appear within 120 seconds");
}
async function nativeColdOffline(pair, profile, refresh) {
  const spec = pairs[pair], f = fixtureManifest.fixtures[pair];
  const initial = fixtureState(pair, spec.androidRole, spec.android, "offline-pre-warm"), stops = offlineStops(pair, profile);
  A.forceStop(); A.launch();
  const cursor = await wireFrom();
  await nativeLiveNavigation(pair, profile, "full-details-warm");
  const warmDetails = await waitOwnNativeDetails(pair, cursor);
  const expected = expectedSavedTrip({ bookingId: f.booking_id, actor: spec.android, role: spec.androidRole, hasStops: false });
  const segment = (profile.lang === "ru" ? "Участок: " : "Юл өлөшө: ") + `${expected.pickup || expected.from} → ${expected.dropoff || expected.to}`;
  await A.scrollTo(segment, { maxBottom: 0.96, max: 12 });
  const readiness = await assertSavedOwnTripReady(sc, { bookingId: f.booking_id, actor: spec.android, role: spec.androidRole, hasStops: false }, `${pair}-${profileName(profile)}-before-cut`);
  A.forceStop(); A.launch();
  const reopenedCursor = await wireFrom();
  await nativeLiveNavigation(pair, profile, "after-readiness");
  const reopenedDetails = await waitOwnNativeDetails(pair, reopenedCursor);
  await A.scrollTo(segment, { maxBottom: 0.96, max: 12 });
  await A.scrollTo(spec.androidRole === "passenger" ? consentText[profile.lang].title : consentText[profile.lang].pending, { maxBottom: 0.96, max: 12 });
  const before = fixtureState(pair, spec.androidRole, spec.android, "offline-pre-cut"); unchanged(initial, before);
  sc.check(before.window_left_ms > 135000, "same pending boarding epoch has enough real five-minute window for the bounded cold probe", { pair, window_left_ms: before.window_left_ms });
  const trafficFrom = await wireFrom();
  faultRecoveryRequired = true; // Set before awaiting DOWN: a lost control response must still trigger UP.
  try {
    const down = await fault({ mode: "down", tag: `W1-06-${pair}-${profileName(profile)}-synthetic-offline` });
    sc.check(down.ok === true && down.down === true, "owned proxy is really DOWN for the cold Android UI probe", { pair });
    const oldPid = A.pid(); sc.check(!!oldPid, "real old Android process exists before force-stop", { pair, old_pid: oldPid });
    A.forceStop(); sc.check(A.pid() === null, "force-stop genuinely removed the Android process", { pair });
    A.launch(); const newPid = A.pid();
    sc.check(!!newPid && newPid !== oldPid, "cold Android launch has a new real PID", { pair, old_pid: oldPid, new_pid: newPid });
    await nativeBookingIntent(pair, profile, "offline-read-only");
    const observed = await waitOfflineReadOnly(pair, profile, stops);
    const pre = fixtureState(pair, spec.androidRole, spec.android, "offline-pre-shot"); unchanged(before, pre);
    await nativeCapture(`${pair}-${spec.androidRole}-cold-offline-booking${f.booking_id}`, profile, { kind: "synthetic-offline", fixture_setup: PURPOSE, refresh, before: pre, warm_details_ui_requests: warmDetails, durable_readiness: readiness, after_readiness_ui_requests: reopenedDetails, cold_process: { old_pid: oldPid, new_pid: newPid }, read_only: observed, legacy_outbox_initial_empty: "measured_readable_empty_by_actual_instrumentation", no_global_zero_POST_or_GPS_claim: true });
    const after = fixtureState(pair, spec.androidRole, spec.android, "offline-post-shot"); unchanged(pre, after); matrix.at(-1).after = after;
    const traffic = await wireSince(trafficFrom);
    matrix.at(-1).android_POST_attempts_observed = traffic.filter(e => e.client === "android" && e.method === "POST").length;
    matrix.at(-1).queue_scope = "Legacy OutboxAutoFlush remains unchanged; actual instrumentation measured a readable empty old queue. TCP-cut wire evidence cannot count HTTP attempts before parsing, so there is no globally-zero-POST/GPS claim.";
  } finally {
    await recoverProxy(`profile-${pair}-${profileName(profile)}-finally`);
  }
}

async function nativeConsent(pair, profile, refresh) {
  const spec = pairs[pair], role = spec.androidRole, f = fixtureManifest.fixtures[pair], text = consentText[profile.lang];
  const before = fixtureState(pair, role, spec.android, "pre-navigation");
  const wireStart = await wireFrom();
  await nativeLiveNavigation(pair, profile, "online-consent");
  const labels = role === "passenger" ? [text.title, text.description, text.yes, text.no] : [text.pending, text.onboard];
  if (role === "driver") {
    await A.waitFor(labels[0]);
    await A.waitFor(labels.at(-1));
  }
  await A.scrollTo(labels[0], { maxBottom: 0.96, max: 12 }); await A.scrollTo(labels.at(-1), { maxBottom: 0.96, max: 12 });
  const nodes = A.dump(), [w, h] = A.screenSize();
  for (const label of labels) { const n = A.find(nodes, label); sc.check(n && n.box[0] >= 0 && n.box[2] <= w && n.box[1] >= h * 0.03 && n.box[3] < h * 0.96 && n.enabled !== "false", "exact native consent/driver label is visible in one frame", { pair, role, label }); }
  sc.check(!A.find(nodes, role === "passenger" ? text.pending : text.title), "Android visible consent state matches the intended actor role", { pair, role });
  const observed = (await wireSince(wireStart)).filter(e => e.client === "android" && e.method === "GET" && e.status === 200 && new RegExp(`^/bookings/${f.booking_id}/(?:role|details)(?:\\?|$)`).test(e.path));
  sc.check(observed.length > 0, "real Android navigation fetched exactly the intended booking after refresh", { pair, booking_id: f.booking_id });
  const pre = fixtureState(pair, role, spec.android, "pre-shot"); unchanged(before, pre);
  const screen = `${pair}-${role === "passenger" ? "passenger-consent" : "driver-pending"}-booking${f.booking_id}`;
  await nativeCapture(screen, profile, { kind: "synthetic-consent", fixture_setup: PURPOSE, refresh, before: pre, ui_requests: observed.map(e => ({ client: e.client, method: e.method, path: e.path, status: e.status })) });
  const after = fixtureState(pair, role, spec.android, "post-shot"); unchanged(pre, after); matrix.at(-1).after = after;
}
async function browserConsent(pair, profile, client, refresh) {
  const spec = pairs[pair], role = spec.pwaRole, f = fixtureManifest.fixtures[pair], page = client.page, text = consentText[profile.lang];
  const before = fixtureState(pair, role, spec.pwa, "pre-navigation");
  const response = page.waitForResponse(r => new URL(r.url()).pathname === `/bookings/${f.booking_id}/role` && r.status() === 200, { timeout: 20000 }).then(r => r.json()).catch(() => null);
  await page.goto(P.PWA + `/trip/${f.booking_id}`, { waitUntil: "domcontentloaded" });
  const actual = await response;
  sc.check(actual?.role === role && actual.status === "onboard" && actual.boarding_undo_pending === true && actual.boarding_undo_wait_sec > 0 && actual.boarding_undo_wait_sec <= 300 && epoch(actual.onboard_at).canonical + "Z" === before.onboard_epoch_utc && hash(actual.boarding_undo_request_id || "") === before.request_sha256, "PWA actual trip response matches role, pending and the current boarding epoch", { pair, role });
  const labels = role === "passenger" ? [text.title, text.description, text.yes, text.no] : [text.pending, text.onboard];
  await page.getByText(labels[0], { exact: true }).waitFor();
  await page.getByText(labels.at(-1), { exact: true }).scrollIntoViewIfNeeded();
  for (const label of labels) {
    const element = page.getByText(label, { exact: true }); await element.waitFor(); const box = await element.boundingBox();
    sc.check(box && box.x >= 0 && box.x + box.width <= 321 && box.y >= 0 && box.y + box.height <= 760, "exact PWA consent/driver label fits the captured viewport", { pair, role, label });
  }
  if (role === "passenger") for (const name of [text.yes, text.no]) sc.check(await page.getByRole("button", { name, exact: true }).isEnabled(), "both passenger choices remain enabled without being clicked", { pair, name });
  sc.check(await page.getByText(role === "passenger" ? text.pending : text.title, { exact: true }).count() === 0, "PWA visible role matches the intended consent state", { pair, role });
  const pre = fixtureState(pair, role, spec.pwa, "pre-shot"); unchanged(before, pre);
  await browserCapture(page, spec.pwa, `${pair}-${role === "passenger" ? "passenger-consent" : "driver-pending"}-booking${f.booking_id}`, profile, { kind: "synthetic-consent", fixture_setup: PURPOSE, refresh, before: pre });
  const after = fixtureState(pair, role, spec.pwa, "post-shot"); unchanged(pre, after); matrix.at(-1).after = after;
}

// Deliberately fail closed before provenance/ADB if the component contract is still pending.
verifyOfflineUIContract();
await runScenario(sc, async () => {
  sc.facts.local_execution = "not_completed";
  sc.facts.requirement_acceptance = "not_accepted";
  sc.facts.unresolved_requirements = [{ requirement: "BA native-speaker acceptance", status: "deferred_by_user", owner: "Александр" }, { requirement: "independent screenshot review", status: "not_executed" }, { requirement: "all other screens/states and physical 07", status: "not_executed" }];
  sc.facts.coverage = { base: { android: ["map", "profile", "settings", "trips", "chat"], pwa: ["map", "profile", "settings", "trips", "chat", "driver-cabinet"], actors: { android: { role: "driver-account", name: ACC.andDriver.firstName, synthetic_telegram_id: ACC.andDriver.telegramId }, pwa: { role: "driver-account", name: ACC.pwaDriver.firstName, synthetic_telegram_id: ACC.pwaDriver.telegramId } }, expected: 44 }, synthetic_visual: { android: ["passenger-consent", "driver-pending"], pwa: ["passenger-consent", "driver-pending"], expected: 16, fixture_setup: PURPOSE, proves_scenario03: false } };
  await standHealthy(sc); proxyConfirmed = true; fixtureManifest = fixtureGuards();
  sc.facts.coverage.synthetic_android_offline = { expected: 8, pairs: { A: "android-passenger", B: "android-driver" }, profiles: profiles.map(profileName), fixture_setup: PURPOSE, pwa_offline_capture: false, proves_scenario03: false };
  sc.facts.expected_matrix_rows = 68;
  sc.facts.offline_selector_contract = { component: OFFLINE_UI.componentSource, sha256: OFFLINE_UI.componentSha256, status: OFFLINE_UI.status };
  sc.facts.legacy_queue_boundary = "Actual per-profile instrumentation must prove readable empty Outbox and encrypted own snapshot; legacy flush/GPS policies unchanged, no global zero-POST/GPS acceptance claimed";
  sc.facts.visual_setup = { manifest: MANIFEST, manifest_sha256: hash(readFileSync(MANIFEST)), helper: HELPER, helper_sha256: hash(readFileSync(HELPER)), purpose: PURPOSE };
  await AF.ensureApp(); initialActor = currentActor = await identifyAndroid();
  androidSaved.set(initialActor.telegramId, await readAndroidPrefs());
  const device = { density: A.sh("wm density", { quiet: true }), font: A.sh("settings get system font_scale", { quiet: true }).trim() };
  const physical = /Physical density:\s*(\d+)/.exec(device.density), override = /Override density:\s*(\d+)/.exec(device.density);
  sc.check(!!physical && Number(device.font) > 0, "original device density and font can be restored exactly"); originalDevice = device;
  const [width] = A.screenSize(), density = Math.round(width / 320 * 160);
  A.sh(`wm density ${density}`, { quiet: true }); A.sh("settings put system font_scale 1.3", { quiet: true }); A.resetSizeCache(); await sleep(800);
  sc.facts.android_narrow_display = { width_px: width, configured_density_dpi: density, width_dp: width * 160 / density, original_override_dpi: override ? Number(override[1]) : null };
  sc.check(Math.abs(width * 160 / density - 320) <= 1, "Android effective width is 320dp within density rounding");
  await selectAndroid(ACC.andDriver); const basePwa = await pwaFor(ACC.pwaDriver);
  for (const profile of profiles) {
    await sc.step(`base Android ${profileName(profile)}`, async () => {
      await applyAndroidPrefs({ lang: profile.lang, theme: profile.dark ? "dark" : "light", font: "normal" }); await nativeCapture("settings", profile, { kind: "base" });
      for (const screen of ["map", "profile", "trips", "chat"]) { await androidHome(); await A.tapText(nav[screen]); await sleep(800); await nativeCapture(screen, profile, { kind: "base" }); }
    });
    await sc.step(`base PWA ${profileName(profile)}`, async () => {
      await applyPwaPrefs(basePwa.page, { lang: profile.lang, theme: profile.dark ? "dark" : "light", font: "xlarge" }); await browserCapture(basePwa.page, basePwa.actor, "settings", profile, { kind: "base" });
      for (const [screen, path] of [["map", "/map"], ["profile", "/profile"], ["trips", "/rides"], ["chat", "/chat"], ["driver-cabinet", "/driver"]]) { await basePwa.page.goto(P.PWA + path, { waitUntil: "domcontentloaded" }); sc.check(!/login|intro|onboarding/.test(basePwa.page.url()), "base PWA screen is authenticated", { screen }); await browserCapture(basePwa.page, basePwa.actor, screen, profile, { kind: "base" }); }
    });
  }
  for (const [pair, spec] of Object.entries(pairs)) {
    await selectAndroid(spec.android); const client = await pwaFor(spec.pwa);
    for (const profile of profiles) await sc.step(`synthetic visual ${pair}/${profileName(profile)}`, async () => {
      await applyAndroidPrefs({ lang: profile.lang, theme: profile.dark ? "dark" : "light", font: "normal" });
      await applyPwaPrefs(client.page, { lang: profile.lang, theme: profile.dark ? "dark" : "light", font: "xlarge" });
      const refresh = refreshFixture(pair, profile);
      await nativeConsent(pair, profile, refresh); await browserConsent(pair, profile, client, refresh);
      await nativeColdOffline(pair, profile, refresh); // PWA frames are captured before this shared API cut; its browser stays network-online, but the proxy temporarily cuts its API too.
    });
  }
  sc.check(matrix.filter(r => r.kind === "base").length === 44 && matrix.filter(r => r.kind === "synthetic-consent").length === 16 && matrix.filter(r => r.kind === "synthetic-offline").length === 8 && matrix.length === 68 && new Set(matrix.map(r => `${r.client}/${r.screen}/${profileName(r)}`)).size === 68, "all 44 base, 16 consent and 8 cold Android offline states were captured exactly once");
  sc.facts.local_execution = "executed_matrix_only_not_independent_acceptance";
}, async () => {
  sc.facts.executed_matrix = matrix;
  const errors = [];
  // Always attempt UP before UI settings restoration, even if the per-profile attempt failed.
  if (proxyConfirmed) try { await recoverProxy("outer-finally"); } catch (e) { errors.push("Proxy UP: " + e.message); }
  for (const client of clients.values()) {
    try { if (client.saved && !faultRecoveryRequired) await applyPwaPrefs(client.page, client.saved); else if (client.saved) errors.push("PWA preferences skipped until owned proxy is restored: " + client.actor.firstName); }
    catch (e) { errors.push(`PWA preferences ${client.actor.firstName}: ${e.message}`); }
    finally { try { await client.ctx.close(); } catch (e) { errors.push(`PWA close ${client.actor.firstName}: ${e.message}`); } }
  }
  // Return physical settings first so app/account restoration uses the original display.
  if (originalDevice) try {
    const override = /Override density:\s*(\d+)/.exec(originalDevice.density);
    A.sh(override ? `wm density ${override[1]}` : "wm density reset", { quiet: true });
    A.sh(`settings put system font_scale ${Number(originalDevice.font)}`, { quiet: true }); A.resetSizeCache();
    sc.check(A.sh("wm density", { quiet: true }).trim() === originalDevice.density.trim() && Number(A.sh("settings get system font_scale", { quiet: true }).trim()) === Number(originalDevice.font), "original device density/font were actually restored");
  } catch (e) { errors.push(`Android device restoration: ${e.message}`); }
  // Preferences are app/device-wide on Android; preserve every observed account snapshot,
  // restore non-initial accounts first, then return the initial account and its initial settings last.
  const restoreActors = actors.filter(a => androidSaved.has(a.telegramId) && a.telegramId !== initialActor?.telegramId);
  if (initialActor) restoreActors.push(initialActor);
  if (!faultRecoveryRequired) {
    for (const actor of restoreActors) try { await selectAndroid(actor); await applyAndroidPrefs(androidSaved.get(actor.telegramId)); } catch (e) { errors.push(`Android preferences/account ${actor.firstName}: ${e.message}`); }
  } else if (restoreActors.length) errors.push("Android app/account preferences skipped until owned proxy is restored");
  sc.facts.preference_restoration = { android_snapshots: [...androidSaved.entries()].map(([actor_id, prefs]) => ({ synthetic_telegram_id: actor_id, ...prefs })), initial_actor: initialActor?.firstName, pwa_profiles: [...clients.keys()], errors };
  sc.facts.visual_fixtures_cleanup = "not retired by UI runner; root retains owned visual IDs until evidence acceptance, then external helper retire";
  if (errors.length) throw new Error(errors.join("; "));
});
