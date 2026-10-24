// Real-UI PWA flows used by the scenarios. Each step clicks/types like a person; no API shortcuts.
import { PWA, shot, sleep, log } from "./pwa.mjs";

export const vis = (loc) => loc.filter({ visible: true });
export async function click(page, loc, what) { await vis(loc).first().waitFor({ timeout: 20000 }); await vis(loc).first().click(); log("pwa.click", { what }); }
export const btn = (page, re) => page.getByRole("button", { name: re });

export async function navTab(page, name) {
  const tab = page.locator("nav a, a").filter({ hasText: new RegExp(`^${name}$`) });
  if (!(await vis(tab).count())) { await page.goto(PWA + "/map", { waitUntil: "domcontentloaded" }); await sleep(1500); }
  await click(page, tab, `tab ${name}`);
  await sleep(600);
}

/** Pick a city/stop from the suggestion list of the N-th field with the given placeholder. */
export async function pickPlace(page, inputLoc, query, name) {
  const input = vis(inputLoc).first();
  await input.click();
  await input.fill(query);
  const opt = page.locator(".city-field__item").filter({ has: page.locator(".city-field__name", { hasText: new RegExp(`^${name}$`) }) });
  await vis(opt).first().waitFor({ timeout: 15000 });
  await vis(opt).first().click();
  log("pwa.pick", { query, name });
  await sleep(300);
}

export function localInput(date) {
  // datetime-local value in the page timezone (Asia/Yekaterinburg = UTC+5, same as Ufa).
  const t = new Date(date.getTime() + 5 * 3600 * 1000);
  return t.toISOString().slice(0, 16);
}

export async function publishRide(page, { from, to, pickupChip, departAt, seats, price, stops = [] }) {
  await navTab(page, "Профиль");
  await click(page, page.getByText("Я водитель", { exact: true }), "profile → driver cabinet");
  await page.waitForURL(/\/driver$/);
  const later = vis(btn(page, /^Позже$/));
  if (await later.count()) await later.first().click();
  await click(page, btn(page, /^Опубликовать маршрут$|^Создать поездку/), "publish entry");
  await page.waitForURL(/create-ride/);
  await sleep(800);
  // A stale draft may bring stop rows of a previous ride (see finding PWA-DRAFT); start clean.
  const stale = await vis(btn(page, /^Убрать$/)).count();
  if (stale) log("pwa.publish.stale_stop_rows", { count: stale });
  for (let i = 0; i < 6 && (await vis(btn(page, /^Убрать$/)).count()); i++) { await vis(btn(page, /^Убрать$/)).first().click(); await sleep(150); }
  const city = page.locator("input[placeholder='Город или село']");
  await pickPlace(page, city.nth(0), from, from);
  await pickPlace(page, city.nth(1), to, to);
  if (pickupChip) await click(page, btn(page, new RegExp(`^${pickupChip}$`)), "pickup chip");
  await page.locator("input[type=datetime-local]").fill(localInput(departAt));
  for (let i = 0; i < 8; i++) {
    const cur = Number(await page.locator("button[aria-label='Меньше'] + b, button[aria-label='Меньше'] ~ b").first().innerText());
    if (cur === seats) break;
    await btn(page, cur > seats ? /^Меньше$/ : /^Больше$/).first().click();
  }
  await page.locator("input[type=number]").fill(String(price));
  for (const s of stops) {
    await click(page, btn(page, /^Добавить остановку$/), "add stop");
    const field = page.locator("input[placeholder='Например, Темясово']");
    await pickPlace(page, field.last(), s, s);
  }
  await shot(page, `${page._tag || "pwa"}-publish-form.png`);
  await click(page, btn(page, /^Опубликовать$/), "publish");
  await page.getByText("Поездка опубликована").first().waitFor({ timeout: 20000 });
  log("pwa.published", { from, to, stops, seats, price });
}

export async function dismissInstall(page) {
  const later = vis(btn(page, /^(Позже|Һуңыраҡ)$/));
  if (await later.count()) { await later.first().click(); await sleep(300); }
}

/** Passenger: search the feed by two stops (sheet pickers), reveal unverified drivers, open the card. */
export async function searchRide(page, { from, to, routeText, cardText = null }) {
  await navTab(page, "Карта");
  await dismissInstall(page);
  // The feed block (with its filters) appears after the first feed load; reload once if it lags.
  if (!(await vis(btn(page, /^Откуда/)).first().waitFor({ timeout: 10000 }).then(() => true).catch(() => false))) {
    log("pwa.search.reload", { reason: "feed filters not rendered in 10s" });
    await page.reload({ waitUntil: "domcontentloaded" }); await sleep(3000); await dismissInstall(page);
  }
  await click(page, btn(page, /^Откуда/), "from picker");
  await click(page, page.locator(".sheet button").filter({ hasText: new RegExp(`^${from}$`) }), `from ${from}`);
  await click(page, btn(page, /^Готово$/), "done");
  await click(page, btn(page, /^Куда/), "to picker");
  await click(page, page.locator(".sheet button").filter({ hasText: new RegExp(`^${to}$`) }), `to ${to}`);
  await click(page, btn(page, /^Готово$/), "done");
  await sleep(1200);
  const showAll = vis(btn(page, /^Показать$/));
  if (await showAll.count()) await showAll.first().click();
  let card = page.locator("article").filter({ hasText: routeText });
  if (cardText) card = card.filter({ hasText: cardText });
  await vis(card).first().waitFor({ timeout: 20000 });
  const seg = await vis(card).first().innerText();
  log("pwa.search", { from, to, card: seg.replace(/\s+/g, " ").slice(0, 160) });
  await shot(page, `${page._tag || "pwa"}-search.png`);
  await vis(card).first().getByRole("button", { name: /^Поехать$/ }).click();
  await page.getByText("Где садишься?").first().waitFor({ timeout: 15000 }).catch(() => {});
  return seg;
}

