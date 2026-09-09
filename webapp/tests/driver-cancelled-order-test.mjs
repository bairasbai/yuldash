import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import {
  canRestoreDriverOrder,
  clearStoredDriverOrder,
  isCurrentDriverOrderPoll,
  isReleasedDriverOrderStatus,
} from "../src/utils/driverActiveOrder.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const screen = readFileSync(join(HERE, "../src/screens/InstantDriverTripScreen.tsx"), "utf8");

let bad = 0;
const check = (ok, label) => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}`);
};

check(
  screen.includes('isReleasedDriverOrderStatus(o.status)'),
  "cancelled/expired распознаются отдельной проверкой терминального статуса"
);
check(
  screen.includes("setCurrentActive(null)"),
  "ответ cancelled/expired действительно закрывает активную поездку"
);
check(
  screen.includes("clearStoredDriverOrder(localStorage, ACTIVE_KEY, orderId)"),
  "очищается только запись завершившегося заказа, а не возможного нового"
);
check(
  screen.includes("isCurrentDriverOrderPoll(activeIdRef.current, orderId)"),
  "запоздалый poll старого заказа не меняет новый active"
);
check(
  screen.includes("canRestoreDriverOrder(activeIdRef.current, saved)"),
  "запоздалое восстановление сохранённого заказа не меняет новый active"
);
check(
  screen.includes('"Пассажир отменил заказ. Ищем следующий."') &&
    screen.includes('"Пассажир заказды кире алды. Киләһе заказды эҙләйбеҙ."'),
  "после отмены водитель получает понятное уведомление на двух языках"
);
check(
  screen.includes("{endedNote &&") && screen.includes('role="status"'),
  "уведомление видно уже на экране ожидания новых предложений"
);
check(
  screen.includes("if (!online || active) return;") && screen.includes("setCurrentActive(null)"),
  "после сброса active существующий poll снова запрашивает предложения"
);

check(isReleasedDriverOrderStatus("cancelled"), "cancelled освобождает водителя");
check(isReleasedDriverOrderStatus("expired"), "expired освобождает водителя");
check(!isReleasedDriverOrderStatus("accepted"), "активный accepted не закрывается");
check(!isReleasedDriverOrderStatus("done"), "done остаётся на экране чека поездки");
check(isCurrentDriverOrderPoll(17, 17), "ответ текущего заказа принимается");
check(!isCurrentDriverOrderPoll(18, 17), "ответ старого заказа после смены active отбрасывается");
check(!isCurrentDriverOrderPoll(null, 17), "ответ старого заказа после сброса active отбрасывается");
check(canRestoreDriverOrder(null, 17), "сохранённый заказ восстанавливается на пустом экране");
check(canRestoreDriverOrder(17, 17), "повторный ответ того же заказа допустим");
check(!canRestoreDriverOrder(18, 17), "поздний restore заказа 17 не заменяет новый заказ 18");

const values = new Map([["active", "18"]]);
const storage = {
  getItem: (key) => values.get(key) ?? null,
  removeItem: (key) => values.delete(key),
};
check(
  !clearStoredDriverOrder(storage, "active", 17) && values.get("active") === "18",
  "поздняя отмена заказа 17 не стирает сохранённый новый заказ 18"
);
check(
  clearStoredDriverOrder(storage, "active", 18) && !values.has("active"),
  "завершившийся текущий заказ удаляется из localStorage"
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
