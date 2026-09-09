import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import {
  forgetWinterCheck,
  rememberWinterCheck,
  wasWinterCheckAsked,
  winterCheckDelay,
  winterCheckNeedsAnswer,
} from "../src/utils/winterCheck.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const taxi = readFileSync(join(HERE, "../src/components/TaxiTripActions.tsx"), "utf8");
const booking = readFileSync(join(HERE, "../src/screens/ActiveTripScreen.tsx"), "utf8");

const store = new Map();
globalThis.sessionStorage = {
  getItem: (key) => store.get(key) ?? null,
  setItem: (key, value) => store.set(key, String(value)),
  removeItem: (key) => store.delete(key),
};

let bad = 0;
const check = (ok, label) => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}`);
};

for (const [name, source] of [
  ["такси", taxi],
  ["попутка", booking],
]) {
  check(!source.includes("if (asked) return;"), `${name}: метка sent не запрещает восстановление после reload`);
  check(
    source.includes("winterCheckDelay(asked,"),
    `${name}: после sent серверное состояние проверяется сразу`
  );
  check(source.includes("winterCheckNeedsAnswer(r.state)"), `${name}: pending показывает ответ, ok/closed скрывает`);
  check(source.includes("forgetWinterCheck("), `${name}: ack очищает локальную метку sent`);
}

check(
  taxi.includes("<WinterCheckAnswer key={order.id} orderId={order.id}"),
  "такси: состояние ответа не переносится на следующий заказ"
);
check(
  taxi.includes("return askForOrderId === order.id"),
  "такси: вопрос принадлежит только текущему orderId"
);
check(
  booking.includes("const winterAsk = winterAskForId === bookingId"),
  "попутка: вопрос принадлежит только текущему bookingId"
);

const activeAnswer = booking.match(/async function winterAnswerOk\(\) \{([\s\S]*?)\n  \}/)?.[1] ?? "";
check(
  activeAnswer.indexOf("await winterCheckOk(bookingId)") < activeAnswer.indexOf("setWinterAskForId(null)"),
  "попутка: вопрос скрывается только после успешного ответа"
);
check(
  activeAnswer.includes("setWinterAskForId(bookingId)"),
  "попутка: сетевой отказ сохраняет возможность повторить"
);

const firstKey = "yuldash.winterAskOrder.41";
const secondKey = "yuldash.winterAskOrder.42";
rememberWinterCheck(firstKey);
check(winterCheckDelay(wasWinterCheckAsked(firstKey), 99_000, 1_000) === 0, "sent→reload: сверка идёт сразу");
check(winterCheckNeedsAnswer("waiting"), "sent→reload до ack: ответ показан");
check(!winterCheckNeedsAnswer("ok"), "ack→reload: ответ скрыт по серверному ok");
forgetWinterCheck(firstKey);
check(!wasWinterCheckAsked(firstKey), "ack→reload: локальная sent-метка очищена");
check(!wasWinterCheckAsked(secondKey), "смена заказа не переносит sent-состояние");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
