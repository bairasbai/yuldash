import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import {
  DETAILS_REFRESH_MS,
  isTerminalBookingStatus,
  shouldApplyBookingDetails,
  shouldRefreshBookingDetails,
} from "../src/utils/tripDetailsSync.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const screen = readFileSync(join(HERE, "../src/screens/ActiveTripScreen.tsx"), "utf8");

let bad = 0;
const check = (ok, label) => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}`);
};

check(
  screen.includes("shouldRefreshBookingDetails(previousStatus, fresh.status"),
  "poll сравнивает старый и новый статус перед загрузкой details"
);
check(
  screen.includes("syncBookingDetails()"),
  "значимый переход перечитывает телефон, место встречи и оплату"
);
check(
  screen.includes("if (isTerminalBookingStatus(fresh.status))") &&
    screen.includes("dropTripPass(bookingId)"),
  "done/cancelled из /role сразу очищает TripPass"
);
check(
  screen.includes("shouldRefreshBookingDetails(previousStatus, fresh.status, elapsed)"),
  "оплата другой стороны сверяется редко, без details на каждом 10-секундном тике"
);
check(
  screen.includes("if (currentBookingIdRef.current !== bookingId) return;"),
  "запоздалый ответ старой брони не переносит details в новую"
);

check(
  shouldRefreshBookingDetails("pending", "confirmed", 10_000),
  "pending→confirmed немедленно обновляет закрытые details"
);
check(
  !shouldRefreshBookingDetails("confirmed", "confirmed", 10_000),
  "неизменный статус не создаёт второй GET каждые 10 секунд"
);
check(
  shouldRefreshBookingDetails("confirmed", "confirmed", DETAILS_REFRESH_MS),
  "редкая минутная сверка подхватывает оплату второй стороны"
);
check(isTerminalBookingStatus("done"), "done распознан как терминальный");
check(isTerminalBookingStatus("cancelled"), "cancelled распознан как терминальный");
check(
  !shouldApplyBookingDetails("done", "confirmed"),
  "запоздалые confirmed details не возвращают TripPass после done"
);
check(
  !shouldApplyBookingDetails("cancelled", "onboard"),
  "запоздалые onboard details не возвращают TripPass после cancelled"
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
