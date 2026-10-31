// PWA driver over real Chromium (Playwright). Profiles persist in the scratchpad (not evidence).
import { createRequire } from "node:module";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { SCRATCH, EVID, log, sleep } from "./common.mjs";
export { log };

const require = createRequire(import.meta.url);
const { chromium } = require("C:/Users/Bayra/AppData/Local/npm-cache/_npx/381cec31605a419a/node_modules/playwright");
export const CHROMIUM = "C:/Users/Bayra/AppData/Local/ms-playwright/chromium-1181/chrome-win/chrome.exe";
export const PWA = "http://127.0.0.1:4173";
export const PROFILE_ROOT = "C:/Users/Bayra/AppData/Local/Temp/rsacc24/pw";

export async function openProfile(name, { width = 390, height = 844, dark = false, fresh = false, headless = true, fontScale = 1 } = {}) {
  // Short root: Chromium IndexedDB paths overflow MAX_PATH under the long scratchpad path.
  const dir = join(PROFILE_ROOT, name);
  if (fresh) { try { (await import("node:fs")).rmSync(dir, { recursive: true, force: true }); } catch {} }
  mkdirSync(dir, { recursive: true });
  const ctx = await chromium.launchPersistentContext(dir, {
    executablePath: CHROMIUM, headless, viewport: { width, height }, deviceScaleFactor: 2,
    colorScheme: dark ? "dark" : "light", locale: "ru-RU", timezoneId: "Asia/Yekaterinburg",
    serviceWorkers: "allow", args: ["--disable-features=Translate"],
  });
  // Hermetic stand: only loopback API/PWA; t.me and any other external host are aborted and noted.
  const external = [];
  await ctx.route(/^https?:\/\/(?!127\.0\.0\.1|localhost)/, (route) => { external.push(route.request().url().slice(0, 80)); route.abort(); });
  const page = ctx.pages()[0] || (await ctx.newPage());
  ctx.on("page", (p) => { if (p !== page) p.close().catch(() => {}); });
  const consoleErrors = [];
  page.on("pageerror", (e) => consoleErrors.push(String(e)));
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  if (fontScale !== 1) await page.addInitScript((s) => { document.documentElement.style.fontSize = `${16 * s}px`; }, fontScale);
  return { ctx, page, name, consoleErrors, external };
}

/** Visible text + interactive controls, compact, for exploration and step logs. */
export async function dump(page, { max = 4000 } = {}) {
  return await page.evaluate((max) => {
    const vis = (el) => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el); return r.width > 0 && r.height > 0 && s.visibility !== "hidden" && s.display !== "none" && Number(s.opacity) > 0.05; };
    const ctrls = [];
    for (const el of document.querySelectorAll("button, a[href], input, textarea, select, [role=button], [role=tab], [role=switch], [role=radio], [role=checkbox], [role=option]")) {
      if (!vis(el)) continue;
      const r = el.getBoundingClientRect();
      const label = (el.getAttribute("aria-label") || el.innerText || el.value || el.placeholder || el.getAttribute("title") || "").replace(/\s+/g, " ").trim().slice(0, 80);
      ctrls.push(`${el.tagName.toLowerCase()}${el.type ? ":" + el.type : ""}${el.disabled ? "[disabled]" : ""} "${label}" @${Math.round(r.x)},${Math.round(r.y)}`);
    }
    const text = (document.body.innerText || "").replace(/\n{2,}/g, "\n").slice(0, max);
    return { url: location.href, text, controls: ctrls.slice(0, 120) };
  }, max);
}

let pwaShotDir = join(EVID, "runs", "_shots");
export function setPwaShotDir(d) { pwaShotDir = d; mkdirSync(d, { recursive: true }); }
export async function shot(page, file) {
  mkdirSync(pwaShotDir, { recursive: true });
  const name = file.split("/").pop();
  const p = join(pwaShotDir, (page._tag ? page._tag + "-" : "") + name.replace(/^pwa-[dp]\d-/, ""));
  await page.screenshot({ path: p, fullPage: false });
  log("pwa.shot", { file: p.replace(EVID, "test-results/release") });
  return p;
}

/** Click the first visible element whose accessible text matches. */
export async function clickText(page, text, { exact = false, role = null, timeout = 15000, nth = 0 } = {}) {
  const loc = role ? page.getByRole(role, { name: text, exact }) : page.getByText(text, { exact });
  const target = loc.filter({ visible: true }).nth(nth);
  await target.waitFor({ state: "visible", timeout });
  await target.click({ timeout });
  log("pwa.click", { text: String(text), role });
}

export async function waitText(page, text, { timeout = 20000 } = {}) {
  await page.getByText(text).filter({ visible: true }).first().waitFor({ state: "visible", timeout });
  return true;
}

export async function hasText(page, text) {
  return (await page.getByText(text).filter({ visible: true }).count()) > 0;
}

/** Real first-run path: intro → onboarding (role) → Telegram login via the bot imitation. */
export async function pwaLogin(page, { role = "passenger", telegramId, firstName, phone, lang = "ru" }) {
  const { tgBotLogin, lastTgAuthId } = await import("./common.mjs");
  await page.goto(PWA + "/", { waitUntil: "domcontentloaded" });
  const skip = page.getByRole("button", { name: /Пропустить|Үткәреү|Ҡалдырыу/ }).first();
  const sawIntro = await skip.waitFor({ timeout: 12000 }).then(() => true).catch(() => false);
  if (sawIntro) {
    await sleep(800);
    await skip.click();
    await page.waitForURL(/onboarding/, { timeout: 20000 });
    if (lang === "ba") { await page.getByRole("button", { name: /БАШ/ }).first().click(); await sleep(400); }
    for (let i = 0; i < 3; i++) { await page.locator(".onb-next, button").filter({ hasText: /^(Далее|Алға)$/ }).filter({ visible: true }).first().click(); await sleep(450); }
    await page.locator(`[data-role=${role}]`).filter({ visible: true }).first().click();
    await sleep(300);
    await page.getByRole("button", { name: /Telegram/ }).filter({ visible: true }).first().click();
  } else {
    await page.goto(PWA + "/login", { waitUntil: "domcontentloaded" });   // intro already seen as a guest
  }
  await page.waitForURL(/login/, { timeout: 20000 });
  const base = lastTgAuthId();
  await page.getByRole("button", { name: /Telegram/ }).filter({ visible: true }).first().click();
  const { code } = await tgBotLogin({ telegramId, firstName, phone, afterId: base });
  await page.locator("input[autocomplete=one-time-code]").fill(code);
  await page.locator("input.login-input").first().fill(firstName);
  await page.locator("button").filter({ hasText: /^(Войти|Инеү)$/ }).filter({ visible: true }).first().click();
  await page.waitForURL((u) => !/login|onboarding|intro/.test(String(u)), { timeout: 30000 });
  log("pwa.login", { role, url: page.url() });
}

export { sleep };
