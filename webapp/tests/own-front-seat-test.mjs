// Real seat components; only language, icons and browser hooks are controlled.
import assert from "node:assert/strict";
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const h = globalThis.__ownFrontSeat = { lang: "ru" };
const mocks = {
  "react/jsx-runtime": `export const Fragment="fragment";export const jsx=(type,props)=>({type,props:props??{}});export const jsxs=jsx;`,
  lang: `export const useLang=()=>({lang:globalThis.__ownFrontSeat.lang,appText:(ru,ba)=>globalThis.__ownFrontSeat.lang==="ba"?ba:ru});`,
  cabinet: `export const SettingSwitchRow="switch";`,
  icons: `export const IconCar="car",IconUsers="users";`,
};
const result = await build({
  entryPoints: [root + "src/components/SeatOptions.tsx"], bundle: true, write: false,
  platform: "node", format: "esm", jsx: "automatic",
  plugins: [{ name: "seat-boundaries", setup(b) {
    b.onResolve({ filter: /.*/ }, a => {
      const key = a.path in mocks ? a.path : a.path.endsWith("/i18n/lang") ? "lang"
        : a.path.endsWith("/cabinetUi") ? "cabinet" : a.path.endsWith("/Icons") ? "icons" : null;
      return key ? { path: key, namespace: "fixture" } : undefined;
    });
    b.onLoad({filter:/ownBookings\.ts$/},a=>{
      if(process.env.OWN_FRONT_SEAT_TEST_MUTANT!=="ignore-live-front")return undefined;
      const contents=readFileSync(a.path,"utf8").replace(
        "return isLiveOwnBooking(booking) && booking.ride_id === rideId && booking.front_seat === true;",
        "return !!booking && booking.ride_id === rideId;");
      return{contents,loader:"ts"};
    });
    b.onLoad({ filter: /.*/, namespace: "fixture" }, a => ({ contents: mocks[a.path], loader: "js" }));
  } }],
});
const { SeatBadges, FrontSeatChoice } = await import("data:text/javascript;base64," +
  Buffer.from(result.outputFiles[0].text).toString("base64"));
function text(node) {
  if (node == null || typeof node === "boolean") return "";
  if (typeof node !== "object") return String(node);
  if (Array.isArray(node)) return node.map(text).join(" ");
  return text(node.props?.children);
}
let cases = 0, failures = 0;
function check(label, test) {
  cases++;
  try { test(); console.log("✓ " + label); }
  catch (e) { failures++; console.error("FAIL " + label + ": " + e.message); }
}
const ride = { id: 42, front_seat_available: true, front_seat_taken: true };
const own = { id: 73, ride_id: 42, status: "confirmed", front_seat: true };
for (const lang of ["ru", "ba"]) {
  h.lang = lang;
  const mine = lang === "ru" ? "Переднее место — твоё" : "Алғы урын — һинеке";
  const occupied = lang === "ru" ? "Переднее занято" : "Алғы урын алынған";
  check(lang + ": explicit own front reservation is shown in the feed", () => {
    const value = text(SeatBadges({ ride, ownBooking: own }));
    assert.ok(value.includes(mine), value);
    assert.ok(!value.includes(occupied), value);
  });
  check(lang + ": own front reservation is not described as another passenger in the sheet", () => {
    const value = text(FrontSeatChoice({ ride, ownBooking: own, checked: false, onChange() {} }));
    assert.ok(value.includes(mine), value);
    assert.ok(!value.includes(lang === "ru" ? "другой пассажир" : "башҡа юлсы"), value);
  });
  for (const status of ["pending", "confirmed", "onboard"]) {
    check(lang + ": own " + status + " is a live reservation", () => {
      assert.ok(text(SeatBadges({ ride, ownBooking: { ...own, status } })).includes(mine));
    });
  }
  for (const booking of [
    { ...own, ride_id: 99 }, { ...own, front_seat: false },
    { ...own, status: "done" }, { ...own, status: "cancelled" }, null,
  ]) {
    check(lang + ": unrelated/rear/terminal/unknown cannot claim the front seat", () => {
      const value = text(SeatBadges({ ride, ownBooking: booking }));
      assert.ok(!value.includes(mine), value);
      assert.ok(value.includes(occupied), value);
    });
  }
}
console.log(`${cases} cases, ${failures} failures`);
process.exitCode = failures ? 1 : 0;
