// Exploration runner (not an acceptance run): node try.mjs <profile> '[["fn",{args}],...]' [--fresh] [--dark] [--w=390]
import { openProfile, dump, pwaLogin } from "./lib/pwa.mjs";
import * as F from "./lib/pwa-flows.mjs";
const [profile, seqJson, ...flags] = process.argv.slice(2);
const { ctx, page } = await openProfile(profile, { width: Number((flags.find((f) => f.startsWith("--w=")) || "--w=390").slice(4)), fresh: flags.includes("--fresh"), dark: flags.includes("--dark") });
try {
  for (const [fn, args0] of JSON.parse(seqJson)) {
    const args = { ...(args0 || {}) };
    if (args.departInMin) args.departAt = new Date(Date.now() + args.departInMin * 60000);
    if (fn === "login") await pwaLogin(page, args);
    else if (fn === "goto") await page.goto(F.PWA + args.path);
    else if (fn === "wait") await F.sleep(args.ms);
    else if (fn === "shot") await F.shot(page, args.file);
    else await F[fn](page, args);
  }
  console.log("OK");
} catch (e) {
  console.log("ERROR", e.message.split("\n").slice(0, 3).join(" | "));
} finally {
  const d = await dump(page).catch(() => null);
  if (d) { console.log("URL", d.url); console.log(d.text.slice(0, 1800)); console.log(d.controls.slice(0, 60).join("\n")); }
  await ctx.close();
}
