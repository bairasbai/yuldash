// Real-UI Android flows (installed MainActivity via adb taps/typing). No API shortcuts for user actions.
import * as A from "./android.mjs";
import { tgBotLogin, lastTgAuthId, log, sleep } from "./common.mjs";

const NAV = ["Карта", "Поездки", "Заявка", "Чат", "Профиль"];

export function topActivity() {
  const out = A.sh("dumpsys activity activities | grep -m1 topResumedActivity", { quiet: true });
  return out.trim();
}
export async function ensureApp() {
  if (!/com\.yuldash\.app/.test(topActivity())) { A.launch(); await sleep(2500); }
}

/** Back until the bottom navigation is visible; relaunch if the stack is odd. */
export async function goHome() {
  for (let i = 0; i < 6; i++) {
    const nodes = A.dump();
    if (A.find(nodes, "Карта") && A.find(nodes, "Профиль")) {
      await A.tapText("Карта", { what: "nav home" });
      return;
    }
    A.back();
    await sleep(700);
  }
  A.forceStop(); A.launch(); await sleep(3000);
  await A.tapText("Карта", { timeout: 20000 });
}
export async function nav(tab) { await A.tapText(tab, { what: `nav ${tab}` }); await sleep(1200); }

/** Login screen → Telegram button → (Chrome/intent dismissed) → bot imitation → name+code → home. */
export async function login({ telegramId, firstName, phone }) {
  await A.waitFor("Войти через Telegram", { timeout: 30000 });
  const base = lastTgAuthId();
  await A.tapText("Войти через Telegram", { what: "login: open bot" });
  await sleep(2500);
  if (!/com\.yuldash\.app/.test(topActivity())) { A.back(); await sleep(1200); }
  if (!/com\.yuldash\.app/.test(topActivity())) { A.launch(); await sleep(1500); }
  const { code } = await tgBotLogin({ telegramId, firstName, phone, afterId: base });
  await A.tapText("Твоё имя (необязательно)", { what: "name field" });
  A.typeText(firstName);
  A.hideKeyboard();
  await A.scrollTap("Код из Telegram");
  A.typeText(code);
  A.hideKeyboard();
  await A.scrollTap("Войти");
  await sleep(3500);
  for (let i = 0; i < 3; i++) {
    const nodes = A.dump();
    const ok = A.find(nodes, "Понятно");
    if (ok) { A.tapXY(...A.center(ok)); await sleep(800); continue; }
    if (A.find(nodes, "Профиль")) break;
    await sleep(1500);
  }
  await A.waitFor("Профиль", { timeout: 20000 });
  log("android.login", { name: firstName });
}

export async function logout() {
  await goHome();
  await nav("Профиль");
  if (!A.find(A.dump(), /^Выйти из аккаунта$/)) await A.scrollTap("Настройки");
  await sleep(1000);
  await A.scrollTap(/^Выйти из аккаунта$/);
  await sleep(1000);
  A.screencap("android-logout-confirm");
  const confirm = A.find(A.dump(), /^Выйти$/);
  if (confirm) { A.tapXY(...A.center(confirm)); }
  await A.waitFor("Войти через Telegram", { timeout: 30000 });
  log("android.logout", {});
}

