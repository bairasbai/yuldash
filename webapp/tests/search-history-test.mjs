// RS-11А, независимая часть. Запуск: node webapp/tests/search-history-test.mjs
// run.mjs занят PR #212; регистрация здесь НЕ заменена обходом общих проверок.
// После RS-07А/RS-10Б: добавить личный ключ в privacy.ts, хранилище с storageFence,
// запись/повтор в шторке и RidesScreen, затем зарегистрировать этот набор в run.mjs.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { transform } from "esbuild";
import { all, button, bundleComponent, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const source = readFileSync(new URL("../src/utils/searchHistory.ts", import.meta.url), "utf8");
const load = async (code = source) => {
  const output = await transform(code, { loader: "ts", format: "esm" });
  return import("data:text/javascript;base64," + Buffer.from(output.code).toString("base64"));
};
const history = await load();
const { searchHistoryKey: key, recordSearchHistory: record, parseSearchHistory: parse,
  removeSearchHistory: remove, clearSearchHistory: clear, repeatSearchHistory: repeat } = history;
const t = checker();
const route = (from, to) => ({ from: typeof from === "string" ? [from] : from, to: typeof to === "string" ? [to] : to });
const trip = route("Ургаза", "Акъяр");
const start = 1_000_000;
const entry = record([], trip, start)[0];

await t.test("один ключ для порядка городов, регистра, ё/е и повторов внутри стороны", () => {
  assert.equal(key(route(["Баймак", "Сибай"], "Ермолаево")), key(route(["СИБАЙ", "баймак", "Баймак"], "Ёрмолаево")));
  assert.equal(key(route(" Ургаза ", "Акъяр")), key(trip));
  assert.notEqual(key(trip), key(route("Акъяр", "Ургаза")));
  assert.notEqual(key(route(["А, Б"], "В")), key(route(["А", "Б"], "В")));
});

await t.test("повтор старого поиска поднимает его первым без дубля", () => {
  const first = record([], trip, start);
  const second = record(first, route("Сибай", "Уфа"), start + 180_000);
  const result = record(second, trip, start + 360_000);
  assert.equal(result.length, 2);
  assert.deepEqual(result[0], { ...trip, at: start + 360_000 });
  assert.equal(key(result[1]), key(second[0]));
});

await t.test("откуда, затем куда до двух минут — одна уточнённая запись", () => {
  const first = record([], route("Ургаза", []), start);
  assert.deepEqual(record(first, trip, start + 119_999), [{ ...trip, at: start + 119_999 }]);
  const toOnly = record([], route([], "Акъяр"), start);
  assert.equal(record(toOnly, trip, start + 1).length, 1);
});

await t.test("ровно две минуты и три минуты — уже отдельный поиск", () => {
  const first = record([], route("Ургаза", []), start);
  assert.equal(record(first, trip, start + 120_000).length, 2);
  assert.equal(record(first, trip, start + 180_000).length, 2);
});

await t.test("сужение и смена маршрута не стирают предыдущий поиск", () => {
  const first = record([], route(["Ургаза", "Сибай"], "Акъяр"), start);
  assert.equal(record(first, trip, start + 1).length, 2);
  assert.equal(record([entry], route("Уфа", "Казань"), start + 1).length, 2);
});

await t.test("перевод часов назад не превращает старый поиск в уточнение", () => {
  const first = record([], route("Ургаза", []), start);
  assert.equal(record(first, trip, start - 1).length, 2);
});

await t.test("шестой поиск вытесняет самый старый, остаётся пять", () => {
  let result = [];
  for (let n = 0; n < 6; n++) result = record(result, route(`Город ${n}`, "Уфа"), start + n * 180_000);
  assert.equal(result.length, 5);
  assert.equal(result[0].from[0], "Город 5");
  assert.equal(result[4].from[0], "Город 1");
});

await t.test("пустой поиск, больше трёх городов и неверные названия не записываются", () => {
  for (const invalid of [route([], []), route(["А", "Б", "В", "Г"], []), route([], ["А", "Б", "В", "Г"]),
    route([""], "Уфа"), route(["  "], "Уфа"), route([null], "Уфа"), null, { from: "Уфа", to: [] }]) {
    assert.equal(key(invalid), null);
    assert.deepEqual(record([entry], invalid, start + 1), [entry]);
  }
  for (const now of [NaN, Infinity, -1]) assert.deepEqual(record([entry], trip, now), [entry]);
});

await t.test("запись и ключ не меняют переданные массивы; в записи только маршрут и время", () => {
  const input = Object.freeze({ from: Object.freeze(["Сибай", "Баймак"]), to: Object.freeze(["Уфа"]), date: "2030-01-01", seats: 4 });
  const previous = Object.freeze([Object.freeze(entry)]);
  const result = record(previous, input, start + 180_000);
  assert.deepEqual(input.from, ["Сибай", "Баймак"]);
  assert.deepEqual(Object.keys(result[0]).sort(), ["at", "from", "to"]);
  result[0].from.push("Акъяр");
  assert.deepEqual(input.from, ["Сибай", "Баймак"]);
  assert.deepEqual(previous, [entry]);
});

await t.test("повтор меняет только маршрут: конкретный день, все дни, условия и места сохранены", () => {
  for (const day of ["2030-10-30", null]) {
    const current = Object.freeze({ from: ["Уфа"], to: [], day, date: day, conditions: ["quiet"], seats: 3 });
    const next = repeat(current, entry);
    assert.deepEqual(next, { ...current, ...trip });
    assert.equal(next.conditions, current.conditions);
    assert.equal(next.day, day);
    assert.equal(next.date, day);
    assert.equal(next.seats, 3);
    next.from.push("Сибай");
    assert.deepEqual(entry.from, trip.from);
    assert.deepEqual(current.from, ["Уфа"]);
  }
});

await t.test("удаление одной записи сохраняет остальные, очистка даёт пустой список", () => {
  const entries = record([entry], route("Сибай", "Уфа"), start + 180_000);
  assert.deepEqual(remove(entries, key(entry)), [entries[0]]);
  assert.deepEqual(remove([entry], key(entry)), []);
  assert.deepEqual(remove(entries, "missing"), entries);
  assert.deepEqual(clear(), []);
  assert.equal(entries.length, 2);
});

await t.test("битый JSON и неверный формат — пусто, без падения", () => {
  for (const invalid of [null, "{broken", "null", "{}", "false", '"text"', "[null,42,{}]", '[{"from":[],"to":[],"at":1}]']) {
    assert.deepEqual(parse(invalid), []);
  }
});

await t.test("чтение ограничивает пять записей, убирает дубли и лишние личные поля", () => {
  const items = Array.from({ length: 7 }, (_, n) => ({ ...route(`Город ${n}`, "Уфа"), at: start, account: "не хранить", date: "2030-01-01" }));
  const entries = parse(JSON.stringify([null, { ...trip, at: -1 }, items[0], items[0], ...items]));
  assert.equal(entries.length, 5);
  assert.equal(new Set(entries.map(key)).size, 5);
  assert.deepEqual(Object.keys(entries[0]).sort(), ["at", "from", "to"]);
  assert.deepEqual(parse(JSON.stringify([entry])), [entry]);
});

await t.test("ядро не обращается к хранилищу или сети — личный ключ до интеграции не включён", () => {
  const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
  const oldFetch = globalThis.fetch;
  Object.defineProperty(globalThis, "localStorage", { configurable: true, get() { assert.fail("запись до регистрации ключа в очистке"); } });
  globalThis.fetch = () => assert.fail("история не должна уходить на сервер");
  try {
    const entries = record(parse(JSON.stringify([entry])), trip, start + 1);
    assert.deepEqual(clear(), []);
    assert.deepEqual(remove(entries, key(entry)), []);
    assert.equal(entries.length, 1);
  } finally {
    if (previous) Object.defineProperty(globalThis, "localStorage", previous);
    else delete globalThis.localStorage;
    globalThis.fetch = oldFetch;
  }
});

// Контроль чувствительности тестов: заведомо сломанные варианты должны быть красными.
for (const [name, from, to, verify] of [
  ["без удаления дубля", "&& searchHistoryKey(entry) !== key", "", (m) => {
    assert.equal(m.recordSearchHistory([entry], trip, start + 180_000).length, 1);
  }],
  ["без уточнения", "elapsed < REFINE_WINDOW_MS", "false", (m) => {
    const first = m.recordSearchHistory([], route("Ургаза", []), start);
    assert.equal(m.recordSearchHistory(first, trip, start + 1).length, 1);
  }],
  ["лимит шесть", "SEARCH_HISTORY_LIMIT = 5", "SEARCH_HISTORY_LIMIT = 6", (m) => {
    let entries = [];
    for (let n = 0; n < 6; n++) entries = m.recordSearchHistory(entries, route(`Город ${n}`, "Уфа"), start + n * 180_000);
    assert.equal(entries.length, 5);
  }],
]) {
  await t.test(`красный контроль: ${name}`, async () => {
    assert.ok(source.includes(from));
    const mutant = await load(source.replace(from, to));
    assert.throws(() => verify(mutant), assert.AssertionError);
  });
}

for (const lang of ["ru", "ba"]) {
  const Component = await bundleComponent("src/components/SearchHistory.tsx", {
    mocks: { "/i18n/lang": langMock(lang) }, real: ["/utils/searchHistory"],
  });
  const translated = lang === "ba";
  const create = (extra = {}) => {
    const calls = { repeat: [], remove: [], clear: 0 };
    const props = {
      entries: [entry], cities: [{ ru: "Ургаза", ba: "Урғаҙа" }, { ru: "Акъяр", ba: "Аҡъяр" }],
      ownerSession: "mother-session", activeSession: "mother-session",
      onRepeat: (value) => calls.repeat.push(value), onRemove: (value) => calls.remove.push(value), onClear: () => calls.clear++,
      ...extra,
    };
    const h = mount(Component, props);
    h.render();
    return { h, calls };
  };

  await t.test(`${lang}: подпись по справочнику, доступное полное имя, повтор только маршрута`, () => {
    const { h, calls } = create();
    const label = translated ? "Урғаҙа → Аҡъяр" : "Ургаза → Акъяр";
    assert.ok(text(h.tree).includes(label));
    assert.ok(text(h.tree).includes(translated ? "Яҡында эҙләнеләр" : "Недавно искали"));
    const repeatButton = all(h.tree).find((n) => n.type === "button" && n.props["aria-label"]?.startsWith(translated ? "Эҙләүҙе ҡабатлау:" : "Повторить поиск:"));
    assert.equal(repeatButton.props["aria-label"], (translated ? "Эҙләүҙе ҡабатлау: " : "Повторить поиск: ") + label);
    repeatButton.props.onClick();
    assert.deepEqual(calls.repeat, [trip]);
    assert.deepEqual(Object.keys(calls.repeat[0]).sort(), ["from", "to"]);
    calls.repeat[0].from.push("Сибай");
    assert.deepEqual(entry.from, trip.from);
    h.unmount();
  });

  await t.test(`${lang}: удаление по ключу и исчезновение последней строки`, () => {
    const { h, calls } = create();
    const removeButton = all(h.tree).find((n) => n.type === "button" && n.props["aria-label"]?.startsWith(translated ? "Тарихтан юйыу:" : "Удалить из истории:"));
    assert.equal(removeButton.props.style.minWidth, 48);
    removeButton.props.onClick();
    assert.deepEqual(calls.remove, [key(entry)]);
    h.props.entries = remove(h.props.entries, calls.remove[0]);
    h.render();
    assert.equal(h.tree, null);
    h.unmount();
  });

  await t.test(`${lang}: очистка только после подтверждения, отмена и закрытие ничего не стирают`, async () => {
    const { h, calls } = create();
    const clearLabel = translated ? "Тарихты таҙартыу" : "Очистить историю";
    const open = async () => { button(h.tree, clearLabel).props.onClick(); await h.settle(); return ofType(h.tree, "AlertDialog")[0].props; };
    let dialog = await open();
    assert.equal(calls.clear, 0);
    assert.equal(dialog.title, translated ? "Эҙләү тарихын таҙартырғамы?" : "Очистить историю поиска?");
    assert.equal(dialog.text, translated ? "Һаҡланған адрестар һәм яҙылыуҙар ҡала" : "Сохранённые адреса и подписки останутся");
    dialog.dismiss.onClick(); await h.settle();
    assert.equal(calls.clear, 0);
    dialog = await open(); dialog.onClose(); await h.settle();
    assert.equal(calls.clear, 0);
    dialog = await open(); dialog.confirm.onClick(); await h.settle();
    assert.equal(calls.clear, 1);
    assert.equal(ofType(h.tree, "AlertDialog").length, 0);
    h.props.entries = clear(); h.render(); assert.equal(h.tree, null);
    h.unmount();
  });

  await t.test(`${lang}: пусто скрыто, пустая сторона подписана, неизвестный город остаётся русским`, () => {
    let result = create({ entries: [] });
    assert.equal(result.h.tree, null); result.h.unmount();
    result = create({ ownerSession: "", activeSession: "" });
    assert.equal(result.h.tree, null); result.h.unmount();
    for (const value of [route("Неизвестное село", []), route([], "Акъяр")]) {
      result = create({ entries: record([], value, start) });
      assert.ok(text(result.h.tree).includes(value.from.length ? (translated ? "Теләһә ҡайҙа" : "Любое направление") : (translated ? "Бөтә ҡалалар" : "Все города")));
      if (value.from.length) assert.ok(text(result.h.tree).includes("Неизвестное село"));
      result.h.unmount();
    }
  });

  await t.test(`${lang}: выход скрывает чужие записи и обезвреживает старые кнопки/подтверждение`, async () => {
    const { h, calls } = create();
    const oldButtons = all(h.tree).filter((n) => n.type === "button");
    button(h.tree, translated ? "Тарихты таҙартыу" : "Очистить историю").props.onClick();
    await h.settle();
    const oldConfirm = ofType(h.tree, "AlertDialog")[0].props.confirm.onClick;
    h.props.activeSession = "son-session"; h.render();
    assert.equal(h.tree, null);
    for (const node of oldButtons) node.props.onClick();
    oldConfirm();
    assert.deepEqual(calls, { repeat: [], remove: [], clear: 0 });
    h.props.ownerSession = "son-session"; h.props.entries = [entry]; h.render(); await h.settle();
    assert.equal(ofType(h.tree, "AlertDialog").length, 0);
    h.unmount();
  });

  await t.test(`${lang}: обработчики после ухода с экрана ничего не меняют`, () => {
    const { h, calls } = create();
    const oldButtons = all(h.tree).filter((n) => n.type === "button");
    h.unmount();
    for (const node of oldButtons) node.props.onClick();
    assert.deepEqual(calls, { repeat: [], remove: [], clear: 0 });
  });
}

t.done();
