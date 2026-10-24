// W1-E2E-06: execute a stated screen matrix; screenshots still require independent review.
// node tools/rideshare_e2e/s06.mjs [runTag]
import { Scenario, runScenario } from "./lib/scenario.mjs";
import { A, AF, ACC, sleep, pwaClient, androidAs, standHealthy } from "./lib/stand.mjs";
import * as P from "./lib/pwa-flows.mjs";

const tag = process.argv[2] || new Date().toISOString().replace(/[^0-9]/g, "");
const sc = new Scenario(`W1-E2E-06-${tag}`, { requirement: "W1-E2E-06", scope: "explicit-local-screen-matrix" });
A.setSerial(process.env.ANDROID_SERIAL || "emulator-5560");
const matrix = [];
const profiles = [{ lang: "ru", dark: false }, { lang: "ru", dark: true }, { lang: "ba", dark: false }, { lang: "ba", dark: true }];
const nav = { map: /^Карта$/, trips: /^(Поездки|Сәфәрҙәр)$/, chat: /^Чат$/, profile: /^Профиль$/ };
let pwa, originalDevice;
let androidSettingsSaved = null;

async function androidHome() {
  for (let i = 0; i < 9; i++) {
    const nodes = A.dump();
    if (A.find(nodes, nav.map) && A.find(nodes, nav.profile)) { await A.tapText(nav.map); return; }
    A.back(); await sleep(500);
  }
  throw new Error("Android bottom navigation is unavailable; no direct app-state injection is permitted");
}
async function androidSettings() {
  await androidHome(); await A.tapText(nav.profile); await sleep(500);
  await A.scrollTap(/^(Настройки|Көйләүҙәр)$/); await A.scrollTo(/^(Язык|Тел)$/);
}
async function androidProfile({ lang, dark }) {
  await androidSettings();
  const isBa = !!A.find(A.dump(), "Башҡортса");
  if (!androidSettingsSaved) {
    await A.scrollTo(/^Тема$/);
    const texts = A.dump().map(n => n.text || "");
    androidSettingsSaved = { lang: isBa ? "ba" : "ru", theme: texts.find(t => /^(Тёмная|Светлая|Как в системе|Ҡараңғы|Яҡты|Системалағыса)$/.test(t)) };
    sc.check(!!androidSettingsSaved.theme, "original Android theme is recorded before changing it");
  }
  if (isBa !== (lang === "ba")) { await A.scrollTap(/^(Язык|Тел)$/); await sleep(400); }
  await A.scrollTap(/^Тема$/);
  await A.tapText(dark ? /^(Тёмная|Ҡараңғы)$/ : /^(Светлая|Яҡты)$/);
  await sleep(500);
  await A.scrollTo(/^(Язык|Тел)$/);
  sc.check(!!A.find(A.dump(), lang === "ba" ? "Башҡортса" : "Русский"), "Android language selected through settings", { lang });
}
async function nativeCapture(screen, profile) {
  const nodes = A.dump({ label: `${screen}-${profile.lang}-${profile.dark ? "dark" : "light"}` });
  const [w, h] = A.screenSize();
  const text = nodes.filter(n => n.text || n["content-desc"]);
  sc.check(text.length > 0, "Android screen has accessible content", { screen, ...profile });
  const overflow = text.filter(n => n.box && (n.box[0] < 0 || n.box[2] > w + 1 || n.box[1] < 0 || n.box[3] > h + 1));
  sc.check(overflow.length === 0, "Android accessibility bounds stay inside narrow display", { screen, ...profile, overflow: overflow.length });
  const screenshot = A.screencap(`android-${screen}-${profile.lang}-${profile.dark ? "dark" : "light"}-320dp-font130`);
  matrix.push({ client: "android", screen, ...profile, width_dp: 320, system_font_scale: 1.3, screenshot, checks: "accessible content and bounds; pixel/text clipping requires independent screenshot review" });
}
async function pwaProfile(page, profile) {
  await page.goto(P.PWA + "/settings", { waitUntil: "domcontentloaded" });
  await page.getByText(/^(Язык|Тел)$/, { exact: true }).waitFor();
  const langIsBa = await page.getByText("Башҡортса", { exact: true }).count() > 0;
  if (langIsBa !== (profile.lang === "ba")) await page.getByText(/^(Язык|Тел)$/, { exact: true }).click();
  await page.getByText("Тема", { exact: true }).click();
  await page.getByRole("button", { name: profile.dark ? /^(Тёмная|Ҡараңғы)$/ : /^(Светлая|Яҡты)$/ }).click();
  await page.getByText(/^(Размер текста|Текст ҙурлығы)$/, { exact: true }).click();
  await page.getByRole("button", { name: /Очень крупный|Бик ҙур/ }).click();
  await sleep(250);
  sc.check(await page.getByText(profile.lang === "ba" ? "Башҡортса" : "Русский", { exact: true }).count() === 1, "PWA language selected through settings", { ...profile });
}
async function browserCapture(page, screen, profile) {
  await sleep(500);
  await page.locator(".skeleton-card").first().waitFor({ state: "hidden", timeout: 20000 });
  const dimensions = await page.evaluate(() => ({ width: innerWidth, scroll: document.documentElement.scrollWidth, bodyText: document.body.innerText.length, dark: document.documentElement.dataset.theme, font: getComputedStyle(document.documentElement).fontSize, fontScale: document.documentElement.dataset.fontScale, zoom: Number(getComputedStyle(document.documentElement).zoom), error: !!document.querySelector(".state__icon--danger"), empty: !!document.querySelector(".state") && !document.querySelector(".state__icon--danger") }));
  sc.check(dimensions.width === 320 && dimensions.scroll <= dimensions.width + 1, "PWA has no horizontal overflow at 320px", { screen, ...profile, width: dimensions.width, scroll: dimensions.scroll });
  sc.check(dimensions.bodyText > 0, "PWA screen contains text", { screen, ...profile });
  sc.check(dimensions.dark === (profile.dark ? "dark" : "light") && dimensions.fontScale === "xlarge" && Math.abs(dimensions.zoom - 1.3) < 0.01, "PWA really applied selected theme and large font", { screen, ...profile, scale: dimensions.fontScale, zoom: dimensions.zoom });
  const screenshot = await P.shot(page, `matrix-${screen}-${profile.lang}-${profile.dark ? "dark" : "light"}-320-xlarge.png`);
  matrix.push({ client: "pwa", screen, ...profile, state_observed: dimensions.error ? "error" : dimensions.empty ? "empty" : "content", width_px: 320, app_font: "xlarge selected through UI", measured_font: dimensions.font, screenshot, checks: "content and horizontal overflow; independent screenshot review still required" });
}