export async function bookRide(page, { pickup, dropoff, seats = 1 }) {
  if (pickup) await page.locator(`input[name^=pickup-stop][value="${pickup}"]`).check();
  if (dropoff) await page.locator(`input[name^=dropoff-stop][value="${dropoff}"]`).check();
  if (seats > 1) await click(page, page.locator(".sheet button, button").filter({ hasText: new RegExp(`^${seats}$`) }), `seats ${seats}`);
  await shot(page, `${page._tag || "pwa"}-book-sheet.png`);
  await click(page, btn(page, /^Забронировать/), "book");
  await sleep(2500);
  log("pwa.booked", { pickup, dropoff, seats, url: page.url() });
}

export async function openDriverCabinet(page) {
  await navTab(page, "Профиль");
  await click(page, page.getByText("Я водитель", { exact: true }), "profile → driver cabinet");
  await page.waitForURL(/\/driver$/);
  await dismissInstall(page);
  await sleep(1200);
}

/** Driver: act on a waiting booking card (by passenger name) in the cabinet. */
export async function driverDecide(page, { passenger, action = "Подтвердить" }) {
  await openDriverCabinet(page);
  const card = page.locator(".act-card, article, li").filter({ hasText: passenger }).filter({ has: btn(page, new RegExp(`^${action}$`)) });
  await vis(card).last().waitFor({ timeout: 20000 });
  await shot(page, `${page._tag || "pwa"}-driver-waiting.png`);
  await vis(card).last().getByRole("button", { name: new RegExp(`^${action}$`) }).click();
  log("pwa.driverDecide", { passenger, action });
  await sleep(2000);
}


export function driverRow(page, passenger) { return vis(page.locator(".list-row--stack").filter({ hasText: passenger })); }

export async function driverBoard(page, { passenger }) {
  await openDriverCabinet(page);
  const row = driverRow(page, passenger).filter({ has: btn(page, /^Сел$/) });
  await row.first().waitFor({ timeout: 20000 });
  await row.first().getByRole("button", { name: /^Сел$/ }).click();
  log("pwa.driver.boarded", { passenger });
  await sleep(1500);
  await driverRow(page, passenger).filter({ hasText: "Пассажир в машине" }).first().waitFor({ timeout: 15000 });
}

export async function driverOpenTrip(page, { passenger }) {
  await openDriverCabinet(page);
  const row = driverRow(page, passenger).filter({ has: btn(page, /^Открыть поездку$/) });
  await row.first().waitFor({ timeout: 20000 });
  await row.first().getByRole("button", { name: /^Открыть поездку$/ }).click();
  await page.waitForURL(/\/trip\/\d+$/, { timeout: 15000 });
  await sleep(1500);
  log("pwa.driver.trip", { url: page.url() });
}

export async function tripButton(page, name) {
  await click(page, btn(page, new RegExp(`^${name}$`)), `trip ${name}`);
  await sleep(800);
  // confirmation dialogs repeat the action word
  const confirm = vis(btn(page, /^Да, /));
  if (await confirm.count()) { await confirm.first().click(); log("pwa.confirm", { name }); await sleep(1200); }
}

export async function driverRatePassenger(page, { bookingId, stars }) {
  await openDriverCabinet(page);
  const card = page.locator(`[data-rate-card="${bookingId}"]`);
  await card.waitFor({ timeout: 20000 });
  await card.locator(".rate-star").nth(stars - 1).click();
  await card.getByRole("button", { name: /^Отправить оценку$/ }).click();
  await card.getByText(/^Твоя оценка/).waitFor({ timeout: 15000 });
  log("pwa.driver.rated", { bookingId, stars });
}

