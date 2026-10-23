// ================================================================
//  Подпись роли в профиле — по тому, что человек делает (просьба Александра 23.10.2026;
//  Android: RideshareSmallFixesTest.roleLabelFollowsWhatThePersonDoes).
//
//  Один аккаунт и возит, и ездит. Было: подпись только по роли сервера — возящий соседей
//  человек оставался «Пассажир», а проверенный водитель — без «Проверен».
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ROOT, bundleComponent, checker } from "./shallow-harness.mjs";

const profileRole = await bundleComponent("src/utils/profileRole.ts", { exportName: "profileRole", real: [] });
const t = checker();

await t.test("кто возит — водитель, кто ещё и ездит — водитель и пассажир", () => {
  assert.deepEqual(profileRole({ role: "passenger" }), ["Пассажир", "Пассажир"]);
  assert.deepEqual(profileRole({ role: "passenger", books: true }), ["Пассажир", "Пассажир"]);
  assert.deepEqual(profileRole({ role: "passenger", drives: true }), ["Водитель", "Йөрөтөүсе"]);
  assert.deepEqual(profileRole({ role: "passenger", verified: true }), ["Водитель", "Йөрөтөүсе"]);
  assert.deepEqual(profileRole({ role: "passenger", drives: true, books: true }), ["Водитель и пассажир", "Йөрөтөүсе һәм пассажир"]);
  assert.deepEqual(profileRole({ role: "driver", books: true }), ["Водитель и пассажир", "Йөрөтөүсе һәм пассажир"]);
  assert.deepEqual(profileRole({ role: "admin", drives: true, books: true }), ["Администратор", "Администратор"]);
});

await t.test("шапка профиля берёт подпись из profileRole и показывает «Проверен»", () => {
  const src = readFileSync(ROOT + "src/screens/ProfileScreen.tsx", "utf8");
  assert.ok(src.includes("appText(...profileRole(user))"), "подпись роли по делу");
  assert.ok(/user\.verified && \(/.test(src) && src.includes('appText("Проверен", "Тикшерелгән")'), "значок «Проверен»");
});

t.done();
