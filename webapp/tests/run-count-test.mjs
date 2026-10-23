// Счётчик «N проверок» в отчёте run.mjs: понимает все способы, которыми наборы печатают успех.
// Раньше считал только «✓» в начале строки — у 38 наборов в отчёте стояло «0 проверок» (23.10.2026).
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
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

// ПОВЕДЕНИЕ run.mjs, а не его текст: настоящий run.mjs прогоняем по подставным наборам (YULDASH_TESTS_SANDBOX —
// папка с «наборами»; run.mjs берёт из неё только те, чьи имена есть в его списке). Набор, который ничего не
// проверил, обязан дать провал; настоящие итоги — честные числа; падающий набор — провал.
function runRunner(suites) {
  const dir = mkdtempSync(join(tmpdir(), "yuldash-run-count-"));
  try {
    for (const [name, body] of Object.entries(suites)) writeFileSync(join(dir, name + ".mjs"), body);
    try {
      const out = execFileSync("node", [HERE + "run.mjs"], { cwd: dir, stdio: "pipe", env: { ...process.env, YULDASH_TESTS_SANDBOX: dir } }).toString();
      return { code: 0, out };
    } catch (e) {
      return { code: e.status ?? 1, out: String(e.stdout || "") + String(e.stderr || "") };
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
}
const say = (text, code = 0) => `console.log(${JSON.stringify(text)});process.exit(${code});`;

check("run.mjs: набор вышел с нулём и не распознан ни одной проверки — ПРОВАЛ, а не зелёный «0 проверок»", () => {
  for (const nothing of ["просто текст\nбез итогов", "", "Suite: 0 failed", "0 failures"]) {
    const r = runRunner({ "update-banner-test": say(nothing) });
    assert.equal(r.code, 1, "нулевой набор обязан дать провал прогона. Вывод:\n" + r.out);
    assert.match(r.out, /ничего не проверил/);
    assert.doesNotMatch(r.out, /✓ update-banner-test/, "зелёной отметки быть не должно");
    assert.match(r.out, /ПРОВАЛЕНО НАБОРОВ: 1/);
  }
});
check("run.mjs: наборы разных видов печати — честное число, прогон зелёный", () => {
  const r = runRunner({
    "update-banner-test": say("PASS a\nPASS b\nPASS c\nUpdate banner: 3 passed, 0 failed"),
    "full-offline-trip-test": say("Full offline trip: 52/52 passed"),
    "taxi-unavailable-gate-test": say("18 cases, 0 failures"),
    "commission-label-test": say("PASS: rate (22 assertions)."),
    "pickup-stop-test": say("✓ раз\n✓ два"),
  });
  assert.equal(r.code, 0, r.out);
  for (const [name, n] of [["update-banner-test", 3], ["full-offline-trip-test", 52], ["taxi-unavailable-gate-test", 18], ["commission-label-test", 22], ["pickup-stop-test", 2]])
    assert.match(r.out, new RegExp(`✓ ${name} — ${n} проверок`), name);
  assert.match(r.out, /ВСЕ НАБОРЫ ПРОШЛИ/);
});
check("run.mjs: один нулевой набор среди хороших роняет весь прогон; упавший набор — тоже ПРОВАЛ", () => {
  const mixed = runRunner({ "update-banner-test": say("PASS a"), "full-offline-trip-test": say("тишина") });
  assert.equal(mixed.code, 1, mixed.out); assert.match(mixed.out, /✓ update-banner-test — 1 проверок/); assert.match(mixed.out, /✗ full-offline-trip-test — набор ничего не проверил/);
  const broken = runRunner({ "update-banner-test": say("✓ a\n✗ b", 1) });
  assert.equal(broken.code, 1, broken.out); assert.match(broken.out, /✗ update-banner-test — ПРОВАЛ/);
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
