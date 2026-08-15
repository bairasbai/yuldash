// Что человек читает при разных отказах — на настоящем клиенте.
const store = new Map([["yuldash.lang", "ru"]]);
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};
globalThis.window = { location: { origin: "https://yulbash.ru" } };

const c = await import("./.bundles/client-bundle.mjs");

let bad = 0;
const check = (got, want, label) => {
  const ok = got === want;
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label.padEnd(34)} ${got}`);
  if (!ok) console.log(`    ждали: ${want}`);
};

async function call(setup, lang = "ru") {
  store.set("yuldash.lang", lang);
  globalThis.fetch = setup;
  try {
    await c.apiGet("/test", { auth: false });
    return "(без ошибки)";
  } catch (e) {
    return e.message;
  }
}

const netDown = async () => { throw new TypeError("Failed to fetch"); };
const safariDown = async () => { throw new TypeError("Load failed"); };
const nginx502 = async () => ({
  ok: false, status: 502, statusText: "Bad Gateway",
  json: async () => { throw new Error("not json"); },
});
const bilingual409 = async () => ({
  ok: false, status: 409, statusText: "Conflict",
  json: async () => ({ detail: { ru: "Поездку уже забронировали", ba: "Сәфәр бронланған" } }),
});

check(await call(netDown), "Нет связи. Проверь интернет и попробуй ещё раз.", "Chrome: обрыв сети");
check(await call(safariDown), "Нет связи. Проверь интернет и попробуй ещё раз.", "Safari: обрыв сети");
check(await call(netDown, "ba"), "Бәйләнеш юҡ. Интернетты тикшереп ҡабатла.", "обрыв сети, башкирский");
check(await call(nginx502), "Ошибка на сервере. Попробуй чуть позже.", "прокси упал (HTML вместо JSON)");
check(await call(nginx502, "ba"), "Серверҙа хата. Аҙыраҡтан ҡабатла.", "прокси упал, башкирский");
check(await call(bilingual409), "Поездку уже забронировали", "понятный ответ сервера");

// Ни одна фраза не должна быть английской технической
const all = [
  await call(netDown), await call(safariDown), await call(nginx502), await call(bilingual409),
];
const techy = all.filter((m) => /failed|load|gateway|error|unauthorized|network/i.test(m));
check(String(techy.length), "0", "английских технических строк");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
