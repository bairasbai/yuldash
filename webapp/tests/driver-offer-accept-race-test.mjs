import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const screen = readFileSync(join(HERE, "../src/screens/InstantDriverTripScreen.tsx"), "utf8");

let DriverOfferExpiry = null;
try {
  ({ DriverOfferExpiry } = await import(
    pathToFileURL(join(HERE, "../src/utils/driverOfferExpiry.js")).href
  ));
} catch {
  // Red до исправления: production-координатора ещё нет.
}

let bad = 0;
const check = (ok, label) => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}`);
};

check(Boolean(DriverOfferExpiry), "таймер и принятие используют общий исполняемый координатор");

if (DriverOfferExpiry) {
  // Водитель нажал «Взять» до дедлайна, а сеть отвечает медленно.
  const slow = new DriverOfferExpiry(7);
  let slowOfferVisible = true;
  check(slow.beginAccept(7), "accept текущего предложения начинается один раз");
  if (slow.expire(7)) slowOfferVisible = false;
  check(slowOfferVisible, "дедлайн во время accept не отправляет decline и не скрывает offer");
  check(!slow.expire(7), "повторный тик во время accept тоже не разрешает decline");

  // Без accept обычный дедлайн должен отправить ровно один decline.
  const ordinary = new DriverOfferExpiry(8);
  const ordinaryDeclines = [ordinary.expire(8), ordinary.expire(8)].filter(Boolean).length;
  check(ordinaryDeclines === 1, "обычное истечение разрешает decline ровно один раз");

  // Ошибка медленного accept после дедлайна: только теперь можно выполнить отложенный decline.
  const failedLate = new DriverOfferExpiry(9);
  failedLate.beginAccept(9);
  const parallelDecline = failedLate.expire(9);
  const afterFailure = failedLate.finishAccept(9, false);
  check(!parallelDecline && afterFailure, "ошибка accept выпускает отложенный таймер без параллельного запроса");
  check(!failedLate.finishAccept(9, false), "после ошибки отложенный decline не дублируется");

  // Ошибка до дедлайна возвращает обычную работу таймера.
  const failedEarly = new DriverOfferExpiry(10);
  failedEarly.beginAccept(10);
  check(!failedEarly.finishAccept(10, false), "ошибка до дедлайна не отклоняет предложение сама");
  check(failedEarly.expire(10), "после ранней ошибки обычный дедлайн снова работает");

  // Старый таймер не имеет права трогать следующее предложение.
  const current = new DriverOfferExpiry(12);
  check(!current.expire(11), "поздний таймер старого order id игнорируется");
  check(current.expire(12), "таймер текущего order id продолжает работать");
}

check(
  screen.includes("expiryRef.current.beginAccept(order.id)") &&
    screen.includes("expiryRef.current.expire(order.id)"),
  "настоящие кнопка accept и таймер подключены к координатору"
);
check(
  screen.includes('expiryRef.current.finishAccept(order.id, result !== "retry")') &&
    screen.includes("onSkipRef.current(true)"),
  "ошибка accept выпускает отложенный таймер без stale accepting"
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
