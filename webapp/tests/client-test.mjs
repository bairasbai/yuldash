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

// Отказы по месту посадки/высадки приходят как {ru, ba, code}: человек читает слова сервера на своём языке
// (ровно эти фразы он видит в шторке «Забронировать»); `code` в текст не попадает.
const refusal = (status, ru, ba, code) => async () => ({
  ok: false, status, statusText: "x",
  json: async () => ({ detail: { ru, ba, code } }),
});
const REFUSALS = [
  [400, "pickup_stop_unknown", "Такой остановки нет в этой поездке — выбери из списка.", "Был сәфәрҙә бындай туҡталыш юҡ — исемлектән һайла."],
  [400, "pickup_stop_is_end", "Здесь поездка заканчивается — выбери, где садишься.", "Сәфәр бында тамамлана — ҡайҙа ултырғаныңды һайла."],
  [409, "pickup_stop_conflict", "У тебя уже есть бронь с другим местом посадки.", "Һинең икенсе ултырыу урыны менән бронең бар инде."],
  [400, "dropoff_stop_unknown", "Такой остановки для высадки нет.", "Был сәфәрҙә төшөү өсөн бындай туҡталыш юҡ."],
  [409, "dropoff_stop_conflict", "У тебя уже есть бронь с другим местом высадки.", "Һинең икенсе төшөү урыны менән бронең бар инде."],
  [409, "departed_too_early", "Рано: «Я выехал» можно нажать за час до выезда.", "Иртә: «Сыҡтым»ға сығыуға бер сәғәт ҡалғас ҡына баҫып була."],
];
for (const [status, code, ru, ba] of REFUSALS) {
  check(await call(refusal(status, ru, ba, code)), ru, `${code}: русский`);
  check(await call(refusal(status, ru, ba, code), "ba"), ba, `${code}: башкирский`);
}

// Ни одна фраза не должна быть английской технической
const all = [
  await call(netDown), await call(safariDown), await call(nginx502), await call(bilingual409),
];
const techy = all.filter((m) => /failed|load|gateway|error|unauthorized|network/i.test(m));
check(String(techy.length), "0", "английских технических строк");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
