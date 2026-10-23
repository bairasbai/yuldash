// Счётчик «N проверок» в отчёте run.mjs: понимает все способы, которыми наборы печатают успех.
// Раньше считал только «✓» в начале строки — у 38 наборов в отчёте стояло «0 проверок» (23.10.2026).
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { countChecks } from "./count-checks.mjs";

const HERE = fileURLToPath(new URL(".", import.meta.url));
let bad = 0;
const check = (name, fn) => {
  try { fn(); console.log("✓ " + name); } catch (e) { bad++; console.log("✗ " + name + "\n    " + String(e.message).split("\n").slice(0, 4).join("\n    ")); }
};

check("«✓ имя» — по строке на проверку", () => {
  assert.equal(countChecks("✓ раз\n✓ два\n✓ три\n\nВСЁ СОШЛОСЬ"), 3);
});
check("«PASS имя» — по строке и итог «N passed» совпадают", () => {
  assert.equal(countChecks("PASS a\nPASS b\nPASS c\nUpdate banner: 3 passed, 0 failed"), 3);
});
check("«PASS» без итога считается по строкам", () => {
  assert.equal(countChecks("PASS a\nPASS b"), 2);
});
check("только итог «N passed (…)»", () => {
  assert.equal(countChecks("Auth gate: 4 passed (RU/BA unavailable, guest, authed)"), 4);
});
check("только итог «N/N passed»", () => {
  assert.equal(countChecks("Parcel ride gates: 30/30 passed"), 30);
  assert.equal(countChecks("Existing benefit UI: 17/17 passed"), 17);
});
check("только итог «N cases, 0 failures»", () => {
  assert.equal(countChecks("18 cases, 0 failures"), 18);
  assert.equal(countChecks("7 cases, 0 failures"), 7);
});
check("одна строка «PASS: … (число assertions)»", () => {
  assert.equal(countChecks("PASS: actual order rate in both languages (22 assertions)."), 22);
});
check("«0 failed» и «0 failures» не принимаются за проверки", () => {
  assert.equal(countChecks("Suite: 0 failed"), 0);
  assert.equal(countChecks("0 failures"), 0);
});
check("пустой и мусорный вывод — 0 (run.mjs это признаёт провалом)", () => {
  assert.equal(countChecks(""), 0);
  assert.equal(countChecks("просто текст\nбез итогов"), 0);
  assert.equal(countChecks(undefined), 0);
});
check("большее из двух: строки и итог", () => {
  assert.equal(countChecks("✓ a\n✓ b\n12 passed"), 12);
  assert.equal(countChecks("✓ a\n✓ b\n✓ c\n2 passed"), 3);
});

// На настоящих наборах — по одному на каждый способ печати (быстрые).
for (const [suite, what] of [["update-banner-test", "PASS + итог"], ["taxi-unavailable-gate-test", "только «N cases»"],
  ["auth-unavailable-gate-test", "только «N passed»"], ["commission-label-test", "одна строка с assertions"], ["pickup-stop-test", "«✓» по строке"]]) {
  check(`настоящий набор ${suite} (${what}) — счётчик больше нуля`, () => {
    const out = execFileSync("node", [HERE + suite + ".mjs"], { cwd: HERE, stdio: "pipe" }).toString();
    assert.ok(countChecks(out) > 0, `0 проверок на выводе набора ${suite}`);
  });
}

// run.mjs считает именно этой функцией (не своим регулярным выражением) и не пропускает набор с нулём.
check("run.mjs использует countChecks и считает нулевой набор провалом", () => {
  const run = readFileSync(HERE + "run.mjs", "utf8");
  assert.match(run, /import \{ countChecks \} from "\.\/count-checks\.mjs"/);
  assert.match(run, /countChecks\(out\)/);
  assert.match(run, /ничего не проверил/);
  assert.doesNotMatch(run, /match\(\/\^✓\/gm\)/, "старый счётчик «только ✓» вернулся");
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