await runScenario(sc, async () => {
  await standHealthy(sc);
  // This runner deliberately covers these six screens, rather than claiming all app screens.
  sc.facts.coverage = { android: ["map", "profile", "settings", "trips", "chat"], pwa: ["map", "profile", "settings", "trips", "chat", "driver-cabinet"] };
  sc.facts.requirement_acceptance = "not_accepted";
  sc.facts.unresolved_requirements = [
    { requirement: "Bashkir confirmed by native speaker", status: "not_executed", owner: "Александр" },
    { requirement: "independent visual and final review", status: "not_executed", evidence: "screenshots generated by this runner must be reviewed independently" },
    { requirement: "all other product screens and their states", status: "not_executed", evidence: "coverage is the explicit screen list only" },
  ];
  await androidAs(ACC.andDriver);
  originalDevice = { density: A.sh("wm density", { quiet: true }), font: A.sh("settings get system font_scale", { quiet: true }).trim() };
  const [width] = A.screenSize();
  const density = Math.round(width / 320 * 160);
  A.sh(`wm density ${density}`, { quiet: true });
  A.sh("settings put system font_scale 1.3", { quiet: true }); A.resetSizeCache(); await sleep(1000);
  sc.check(Number(A.sh("settings get system font_scale", { quiet: true }).trim()) === 1.3, "Android system font scale is actually 1.3");
  sc.facts.android_narrow_display = { physical_width_px: width, configured_density_dpi: density, effective_width_dp: width * 160 / density };
  pwa = await pwaClient(ACC.pwaDriver, { width: 320, height: 760 });
  sc.pages = [pwa.page];
  for (const profile of profiles) {
    await sc.step(`Android settings ${profile.lang}/${profile.dark ? "dark" : "light"}`, async () => {
      await androidProfile(profile); await nativeCapture("settings", profile);
    });
    for (const screen of ["map", "profile", "trips", "chat"]) {
      await sc.step(`Android ${screen} ${profile.lang}/${profile.dark ? "dark" : "light"}`, async () => {
        await androidHome(); await A.tapText(nav[screen]); await sleep(800); await nativeCapture(screen, profile);
      });
    }
    await sc.step(`PWA settings ${profile.lang}/${profile.dark ? "dark" : "light"}`, async () => {
      await pwaProfile(pwa.page, profile); await browserCapture(pwa.page, "settings", profile);
    });
    for (const [screen, path] of [["map", "/map"], ["profile", "/profile"], ["trips", "/rides"], ["chat", "/chat"], ["driver-cabinet", "/driver"]]) {
      await sc.step(`PWA ${screen} ${profile.lang}/${profile.dark ? "dark" : "light"}`, async () => {
        await pwa.page.goto(P.PWA + path, { waitUntil: "domcontentloaded" });
        sc.check(!/login|intro|onboarding/.test(pwa.page.url()), "screen is authenticated, not a login fallback", { screen });
        await browserCapture(pwa.page, screen, profile);
      });
    }
  }
  sc.facts.executed_matrix = matrix;
  sc.facts.local_execution = "passed";
}, async () => {
  sc.facts.executed_matrix = matrix;
  const failures = [];
  if (androidSettingsSaved) {
    try {
      await androidProfile({ lang: androidSettingsSaved.lang, dark: /Тёмная|Ҡараңғы/.test(androidSettingsSaved.theme || "") });
      if (/Как в системе|Системалағыса/.test(androidSettingsSaved.theme || "")) { await A.tapText(/^Тема$/); await A.tapText(/Как в системе|Системалағыса/); }
    } catch (error) { failures.push("Android app preferences: " + error.message); }
  }
  if (originalDevice) {
    try {
      const override = /Override density:\s*(\d+)/.exec(originalDevice.density);
      A.sh(override ? `wm density ${override[1]}` : "wm density reset", { quiet: true });
      const font = Number(originalDevice.font);
      if (Number.isFinite(font) && font > 0) A.sh(`settings put system font_scale ${font}`, { quiet: true });
      A.resetSizeCache();
    } catch (error) { failures.push("Android display restoration: " + error.message); }
  }
  if (pwa) { try { await pwa.ctx.close(); } catch (error) { failures.push("PWA close: " + error.message); } }
  if (failures.length) throw new Error(failures.join("; "));
});
