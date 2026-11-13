// PWA driver over real Chromium (Playwright). Profiles persist in the scratchpad (not evidence).
import { createRequire } from "node:module";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { API, DB_PATH, EVID, log, sleep } from "./common.mjs";
import { checkPwaStand, verifyPwaClient } from "./pwa-preflight.mjs";
export { log };

const require = createRequire(import.meta.url);
const { chromium } = require("C:/Users/Bayra/AppData/Local/npm-cache/_npx/381cec31605a419a/node_modules/playwright");
export const CHROMIUM = "C:/Users/Bayra/AppData/Local/ms-playwright/chromium-1181/chrome-win/chrome.exe";
export const PWA = "http://127.0.0.1:4173";
export const PROFILE_ROOT = join(dirname(DB_PATH), "pwa-profiles");

export async function openProfile(name, { width = 390, height = 844, dark = false, fresh = false, headless = true, fontScale = 1 } = {}) {
  const stand = await checkPwaStand(PWA, "http://127.0.0.1:8000", process.env.VITE_API_BASE, API, DB_PATH);
  if (!/^[A-Za-z0-9_-]+$/.test(name ?? "")) throw new Error("Invalid test profile name");
  // Account data is reused only within the same stand, which never allows registration of workers.
  const profiles = join(PROFILE_ROOT, stand.runId);
  mkdirSync(profiles, { recursive: true });
  const dir = fresh ? mkdtempSync(join(profiles, name + "-")) : join(profiles, name);
  const ctx = await chromium.launchPersistentContext(dir, {
    executablePath: CHROMIUM, headless, viewport: { width, height }, deviceScaleFactor: 2,
    colorScheme: dark ? "dark" : "light", locale: "ru-RU", timezoneId: "Asia/Yekaterinburg",
    serviceWorkers: "block", args: ["--disable-features=Translate", "--disable-background-networking", "--no-proxy-server"],
  });
  const external = [];
  try {
    await verifyPwaClient(ctx, stand, external);
  } catch (error) {
    await ctx.close();
    throw error;
  }
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

/** Real first-run path through onboarding and into the selected local login method. */
export async function pwaLogin(page, { role = "passenger", telegramId, firstName, phone, lang = "ru", method = "telegram" }) {
  if (method !== "sms" && method !== "telegram") throw new Error("Unknown local PWA login method");
  if (method === "telegram" && !process.env.RSACC_TELEGRAM_SECRET) {
    throw new Error("Set the local Telegram simulator secret or select the SMS test method");
  }
  const { tgBotLogin, lastTgAuthId } = await import("./common.mjs");
  await page.goto(PWA + "/", { waitUntil: "domcontentloaded" });
  const skip = page.getByRole("button", { name: /Пропустить|Үткәреү|Ҡалдырыу/ }).first();
  const sawIntro = await skip.waitFor({ timeout: 12000 }).then(() => true).catch(() => false);
  if (sawIntro) {
    await sleep(800);
    await skip.click();
    await page.waitForURL(/onboarding/, { timeout: 20000 });
    if (lang === "ba") { await page.getByRole("button", { name: /БАШ/ }).first().click(); await sleep(400); }
    for (let i = 0; i < 3; i++) { await page.locator(".onb-next, button").filter({ hasText: /^(Далее|Артабан)$/ }).filter({ visible: true }).first().click(); await sleep(450); }
    await page.locator(`[data-role=${role}]`).filter({ visible: true }).first().click();
    await sleep(300);
    await page.locator(".onb__next").click();
  } else {
    await page.goto(PWA + "/login", { waitUntil: "domcontentloaded" });   // intro already seen as a guest
  }
  await page.waitForURL(/login/, { timeout: 20000 });
  const sms = page.getByRole("button", { name: /Войти по номеру телефона|Телефон номеры аша инеү/ });
  const telegram = page.getByRole("button", { name: /Telegram/ }).filter({ visible: true }).first();
  if (method === "sms") {
    await sms.waitFor({ timeout: 12000 });
    await sms.click();
    await page.getByRole("textbox", { name: /Номер телефона|Телефон номеры/ }).fill(phone);
    const requestCode = page.waitForResponse((response) =>
      response.url() === process.env.VITE_API_BASE.replace(/\/+$/, "") + "/auth/request-code" &&
      response.request().method() === "POST",
    );
    await page.getByRole("button", { name: /Получить код|Код алыу/ }).click();
    const reply = await requestCode;
    const code = (await reply.json()).dev_code;
    if (reply.status() !== 200 || typeof code !== "string" || !/^\d{6}$/.test(code)) {
      throw new Error("The local mock SMS did not issue a code");
    }
    const name = page.getByRole("textbox", { name: /^(Имя|Исем)$/ });
    if (await name.isVisible()) await name.fill(firstName);
    await page.getByRole("textbox", { name: /Код из SMS|SMS коды/ }).fill(code);
    await page.getByRole("button", { name: /^(Войти|Инеү)$/ }).click();
    await page.waitForURL((url) => !/login|onboarding|intro/.test(String(url)), { timeout: 30000 });
    log("pwa.login", { role, url: page.url() });
    return;
  }
  const base = lastTgAuthId();
  await telegram.click({ timeout: 12000 });
  const { code } = await tgBotLogin({ telegramId, firstName, phone, afterId: base });
  await page.locator("input[autocomplete=one-time-code]").fill(code);
  await page.locator("input.login-input").first().fill(firstName);
  await page.locator("button").filter({ hasText: /^(Войти|Инеү)$/ }).filter({ visible: true }).first().click();
  await page.waitForURL((u) => !/login|onboarding|intro/.test(String(u)), { timeout: 30000 });
  log("pwa.login", { role, url: page.url() });
}

export { sleep };