/** Passenger: feed filters by two stops, reveal unverified, open the ride card that shows `cardText` (unique price). */
export async function searchAndOpen({ from, to, cardText }) {
  await goHome();
  await sleep(800);
  await A.tapText("Найти попутку", { what: "scroll feed to its filters" });
  await sleep(1200);
  await A.scrollTap(/^Откуда( · .*)?$/);
  await A.tapText(from, { what: "from option" });
  await A.tapText("Готово");
  await A.scrollTap(/^Куда( · .*)?$/);
  await A.tapText(to, { what: "to option" });
  await A.tapText("Готово");
  await sleep(1500);
  // The list loads after the filter: either our card or the «show unverified drivers» offer appears.
  for (let i = 0; i < 20; i++) {
    const nodes = A.dump();
    if (A.find(nodes, cardText)) break;
    const show = A.find(nodes, "Показать");
    if (show) { A.tapXY(...A.center(show)); log("android.tap", { want: "Показать", what: "show unverified" }); await sleep(2000); break; }
    if (i % 3 === 2) await A.swipeUp(0.3); else await sleep(800);
  }
  // Cards are a horizontal carousel: swipe it sideways until the card with our price is fully on screen.
  let price = null;
  for (let i = 0; i < 25 && !price; i++) {
    const nodes = A.dump();
    const p = A.find(nodes, cardText);
    const [w] = A.screenSize();
    if (p) {
      const cy0 = (p.box[1] + p.box[3]) / 2;
      const g = nodes.find((n) => n.text === "Поехать" && n.box[0] >= p.box[0] && n.box[0] - p.box[0] < 800 && Math.abs((n.box[1] + n.box[3]) / 2 - cy0) < 150);
      if (g && g.box[2] <= w) { price = p; break; }
      const y = Math.round((p.box[1] + p.box[3]) / 2);
      A.sh(`input swipe ${Math.round(w * 0.6)} ${y} ${Math.round(w * 0.3)} ${y} 500`, { quiet: true }); log("android.swipe", { dir: "carousel-nudge" }); await sleep(900);
      continue;
    }
    const more = nodes.find((n) => /^Показать\s+ещё$/.test(n.text || "") && n.box[2] <= w);
    if (more && !p) { A.tapXY(...A.center(more)); log("android.tap", { want: "Показать ещё" }); await sleep(1500); continue; }
    const go = nodes.find((n) => n.text === "Поехать");
    if (go) { const y = Math.round((go.box[1] + go.box[3]) / 2) - 80; A.sh(`input swipe ${Math.round(w * 0.85)} ${y} ${Math.round(w * 0.15)} ${y} 500`, { quiet: true }); log("android.swipe", { dir: "carousel-left" }); await sleep(900); }
    else await A.swipeUp(0.3);
  }
  if (!price) throw new Error("card not found in carousel: " + cardText);
  const nodes = A.dump();
  const p = A.find(nodes, cardText);
  const cy = (p.box[1] + p.box[3]) / 2;
  // same card = same row AND to the right of the price within one card width
  const go = nodes.filter((n) => n.text === "Поехать" && n.box[0] >= p.box[0] && n.box[0] - p.box[0] < 800)
    .sort((a, b) => Math.abs((a.box[1] + a.box[3]) / 2 - cy) - Math.abs((b.box[1] + b.box[3]) / 2 - cy))[0];
  if (!go || Math.abs((go.box[1] + go.box[3]) / 2 - cy) > 150) throw new Error("no «Поехать» on the card " + cardText);
  const cardTexts = nodes.filter((n) => n.text && n.box[1] > p.box[1] - 400 && n.box[3] < p.box[3] + 60 && n.box[0] >= p.box[0] - 60 && n.box[0] < p.box[0] + 800).map((n) => n.text);
  A.screencap("android-search-result");
  A.tapXY(...A.center(go));
  await A.waitFor("Детали поездки", { timeout: 20000 });
  log("android.search", { from, to, cardText, card: cardTexts.join(" | ").slice(0, 200), found: !!price });
  return cardTexts;
}

/** Tap the first of `options` that appears below the heading `head` (scrolling as needed). */
export async function tapOptionAfter(head, options) {
  const [, h] = A.screenSize();
  for (let i = 0; i < 10; i++) {
    const nodes = A.dump();
    const hd = A.find(nodes, head);
    if (hd) {
      const opt = nodes.filter((n) => options.includes(n.text) && n.box[1] > hd.box[1]).sort((a, b) => a.box[1] - b.box[1])[0];
      if (opt && opt.box[3] < h * 0.88) { A.tapXY(...A.center(opt)); log("android.option", { head: String(head), option: opt.text }); await sleep(600); return opt.text; }
      await A.swipeUp(0.25);
    } else {
      await A.scrollTo(head);
    }
  }
  throw new Error(`option ${options} not reachable after ${head}`);
}

