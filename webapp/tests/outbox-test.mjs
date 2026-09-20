import { IDBFactory } from 'fake-indexeddb';
globalThis.indexedDB = new IDBFactory();
// Проверка очереди исходящих на настоящем модуле (собранном esbuild-ом).
// Подделываем только браузер: localStorage, window, fetch.

const store = new Map();
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};

const handlers = new Map();
globalThis.window = {
  addEventListener: (ev, fn) => handlers.set(ev, [...(handlers.get(ev) ?? []), fn]),
  removeEventListener: (ev, fn) =>
    handlers.set(ev, (handlers.get(ev) ?? []).filter((f) => f !== fn)),
  location: { origin: "https://yulbash.ru" },
};
const fireOnline = () => (handlers.get("online") ?? []).forEach((f) => f());

// Режим сети: "down" — до сервера не дозвонились; "ok" — принял; "reject" — отказал.
let mode = "down";
const sent = [];
globalThis.fetch = async (url, init) => {
  if (mode === "down") throw new TypeError("Failed to fetch");
  const body = init?.body ? JSON.parse(init.body) : null;
  sent.push({ url: String(url), body });
  if (mode === "reject") {
    return {
      ok: false,
      status: 409,
      statusText: "Conflict",
      json: async () => ({ detail: { ru: "Поездка уже закрыта", ba: "Сәфәр ябылған" } }),
    };
  }
  return { ok: true, status: 200, json: async () => ({ id: sent.length, text: body?.text ?? "" }) };
};

const ob = await import("./.bundles/outbox-bundle.mjs");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

// --- 1. Сети нет: действия копятся ---
await ob.enqueue(7, "message", "жду у поворота");
await ob.enqueue(7, "driver_status", "departed");
await ob.enqueue(7, "message", "подъезжаю");
check(ob.outboxCount(7) === 3, "три действия легли в очередь", `count=${ob.outboxCount(7)}`);
check(ob.hasPending(), "очередь непустая");

// --- 2. Разгрузка при лежащей сети: ничего не теряем ---
let changed = await ob.flushOutbox();
check(!changed && ob.outboxCount(7) === 3, "сеть лежит → очередь цела", `count=${ob.outboxCount(7)}`);
check(sent.length === 0, "ни одного запроса не ушло");

// --- 3. Сеть вернулась: уходит всё и по порядку ---
mode = "ok";
changed = await ob.flushOutbox();
check(changed && ob.outboxCount(7) === 0, "очередь разгрузилась", `count=${ob.outboxCount(7)}`);
check(sent.length === 3, "ушли все три", `sent=${sent.length}`);
const order = sent.map((s) => s.body?.text ?? s.body?.status);
check(
  JSON.stringify(order) === JSON.stringify(["жду у поворота", "departed", "подъезжаю"]),
  "порядок сохранён",
  JSON.stringify(order)
);
check(sent[1].url.includes("/driver-status"), "статус ушёл на свою ручку", sent[1].url);

// --- 4. Сервер отказал: очередь не застревает навсегда ---
sent.length = 0;
mode = "reject";
await ob.enqueue(7, "message", "поздно, бронь закрыта");
await ob.enqueue(7, "message", "второе");
changed = await ob.flushOutbox();
check(ob.outboxCount(7) === 0, "отказ сервера снимает действие, очередь не отравлена", `count=${ob.outboxCount(7)}`);

// --- 5. Событие «сеть вернулась» разгружает само ---
sent.length = 0;
mode = "down";
await ob.enqueue(9, "message", "еду");
let flushedCalls = 0;
const stop = ob.watchOutbox(() => flushedCalls++);
await new Promise((r) => setImmediate(r));
check(ob.outboxCount(9) === 1, "пока сети нет — лежит");
mode = "ok";
fireOnline();
await new Promise((r) => setTimeout(r, 20));
check(ob.outboxCount(9) === 0, "сеть вернулась → ушло само", `count=${ob.outboxCount(9)}`);
check(flushedCalls === 1, "экран получил сигнал обновить историю", `calls=${flushedCalls}`);
stop();

// --- 6. Выход из аккаунта чистит чужое ---
mode = "down";
await ob.enqueue(11, "message", "чужое сообщение");
await ob.clearOutbox();
check(!ob.hasPending() && ob.outboxCount(11) === 0, "выход из аккаунта очистил очередь");

// --- 7. Повреждённое хранилище не роняет экран ---
store.set("yuldash.outbox", "{это не json");
check(ob.outboxCount(7) === 0 && !ob.hasPending(), "мусор в хранилище не ломает чтение");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