export async function chatOpenFromInbox(page, { name, text = null }) {
  await navTab(page, "Чат");
  let row = page.locator("button").filter({ hasText: name });
  if (text) row = row.filter({ hasText: text });
  await click(page, row, `inbox ${name}`);
  await page.waitForURL(/\/chat$/, { timeout: 15000 });
  await sleep(1000);
  const ok = vis(btn(page, /^Понятно$/));
  if (await ok.count()) await ok.first().click();
}
export async function chatSend(page, text) {
  await page.getByRole("textbox", { name: /^Сообщение/ }).first().fill(text);
  await click(page, btn(page, /^Отправить$/), "send");
  await sleep(800);
  log("pwa.chat.sent", { chars: text.length });
}
export async function chatSee(page, text, timeout = 30000) {
  await page.locator(".bubble").filter({ hasText: text }).first().waitFor({ timeout });
  log("pwa.chat.seen", { chars: text.length });
}

/** Driver: open the finished booking from the rating card and reach its receipt. */
export async function driverReceipt(page, { bookingId }) {
  await openDriverCabinet(page);
  const card = page.locator(`[data-rate-card="${bookingId}"]`);
  await card.getByRole("button", { name: /^Открыть поездку$/ }).click();
  await sleep(2000);
  if (!/\/receipt\//.test(page.url())) {
    const r = vis(page.getByRole("button", { name: /Квитанция/ }).or(page.getByRole("link", { name: /Квитанция/ })));
    if (await r.count()) { await r.first().click(); await sleep(1500); }
  }
  await page.getByText(/Квитанция|Чек поездки/).first().waitFor({ timeout: 15000 });
  await shot(page, `${page._tag}-driver-receipt.png`);
  log("pwa.driver.receipt", { url: page.url() });
  return await page.locator("main, body").first().innerText();
}

/** Driver: close the whole ride («Завершить рейс») and see it in the cabinet archive. */
export async function driverArchive(page, { route }) {
  await openDriverCabinet(page);
  const finish = vis(btn(page, /^Завершить рейс$/));
  if (await finish.count()) {
    await finish.first().click(); await sleep(800);
    const confirm = vis(page.locator("[role=dialog] button, .sheet button").filter({ hasText: /^(Завершить|Да)/ }));
    if (await confirm.count()) await confirm.first().click();
    await sleep(1500);
  }
  await page.getByText("Архив").first().scrollIntoViewIfNeeded();
  const arch = page.locator("section, div").filter({ hasText: /^Архив/ });
  await page.getByText(route).last().waitFor({ timeout: 15000 });
  await shot(page, `${page._tag}-driver-archive.png`);
  log("pwa.driver.archive", { route });
}

/** Passenger: «Поездки» tab → booking card (route + optional text) → open its trip. */
export async function openMyTrip(page, { route, cardText = null, tab = "Активные" }) {
  await navTab(page, "Поездки");
  if (tab) { const t = vis(page.locator("button, [role=tab]").filter({ hasText: new RegExp(`^${tab}$`) })); if (await t.count()) { await t.first().click(); await sleep(800); } }
  let card = page.locator("article, .list-row, .card, li").filter({ hasText: route });
  if (cardText) card = card.filter({ hasText: cardText });
  const open = vis(card).filter({ has: page.getByRole("button", { name: /^(Открыть поездку|Подробнее|Квитанция)$/ }) }).last();
  await open.waitFor({ timeout: 20000 });
  await open.getByRole("button", { name: /^(Открыть поездку|Подробнее)$/ }).first().click();
  await page.waitForURL(/\/(trip|booking)\/\d+/, { timeout: 15000 });
  await sleep(1500);
  log("pwa.openMyTrip", { route, url: page.url() });
}

/** Passenger on the finished trip page: stars → «Отправить оценку» → «Квитанция поездки». */
export async function passengerRateAndReceipt(page, { stars, bookingId }) {
  await page.goto(PWA + `/trip/${bookingId}`, { waitUntil: "domcontentloaded" });
  await page.getByText("Как прошла поездка?").first().waitFor({ timeout: 20000 });
  await page.locator(".rate-card .rate-star").nth(stars - 1).click();
  await click(page, btn(page, /^Отправить оценку$/), "send rating");
  await page.getByText(/Спасибо за оценку/).first().waitFor({ timeout: 15000 });
  await shot(page, "passenger-rated.png");
  await click(page, btn(page, /^Квитанция поездки$/), "receipt");
  await page.waitForURL(/\/receipt\/\d+/, { timeout: 15000 });
  await sleep(1500);
  await shot(page, "passenger-receipt.png");
  const text = await page.locator("body").innerText();
  log("pwa.passenger.receipt", { url: page.url() });
  return text;
}

export async function passengerHistory(page, { route, cardText = null }) {
  await navTab(page, "Поездки");
  await click(page, page.locator("button, [role=tab]").filter({ hasText: /^История$/ }), "history tab");
  await sleep(1200);
  let card = page.locator("article, .list-row, .card, li").filter({ hasText: route });
  if (cardText) card = card.filter({ hasText: cardText });
  await vis(card).first().waitFor({ timeout: 15000 });
  const txt = await vis(card).first().innerText();
  await shot(page, "passenger-history.png");
  log("pwa.passenger.history", { route, card: txt.replace(/\s+/g, " ").slice(0, 160) });
  return txt;
}

export { PWA, shot, sleep, log };