export async function chooseStopsAndBook({ pickup, dropoff }) {
  if (pickup) await tapOptionAfter("Где садишься?", [pickup, `Начало маршрута · ${pickup}`]);
  if (dropoff) await tapOptionAfter("Где выходишь?", [dropoff, `Конец маршрута · ${dropoff}`]);
  A.screencap("android-booking-choice");
  await A.tapText("Забронировать место", { what: "book" });
  await A.waitFor("Отменить бронь", { timeout: 20000 });
  log("android.booked", { pickup, dropoff });
}

export async function chatSend(text) {
  const nodes = A.dump();
  const ok = A.find(nodes, "Понятно");
  if (ok) { A.tapXY(...A.center(ok)); await sleep(500); }
  const edit = A.dump().filter((n) => n.class === "android.widget.EditText").pop();
  A.tapXY(...A.center(edit));
  await sleep(500);
  A.typeText(text);
  await A.tapText("Отправить", { what: "send" });
  await sleep(1200);
  log("android.chat.sent", { chars: text.length });
}
export async function chatSee(text, timeout = 30000) {
  await A.waitFor(text, { timeout });
  log("android.chat.seen", { chars: text.length });
}

/** Focus an EditText found by its label/hint text, type, and pick the exact suggestion below it. */
export async function pickSuggestion(fieldWant, query, name, { index = 0 } = {}) {
  const lbl = await A.scrollTo(fieldWant, { index });
  A.tapXY(...A.center(lbl));
  await sleep(500);
  A.typeText("");          // a field pre-filled with the same text would not open suggestions
  await sleep(300);
  A.typeText(query);
  await sleep(1800);
  for (let i = 0; i < 10; i++) {
    const nodes = A.dump();
    const edit = nodes.filter((n) => n.class === "android.widget.EditText" && n.focused === "true")[0];
    const below = nodes.filter((n) => n.text === name && n.class !== "android.widget.EditText" && edit && n.box[1] >= edit.box[3]).sort((a, b) => a.box[1] - b.box[1])[0];
    if (below) { A.tapXY(...A.center(below)); await sleep(600); A.hideKeyboard(); log("android.pick", { query, name }); return; }
    await sleep(700);
  }
  throw new Error(`android: suggestion «${name}» not offered for «${query}»`);
}

