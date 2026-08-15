import { serverDate, serverMs } from "./.bundles/st-bundle.mjs";

// Тест не зависит от пояса машины: ждём, что 10:00 UTC покажется как 10:00+смещение.
const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
const offsetH = -new Date("2026-08-14T12:00:00Z").getTimezoneOffset() / 60;
console.log(`пояс машины: ${tz} (UTC${offsetH >= 0 ? "+" : ""}${offsetH})`);
console.log(`у людей Юлдаша пояс Уфы, UTC+5 — сдвиг будет тот же по величине\n`);

const hhmm = (d) =>
  d ? d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }) : "—";
// 10:00 UTC глазами этого пояса
const want = hhmm(new Date("2026-08-14T10:00:00Z"));

const cases = [
  ["2026-08-14T10:00:00", "наивный UTC — так и отдаёт .isoformat()"],
  ["2026-08-14T10:00:00.123456", "микросекунды Python"],
  ["2026-08-14T10:00:00Z", "явный UTC"],
  ["2026-08-14T15:00:00+05:00", "эхо клиентской строки со смещением"],
  ["2026-08-14 10:00:00", "пробел вместо T — Safari сам не осилит"],
];

let bad = 0;
for (const [iso, note] of cases) {
  const got = hhmm(serverDate(iso));
  const ok = got === want;
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${iso.padEnd(29)} → ${got}   ${note}`);
}

const before = hhmm(new Date("2026-08-14T10:00:00"));
console.log(`\nбыло (new Date напрямую): ${before}    стало: ${want}    сдвиг: ${offsetH} ч`);

const junk = [null, undefined, "", "   ", "не дата", "0000", "2026-13-45T99:99:99", "Invalid Date"];
console.log("");
for (const j of junk) {
  const ok = serverDate(j) === null && Number.isNaN(serverMs(j));
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} мусор ${JSON.stringify(j)} → null / NaN`);
}

const dayOnly = serverDate("2026-08-14");
const dayOk = dayOnly && dayOnly.getDate() === 14 && dayOnly.getMonth() === 7;
if (!dayOk) bad++;
console.log(`\n${dayOk ? "✓" : "✗"} только день «2026-08-14» → ${dayOnly?.toLocaleDateString("ru-RU")} (день не съехал)`);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
