// Exploration helper (not an acceptance run): node explore-android.mjs '<json steps>'
// steps: {launch}, {stop}, {tap:"text"|"/re/", index}, {type:"text"}, {typeInto:["field text", "value"]}, {back}, {up}, {down},
//        {scrollTap:"text"}, {wait:ms}, {shot:"name"}, {key:"KEYCODE_ENTER"}, {tg:{...}} (bot imitation), {tgMark}, {xy:[x,y]}
import * as A from "./lib/android.mjs";
import { tgBotLogin, lastTgAuthId, sleep } from "./lib/common.mjs";
process.env.QUIET = "1";
A.setSerial(process.env.ANDROID_SERIAL || "emulator-5560");
const steps = JSON.parse(process.argv[2] || "[]");
const re = (s) => (typeof s === "string" && s.startsWith("/") && s.endsWith("/") ? new RegExp(s.slice(1, -1)) : s);
let tgBase = 0;
try {
  for (const s of steps) {
    if (s.launch) A.launch();
    if (s.stop) A.forceStop();
    if (s.tap) await A.tapText(re(s.tap), { index: s.index || 0, timeout: s.timeout || 15000 });
    if (s.xy) A.tapXY(...s.xy);
    if (s.type) A.typeText(s.type, s.index ?? null);
    if (s.ascii) A.sh(`input text ${s.ascii}`);
    if (s.typeInto) await A.typeInto(re(s.typeInto[0]), s.typeInto[1], { index: s.index || 0 });
    if (s.back) A.back();
    if (s.key) A.key(s.key);
    if (s.up) await A.swipeUp(s.up === true ? 0.5 : s.up);
    if (s.down) await A.swipeDown(s.down === true ? 0.5 : s.down);
    if (s.scrollTap) await A.scrollTap(re(s.scrollTap), { index: s.index || 0 });
    if (s.waitFor) await A.waitFor(re(s.waitFor), { timeout: s.timeout || 20000 });
    if (s.tgMark) tgBase = lastTgAuthId();
    if (s.tg) { const r = await tgBotLogin({ ...s.tg, afterId: tgBase }); console.log("TGCODE", r.code); if (s.tgType) { A.typeText(r.code, s.tgType === true ? null : s.tgType); } }
    if (s.wait) await sleep(s.wait);
    if (s.shot) console.log("SHOT", A.screencap(s.shot));
  }
  await sleep(600);
} catch (e) {
  console.log("ERROR", e.message.split("\n")[0]);
}
const nodes = A.dump();
console.log(A.texts(nodes).join("\n"));
const edits = nodes.filter((n) => n.class === "android.widget.EditText").map((n) => `EDIT "${n.text}" focused=${n.focused} @${n.box}`);
if (edits.length) console.log(edits.join("\n"));