/** Driver: home «Я водитель» → create ride form → publish. Time is HH:MM today (device local). */
export async function publishRide({ from, to, stops = [], hh, mm, day = null, seats, price }) {
  await goHome();
  await A.scrollTap("Я водитель");
  await A.waitFor("Создать поездку", { timeout: 20000 });
  await pickSuggestion("Откуда", from, from);
  await pickSuggestion("Куда", to, to);
  for (let i = 0; i < 3; i++) {
    await A.scrollTap(/^Дата и время/);
    try { await A.waitFor("Сохранить", { timeout: 5000 }); break; } catch (e) { if (i === 2) throw e; }
  }
  // The dialog defaults to «now + a few hours», which is TOMORROW late in the evening: set the day explicitly.
  if (day) {
    const dayBox = A.dump().find((n) => /^День: \d+$/.test(n["content-desc"] || ""));
    if (dayBox && dayBox["content-desc"] !== `День: ${day}`) {
      A.tapXY(...A.center(dayBox)); await sleep(1000);
      let opt = null;
      for (let k = 0; k < 4 && !opt; k++) {
        const menu = A.dump();
        opt = menu.filter((n) => n.text === String(day) && n.box[1] > 0).sort((a, b) => a.box[1] - b.box[1])[0];
        if (!opt) {   // the menu opens at the current value; earlier days are above — scroll the menu down
          const first = menu.find((n) => /^День: \d+$/.test(n["content-desc"] || ""));
          const y = first ? first.box[1] + 20 : 1300;
          A.sh(`input swipe 340 ${y} 340 ${y + 450} 400`, { quiet: true }); await sleep(700);
        }
      }
      if (!opt) throw new Error("day option not offered: " + day);
      A.tapXY(...A.center(opt)); await sleep(800);
      const now = A.dump().find((n) => /^День: \d+$/.test(n["content-desc"] || ""));
      if (!now || now["content-desc"] !== `День: ${day}`) throw new Error("day not set: " + (now && now["content-desc"]));
      log("android.day", { day });
    }
  }
  // Fresh coordinates before each tap: the keyboard moves the dialog up after the first one.
  for (const [i, v] of [[0, hh], [1, mm]]) {
    const ed = A.dump().filter((n) => n.class === "android.widget.EditText")[i];
    A.tapXY(...A.center(ed)); await sleep(900);
    const f = A.dump().filter((n) => n.class === "android.widget.EditText");
    if (f[i].focused !== "true") throw new Error("time field not focused: " + i);
    A.typeText(String(v).padStart(2, "0"));
  }
  const set = A.dump().filter((n) => n.class === "android.widget.EditText").map((n) => n.text);
  if (set[0] !== String(hh).padStart(2, "0") || set[1] !== String(mm).padStart(2, "0")) throw new Error("time not set: " + set.join(":"));
  A.hideKeyboard();
  A.screencap("android-publish-time");
  await A.tapText("Сохранить");
  await sleep(800);
  // seats & price EditTexts are labelled «Мест» and «Цена, ₽»
  for (const [lbl, val] of [["Мест", seats], ["Цена, ₽", price]]) {
    const n = await A.scrollTo(lbl);
    A.tapXY(...A.center(n)); await sleep(400);
    const focused = A.dump().filter((x) => x.class === "android.widget.EditText" && x.focused === "true")[0];
    if (!focused) throw new Error("field not focused: " + lbl);
    A.typeText(String(val));
    A.hideKeyboard();
  }
  // A form left half-filled by an interrupted attempt keeps its stop rows: start the stops clean.
  await A.scrollTo("Добавить остановку");
  for (let i = 0; i < 8; i++) {
    const nodes = A.dump();
    const rm = nodes.filter((n) => n["content-desc"] === "Убрать остановку" && n.box[1] > A.screenSize()[1] * 0.1 && n.box[3] < A.screenSize()[1] * 0.88)[0];
    if (rm) { A.tapXY(...A.center(rm)); log("android.stop.removed", {}); await sleep(600); continue; }
    const head = A.find(nodes, "Остановки по пути");
    if (head || !nodes.some((n) => n["content-desc"] === "Убрать остановку")) break;
    await A.swipeDown(0.25, { from: 0.4 });
  }
  for (const s of stops) {
    await A.scrollTap("Добавить остановку");
    await sleep(600);
    const nodes = A.dump();
    const empties = nodes.filter((n) => n.class === "android.widget.EditText" && n.text === "");
    const target = empties.sort((a, b) => b.box[1] - a.box[1])[0];
    A.tapXY(...A.center(target)); await sleep(400);
    A.typeText(s);
    await sleep(1800);
    const after = A.dump();
    const edit = after.filter((n) => n.class === "android.widget.EditText" && n.focused === "true")[0];
    const sug = after.filter((n) => n.text === s && n.class !== "android.widget.EditText" && n.box[1] >= edit.box[3]).sort((a, b) => a.box[1] - b.box[1])[0];
    if (!sug) throw new Error("stop suggestion missing: " + s);
    A.tapXY(...A.center(sug)); await sleep(600);
    A.hideKeyboard();
  }
  A.screencap("android-publish-form");
  await A.scrollTap("Опубликовать");
  await sleep(2500);
  A.screencap("android-published");
  log("android.published", { from, to, stops, seats, price, hh, mm });
}

/** Nearest node matching `want` BELOW the anchor node (same card), within `maxDy` px. */
export function below(nodes, anchor, want, maxDy = 900) {
  return nodes.filter((n) => n.box && n.box[1] >= anchor.box[1] && n.box[1] - anchor.box[1] <= maxDy &&
    ((want instanceof RegExp) ? want.test(n.text || n["content-desc"] || "") : (n.text === want || n["content-desc"] === want)))
    .sort((a, b) => a.box[1] - b.box[1])[0] || null;
}

