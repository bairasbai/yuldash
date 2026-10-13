// «Долг уже оплачен»: на ответ сервера `already: true` админка показывает свою надпись,
// а не обычное «Оплата подтверждена» (лист 1.2c). Раньше лишний перевод по СБП было видно
// только в журнале сервера.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { transform } from "esbuild";

const source = await readFile(new URL("../src/utils/debtAlready.ts", import.meta.url), "utf8");
const { code } = await transform(source, { loader: "ts", format: "esm" });
const { DEBT_ALREADY, isDebtAlreadySettled } = await import(
  `data:text/javascript;base64,${Buffer.from(code).toString("base64")}`
);

assert.equal(isDebtAlreadySettled({ ok: true, status: "paid", already: true, paid_kop: 0 }), true);
for (const res of [{ ok: true, status: "paid", paid_kop: 25000 }, { already: "true" }, { already: 1 }, null, undefined, "already", []]) {
  assert.equal(isDebtAlreadySettled(res), false, `не «уже оплачено»: ${JSON.stringify(res)}`);
}
console.log("✓ «уже оплачено» — только на настоящий already: true");

for (const action of ["confirm", "reject", "forgive"]) {
  const { ru, ba } = DEBT_ALREADY[action];
  assert.ok(ru.trim() && ba.trim() && ru !== ba, `${action}: нужны оба языка`);
  assert.ok(ru.startsWith("Долг уже оплачен"), `${action}: ${ru}`);
}
assert.ok(DEBT_ALREADY.confirm.ru.includes("СБП"), "при подтверждении админ должен узнать про лишний перевод по СБП");
console.log("✓ тексты на двух языках, у подтверждения — подсказка про лишний перевод");

const screen = await readFile(new URL("../src/screens/AdminPaymentRequestsScreen.tsx", import.meta.url), "utf8");
assert.match(screen, /isDebtAlreadySettled\(res\)/, "карточка долга не смотрит на already");
for (const action of ["confirm", "reject", "forgive"]) {
  assert.match(screen, new RegExp(`"${action}"\\)`), `кнопка ${action} не передаёт своё действие`);
}
console.log("✓ карточка долга различает «уже оплачено» на всех трёх кнопках");
