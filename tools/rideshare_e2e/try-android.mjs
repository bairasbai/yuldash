// Exploration runner for Android flows (not an acceptance run): node try-android.mjs '[["fn",{args}],...]'
import * as F from "./lib/android-flows.mjs";
const A = F.A;
A.setSerial(process.env.ANDROID_SERIAL || "emulator-5560");
process.env.QUIET = process.env.QUIET || "1";
try {
  for (const [fn, args] of JSON.parse(process.argv[2])) {
    if (fn === "wait") await new Promise((r) => setTimeout(r, args.ms));
    else if (fn === "tap") await A.tapText(args.text, { index: args.index || 0 });
    else if (fn === "scrollTap") await A.scrollTap(args.text, { index: args.index || 0 });
    else await F[fn](args || {});
  }
  console.log("OK");
} catch (e) {
  console.log("ERROR", e.message.split("\n")[0]);
  if (e.screen) console.log(e.screen.join("\n"));
}
console.log(A.texts(A.dump()).join("\n"));