export function containsRe(text) { return new RegExp(text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")); }

/** Passenger: «Поездки» tab → the booking card of `route` (and `cardText`, e.g. "· 512 ₽") → open it. */
export async function openMyBooking({ route, status = null, action = /^(Открыть поездку|Подробнее)$/, tab = "Активные", cardText = null }) {
  await goHome();
  await nav("Поездки");
  if (tab) { await A.tapText(tab); await sleep(1200); }
  const [, h] = A.screenSize();
  for (let i = 0; i < 12; i++) {
    const nodes = A.dump();
    for (const r of nodes.filter((n) => n.text === route)) {
      if (cardText && !below(nodes, r, cardText instanceof RegExp ? cardText : containsRe(cardText), 520)) continue;
      if (status && !below(nodes, r, status, 400)) continue;
      const act = below(nodes, r, action, 900);
      if (!act || act.box[3] > h * 0.88) continue;
      A.tapXY(...A.center(act));
      await sleep(2000);
      log("android.openBooking", { route, status: String(status), cardText: String(cardText), via: act.text });
      return;
    }
    await A.swipeUp(0.3);
  }
  throw new Error(`no booking card ${route} ${cardText || ""} ${status || ""}`);
}

/** Union of visible texts while scrolling a long screen top→bottom. */
export async function screenTexts(pages = 4) {
  const seen = new Set();
  for (let i = 0; i < pages; i++) { for (const n of A.dump()) if (n.text) seen.add(n.text); if (i < pages - 1) await A.swipeUp(0.45); }
  return [...seen];
}

const inCabinet = (nodes) => nodes.some((n) => n.text === "Кабинет водителя" && n.box && n.box[1] < 320);
export async function openDriverCabinet() {
  let nodes = A.dump();
  if (!inCabinet(nodes)) {
    await goHome();
    await nav("Профиль");
    nodes = A.dump();
    if (!inCabinet(nodes)) {
      const entry = await A.scrollTo("Маршруты, проверка и поднятие");
      A.tapXY(...A.center(entry));
      await sleep(1500);
    }
  }
  // fresh state: back to the top of the cabinet, data reloads on entry
  for (let i = 0; i < 10; i++) {
    const nodes = A.dump();
    if (A.find(nodes, "Маршруты и проверка")) break;
    if (!inCabinet(nodes)) throw new Error("not in the driver cabinet");
    await A.swipeDown(0.5, { from: 0.3 });
  }
  await sleep(800);
}

/** Driver cabinet: card of the passenger (by name), then the action button inside that card. */
export async function driverCardAction({ passenger, action, confirm = null }) {
  await openDriverCabinet();
  for (let i = 0; i < 10; i++) {
    const nodes = A.dump();
    const names = nodes.filter((n) => n.text === passenger);
    for (const nm of names) {
      const act = below(nodes, nm, action, 700);
      if (act && act.box[3] < A.screenSize()[1] * 0.9) {
        A.tapXY(...A.center(act));
        log("android.driver.action", { passenger, action: String(action) });
        await sleep(1200);
        if (confirm) {
          const c = A.find(A.dump(), confirm);
          if (c) { A.tapXY(...A.center(c)); log("android.confirm", { confirm: String(confirm) }); await sleep(1200); }
        }
        return;
      }
    }
    await A.swipeUp(0.35);
  }
  throw new Error(`driver cabinet: no «${action}» for ${passenger}`);
}
export const driverDecide = ({ passenger, action }) => driverCardAction({ passenger, action: new RegExp(`^${action}$`) });
export const driverBoard = ({ passenger }) => driverCardAction({ passenger, action: /^Сел$/ });

export async function chatOpenFromInbox({ name, text = null }) {
  await goHome();
  await nav("Чат");
  if (text) await A.tapText(text, { what: "inbox row by last message" });
  else await A.tapText(name, { what: "inbox row" });
  await sleep(2000);
  const ok = A.find(A.dump(), "Понятно");
  if (ok) { A.tapXY(...A.center(ok)); await sleep(500); }
}

export async function driverComplete({ passenger }) {
  await driverCardAction({ passenger, action: /^Открыть поездку$/ });
  await A.waitFor("Моя поездка", { timeout: 20000 });
  await A.tapText(/^Завершить$/, { what: "bottom bar complete" });
  await A.waitFor("Завершить поездку?", { timeout: 10000 });
  A.screencap("android-driver-complete-confirm");
  await A.tapText("Да, завершить");
  await sleep(2000);
  log("android.driver.completed", { passenger });
}

/** Passenger: «Поездки» → «История» → card → «Квитанция»; rate the driver there (one tap saves). */
export async function passengerRateAndReceipt({ stars, route, cardText = null, expect = [] }) {
  await openMyBooking({ route, status: "Завершена", action: /^Квитанция$/, tab: "История", cardText });
  await A.waitFor("Поездка завершена", { timeout: 20000 });
  A.screencap("android-passenger-receipt");
  const seen = await screenTexts(3);
  for (const e of expect) if (!seen.some((t) => t.includes(e))) throw new Error("receipt lacks " + e);
  await A.scrollTap(`${stars} из 5`);
  await A.waitFor(/^Твоя оценка$/, { timeout: 15000 });
  A.screencap("android-passenger-rated");
  log("android.passenger.rated", { stars });
  return seen;
}
export async function passengerHistory({ route }) {
  await goHome();
  await nav("Поездки");
  await A.tapText("История");
  await sleep(1500);
  const r = await A.scrollTo(route);
  const nodes = A.dump();
  const st = below(nodes, r, "Завершена", 300);
  A.screencap("android-passenger-history");
  if (!st) throw new Error("history card without «Завершена»");
  log("android.passenger.history", { route });
}

/** Driver: rate the passenger in the cabinet section «Пассажиры — оцените…», then open the receipt. */
export async function driverRateAndReceipt({ passenger, stars }) {
  await openDriverCabinet();
  const head = await A.scrollTo(/^Пассажиры — оцените/);
  let done = false;
  for (let i = 0; i < 6 && !done; i++) {
    const nodes = A.dump();
    const hd = A.find(nodes, /^Пассажиры — оцените/);
    const nm = hd ? nodes.filter((n) => n.text === passenger && n.box[1] > hd.box[1])[0] : null;
    const star = nm ? below(nodes, nm, new RegExp(`^${stars}( из 5| звезд\\S*| звёзд)$`), 800) : null;
    if (star && star.box[3] < A.screenSize()[1] * 0.88) { A.tapXY(...A.center(star)); done = true; break; }
    await A.swipeUp(0.25);
  }
  if (!done) throw new Error("driver rating stars not found");
  await sleep(800);
  const send = A.find(A.dump(), /^Отправить оценку$/);
  if (send) { A.tapXY(...A.center(send)); await sleep(1500); }
  A.screencap("android-driver-rated");
  log("android.driver.rated", { passenger, stars });
  // receipt from the finished booking
  await driverCardAction({ passenger, action: /^Открыть поездку$/ });
  await A.scrollTap(/^Квитанция/);
  await A.waitFor(/Поездка завершена|Квитанция/, { timeout: 20000 });
  A.screencap("android-driver-receipt");
  return await screenTexts(3);
}

/** Driver: close the whole ride («Завершить рейс») and find it in «Архив». */
export async function driverArchive({ route }) {
  await openDriverCabinet();
  const fin = await A.scrollTo(/^Завершить рейс$/).catch(() => null);
  if (fin) {
    A.tapXY(...A.center(fin)); await sleep(1000);
    const c = A.find(A.dump(), /^(Да, завершить|Завершить)$/);
    if (c) { A.tapXY(...A.center(c)); await sleep(1500); }
  }
  await openDriverCabinet();
  const arch = await A.scrollTo(/^Архив( поездок)?$/);
  for (let i = 0; i < 5; i++) {
    const nodes = A.dump();
    const hd = A.find(nodes, /^Архив( поездок)?$/);
    const r = hd ? nodes.filter((n) => n.text === route && n.box[1] > hd.box[1])[0] : null;
    if (r) { A.screencap("android-driver-archive"); log("android.driver.archive", { route }); return; }
    await A.swipeUp(0.25);
  }
  throw new Error("route not in driver archive: " + route);
}

export { A };
