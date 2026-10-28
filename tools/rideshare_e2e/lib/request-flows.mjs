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
  await A.scrollTo(label);
  const editable = (nodes) => {
    const head = A.find(nodes, label);
    if (!head) return null;
    const fields = nodes.filter(n => n.class === "android.widget.EditText" && n.box);
    const enclosing = fields.find(n => n.box[0] <= head.box[0] && n.box[2] >= head.box[2] && n.box[1] <= head.box[1] && n.box[3] >= head.box[3]);
    if (enclosing) return enclosing;
    return fields.filter(n => n.box[1] >= head.box[3] && n.box[1] - head.box[3] < 250 && n.box[0] < head.box[2] && n.box[2] > head.box[0]).sort((a,b) => a.box[1] - b.box[1])[0] || null;
  };
  const field = editable(A.dump());
  if (!field) throw new Error("No editable field associated with " + label);
  A.tapXY(...A.center(field)); await sleep(350); A.typeText(String(value)); A.hideKeyboard();
  if (editable(A.dump())?.text !== String(value)) throw new Error("The labelled field did not retain its value: " + label);
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
  A.hideKeyboard();
  // Hiding the IME moves this native dialog. Confirm only its stable positive button.
  let previousBox = null, confirmed = false;
  for (let frame = 0; frame < 8; frame++) {
    const nodes = A.dump();
    const hour = nodes.find(n => n["resource-id"] === "android:id/input_hour");
    const minute = nodes.find(n => n["resource-id"] === "android:id/input_minute");
    const buttons = nodes.filter(n => n["resource-id"] === "android:id/button1"
      && n.class === "android.widget.Button" && n.enabled === "true" && n.clickable === "true" && n.box);
    if (buttons.length !== 1 || !hour || !minute) throw new Error("Current native TimePicker controls are missing or ambiguous");
    if (Number(hour.text) !== ufa.getUTCHours() || Number(minute.text) !== ufa.getUTCMinutes())
      throw new Error("Native TimePicker did not retain the requested time");
    const keyboard = A.imeShown(), box = JSON.stringify(buttons[0].box);
    if (!keyboard && box === previousBox) {
      A.tapXY(...A.center(buttons[0])); confirmed = true; break;
    }
    previousBox = keyboard ? null : box;
    await sleep(500);
  }
  if (!confirmed) throw new Error("Native TimePicker positive button did not settle after keyboard dismissal");
  await until(() => A.dump(), nodes => !!A.find(nodes, /^Создать заявку$/)
    && !nodes.some(n => /android:id\/input_(?:hour|minute)$/.test(n["resource-id"] || "")),
    "native TimePicker closed after its one positive action");
}

export async function androidCreateRequest({ from, to, price, departAt }) {
  await AF.goHome(); await AF.nav("Заявка");
  await A.scrollTap(/^(Создать заявку|Создать новую)$/); await sleep(700);
  if (!A.find(A.dump(), /^Откуда$/)) { const expand = A.find(A.dump(), /^Изменить маршрут$/); if (expand) A.tapXY(...A.center(expand)); }
  await AF.pickSuggestion("Откуда", from, from);
  await AF.pickSuggestion("Куда", to, to);
  await requestDateTime(departAt);
  await androidField("Бюджет", price);
  // This action is fixed above the system bar, below the scrolling-content threshold.
  await A.tapText(/^Опубликовать заявку$/);
  await A.waitFor("Мои заявки", { timeout: 25000 });
}
export async function pwaCreateRequest(page, { from, to, price, departAt }) {
  await P.navTab(page, "Заявка");
  await P.click(page, P.btn(page, /^(Создать заявку|Создать новую)$/), "create passenger request");
  const fields = page.locator("input[placeholder='Город или село']");
  await P.pickPlace(page, fields.nth(0), from, from);
  await P.pickPlace(page, fields.nth(1), to, to);
  await page.locator("input[type=datetime-local]").fill(P.localInput(departAt));
  await page.locator("input[type=number]").fill(String(price));
  await P.click(page, P.btn(page, /^Найти водителя$/), "send passenger request");
  await page.getByText("Готово! Ищем водителя", { exact: true }).waitFor({ timeout: 25000 });
}
export async function androidOfferOwnRide({ passenger, price }) {
  await AF.openDriverCabinet();
  await A.scrollTap("Заявки пассажиров", { max: 48 }); await A.waitFor("По пути"); await A.tapText("По пути");
  const name = await A.scrollTo(passenger);
  let nodes = A.dump(), action = AF.below(nodes, name, /^Предложить поездку$/, 1000);
  if (!action) { await A.swipeUp(0.25); nodes = A.dump(); action = A.find(nodes, /^Предложить поездку$/); }
  if (!action) throw new Error("No proposal action on the passenger request card");
  A.tapXY(...A.center(action)); await A.waitFor("Место в моём рейсе");
  await A.tapText("Место в моём рейсе");
  await androidField("Твоя цена, ₽", price);
  await A.scrollTap("Отправить предложение", { maxBottom: 0.96 });
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
export async function androidAcceptOffer({ pickup, dropoff, requestPrice, doubleTap = true }) {
  await AF.goHome(); await AF.nav("Заявка");
  if (!requestPrice) throw new Error("Bind the response action to the current synthetic request price");
  let open = null;
  for (let i=0;i<10&&!open;i++) {
    const nodes=A.dump(), budget=A.find(nodes, `${requestPrice} ₽ предлагаю`);
    if(budget) open=AF.below(nodes,budget,"Посмотреть отклики",600);
    if(!open) await A.swipeUp(0.3);
  }
  if(!open) throw new Error("No response action on the current request card");
  A.tapXY(...A.center(open)); await A.waitFor("Отклики на заявку");
  const seen = (await AF.screenTexts(5)).join("\n");
  if (!seen.includes(pickup) || !seen.includes(dropoff)) throw new Error("Offer hides one of the two passenger stops");
  // Returning to the top does not submit anything; the action is a real user double tap.
  await A.swipeDown(0.5); await A.swipeDown(0.5);
  const accept = await A.scrollTo(/^Принять$/, { maxBottom: 0.96 });
  A.tapXY(...A.center(accept)); if (doubleTap) A.tapXY(...A.center(accept));
}
export async function pwaAcceptOffer(page, { pickup, dropoff, requestPrice, driverName, doubleTap = true }) {
  await P.click(page, P.btn(page, /^Смотреть отклики$/), "open driver responses");
  if (!requestPrice || !driverName) throw new Error("Bind the current offered ride by its driver and amount");
  const card = page.locator(".offer-card").filter({ hasText: driverName })
    .filter({ hasText: new RegExp(`${requestPrice}\\s*₽`) })
    .filter({ has: page.getByRole("button", { name: "Поехать с ним", exact: true }) });
  await card.first().waitFor({ state: "visible" });
  if (await card.count() !== 1) throw new Error("Current offer is ambiguous");
  await card.getByText(new RegExp(pickup)).first().waitFor();
  await card.getByText(new RegExp(dropoff)).first().waitFor();
  const body = await card.innerText();
  if (!body.includes(pickup) || !body.includes(dropoff)) throw new Error("Offer hides one of the two passenger stops");
  const accept = card.getByRole("button", { name: "Поехать с ним", exact: true });
  if (doubleTap) await accept.dblclick(); else await accept.click();
}
