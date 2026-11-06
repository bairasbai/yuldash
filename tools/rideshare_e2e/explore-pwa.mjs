// Exploration helper (not an acceptance run): node explore-pwa.mjs <profile> '<json steps>' [--dark] [--w=390]
import { openProfile, dump, shot, clickText, PWA, pwaLogin } from "./lib/pwa.mjs";
import { tgBotLogin, lastTgAuthId, sleep } from "./lib/common.mjs";

const [profile, stepsRaw, ...flags] = process.argv.slice(2);
const dark = flags.includes("--dark");
const w = Number((flags.find((f) => f.startsWith("--w=")) || "--w=390").slice(4));
const steps = JSON.parse(stepsRaw || "[]");
process.env.QUIET = "1";
const { ctx, page, consoleErrors } = await openProfile(profile, { dark, width: w });
let tgBase = 0;
try {
  for (const s of steps) {
    if (s.login) await pwaLogin(page, s.login);
    if (s.goto) await page.goto(s.goto.startsWith("http") ? s.goto : PWA + s.goto, { waitUntil: "domcontentloaded" });
    if (s.click) await clickText(page, s.click, { exact: !!s.exact, role: s.role || null, nth: s.nth || 0 });
    if (s.css) await page.locator(s.css).filter({ visible: true }).nth(s.nth || 0).click();
    if (s.fill) await page.locator(s.fill[0]).filter({ visible: true }).nth(s.nth || 0).fill(s.fill[1]);
    if (s.type) await page.keyboard.type(s.type, { delay: 30 });
    if (s.press) await page.keyboard.press(s.press);
    if (s.tgMark) tgBase = lastTgAuthId();
    if (s.tg) { const r = await tgBotLogin({ ...s.tg, afterId: tgBase }); console.log("TGCODE", r.code); if (s.tgFill) await page.locator(s.tgFill).filter({ visible: true }).first().fill(r.code); }
    if (s.wait) await sleep(s.wait);
    if (s.waitText) await page.getByText(s.waitText).filter({ visible: true }).first().waitFor({ timeout: 20000 });
    if (s.eval) console.log("EVAL", JSON.stringify(await page.evaluate(s.eval)));
    if (s.shot) console.log("SHOT", await shot(page, s.shot));
  }
  await sleep(700);
  const d = await dump(page);
  console.log("URL", d.url);
  console.log("TEXT\n" + d.text);
  console.log("CONTROLS\n" + d.controls.join("\n"));
  if (consoleErrors.length) console.log("CONSOLE_ERRORS\n" + consoleErrors.slice(0, 10).join("\n"));
} catch (e) {
  console.log("ERROR", e.message.split("\n")[0]);
  const d = await dump(page).catch(() => null);
  if (d) { console.log("URL", d.url); console.log("TEXT\n" + d.text.slice(0, 2500)); console.log("CONTROLS\n" + d.controls.join("\n")); }
} finally {
  await ctx.close();
}
