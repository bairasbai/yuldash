// Actual Android/PWA actions for the request → own existing ride flow.
import { A, AF, sleep, q1 } from "./stand.mjs";
import * as P from "./pwa-flows.mjs";

export async function until(read, predicate, label, timeout = 25000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await read(); if (predicate(value)) return value; await sleep(400); }
  throw new Error("Timed out: " + label);
}
export const requestRow = (passengerId) => q1("SELECT * FROM riderequest WHERE passenger_id=? ORDER BY id DESC LIMIT 1", passengerId);
export const responseRow = (requestId, driverId) => q1("SELECT * FROM requestresponse WHERE request_id=? AND driver_id=? ORDER BY id DESC LIMIT 1", requestId, driverId);

async function androidField(label, value) {
  const field = await A.scrollTo(label);
  A.tapXY(...A.center(field)); await sleep(350); A.typeText(String(value)); A.hideKeyboard();
}
export async function requestDateTime(date) {
  await A.scrollTap(/^Дата и время/);
  await sleep(700);
  const ufa = new Date(date.getTime() + 5 * 3600000);
  const day = ufa.getUTCDate();
  // The native DatePicker starts at today. Pick an actual calendar cell; never alter the clock.
  for (let month = 0; month < 3; month++) {
    const nodes = A.dump();
    const cell = nodes.find((n) => n.text === String(day) && /\d{4}/.test(n["content-desc"] || "") && n.enabled !== "false");
    if (cell) { A.tapXY(...A.center(cell)); break; }
    const next = nodes.find((n) => /Следующий месяц|Next month/.test(n["content-desc"] || ""));
    if (!next) throw new Error("DatePicker has no requested day/month");
    A.tapXY(...A.center(next)); await sleep(300);
    if (month === 2) throw new Error("DatePicker requested date not found");
  }
  await A.tapText(/^(ОК|OK|Готово)$/); await sleep(600);
  let nodes = A.dump();
  if (nodes.filter((n) => n.class === "android.widget.EditText").length < 2) {
    const toggle = nodes.find((n) => /toggle_mode$/.test(n["resource-id"] || "") || /режим ввода|text input/i.test(n["content-desc"] || ""));
    if (!toggle) throw new Error("TimePicker keyboard mode is unavailable");
    A.tapXY(...A.center(toggle)); await sleep(350);
  }
  for (const [index, number] of [[0, ufa.getUTCHours()], [1, ufa.getUTCMinutes()]]) {
    const edit = A.dump().filter((n) => n.class === "android.widget.EditText")[index];
    if (!edit) throw new Error("TimePicker field missing");
    A.tapXY(...A.center(edit)); await sleep(200); A.typeText(String(number).padStart(2, "0"));
  }
  A.hideKeyboard(); await A.tapText(/^(ОК|OK|Готово)$/); await sleep(600);
}

export async function androidCreateRequest({ from, to, price, departAt }) {
  await AF.goHome(); await AF.nav("Заявка");
  await A.scrollTap(/^Создать заявку$/); await sleep(700);
  if (!A.find(A.dump(), /^Откуда$/)) { const expand = A.find(A.dump(), /^Изменить маршрут$/); if (expand) A.tapXY(...A.center(expand)); }
  await AF.pickSuggestion("Откуда", from, from);
  await AF.pickSuggestion("Куда", to, to);
  await requestDateTime(departAt);
  await androidField("Бюджет", price);
  await A.scrollTap(/^Опубликовать заявку$/);
  await A.waitFor("Мои заявки", { timeout: 25000 });
}
export async function pwaCreateRequest(page, { from, to, price, departAt }) {
  await P.navTab(page, "Заявка");
  await P.click(page, P.btn(page, /^Создать заявку$/), "create passenger request");
  const fields = page.locator("input[placeholder='Город или село']");
  await P.pickPlace(page, fields.nth(0), from, from);
  await P.pickPlace(page, fields.nth(1), to, to);
  await page.locator("input[type=datetime-local]").fill(P.localInput(departAt));
  await page.locator("input[type=number]").fill(String(price));
  await P.click(page, P.btn(page, /^Найти водителя$/), "send passenger request");
  await page.getByText("Готово! Ищем водителя", { exact: true }).waitFor({ timeout: 25000 });
}
export async function androidOfferOwnRide({ passenger, price }) {
  await AF.goHome(); await AF.nav("Профиль");
  await A.scrollTap("Заявки пассажиров"); await A.waitFor("По пути"); await A.tapText("По пути");
  const name = await A.scrollTo(passenger);
  let nodes = A.dump(), action = AF.below(nodes, name, /^Предложить поездку$/, 1000);
  if (!action) { await A.swipeUp(0.25); nodes = A.dump(); action = A.find(nodes, /^Предложить поездку$/); }
  if (!action) throw new Error("No proposal action on the passenger request card");
  A.tapXY(...A.center(action)); await A.waitFor("Место в моём рейсе");
  await A.tapText("Место в моём рейсе");
  await androidField("Твоя цена, ₽", price);
  await A.scrollTap("Отправить предложение");
}
export async function pwaOfferOwnRide(page, { passenger, price }) {
  await P.openDriverCabinet(page);
  await P.click(page, page.getByText("Заявки пассажиров", { exact: true }), "open passenger requests");
  const filter = page.getByRole("checkbox", { name: /По моим маршрутам/ }); await filter.check();
  const card = page.locator(".act-card, article").filter({ hasText: passenger }).filter({ has: P.btn(page, /^Откликнуться$/) });
  await P.click(page, card.getByRole("button", { name: /^Откликнуться$/ }), "offer to selected passenger");
  await page.getByRole("radio", { name: /^Мой рейс/ }).check();
  await page.getByRole("spinbutton", { name: "Цена, ₽", exact: true }).fill(String(price));
  await P.click(page, P.btn(page, /^Отправить отклик$/), "send own ride offer");
}
export async function androidAcceptOffer({ pickup, dropoff, doubleTap = true }) {
  await AF.goHome(); await AF.nav("Заявка");
  await A.scrollTap("Посмотреть отклики"); await A.waitFor("Отклики на заявку");
  const seen = (await AF.screenTexts(5)).join("\n");
  if (!seen.includes(pickup) || !seen.includes(dropoff)) throw new Error("Offer hides one of the two passenger stops");
  // Returning to the top does not submit anything; the action is a real user double tap.
  await A.swipeDown(0.5); await A.swipeDown(0.5);
  const accept = await A.scrollTo(/^Принять$/);
  A.tapXY(...A.center(accept)); if (doubleTap) A.tapXY(...A.center(accept));
}
export async function pwaAcceptOffer(page, { pickup, dropoff, doubleTap = true }) {
  await P.click(page, P.btn(page, /^Смотреть отклики$/), "open driver responses");
  const body = await page.locator("body").innerText();
  if (!body.includes(pickup) || !body.includes(dropoff)) throw new Error("Offer hides one of the two passenger stops");
  const accept = page.getByRole("button", { name: "Поехать с ним", exact: true });
  if (doubleTap) await accept.dblclick(); else await accept.click();
}
