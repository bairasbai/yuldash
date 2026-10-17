// ================================================================
//  Ряд фильтров и строки шторок — НАСТОЯЩАЯ разметка настоящих компонентов (react-dom/server).
//
//  Исходники читает nearby-filters-ui-test; здесь — что компоненты реально рисуют: сколько строк,
//  какие роли и aria-checked у читалки экрана, какие подписи на чипах на обоих языках, есть ли у
//  каждого из семи условий иконка. Открытые шторки (портал в body) сюда не попадают — их состав
//  проверяют те же компоненты внутри (NearbyConditionsList, NearbyDayOptions).
//  Язык подменён заглушкой useLang; сеть не нужна.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const result = await build({
  stdin: {
    contents: `
      import { renderToStaticMarkup } from "react-dom/server";
      import { createElement as h } from "react";
      export { renderToStaticMarkup, h };
      export { localDayIso } from "./src/nearbyFeed";
      export { NearbyCheckRow, NearbyConditionsList, NearbyDayOptions, NearbyWhenChip, NearbyConditionsChip, NearbyCityChips } from "./src/components/NearbyFilters";
      export { NearbyUnverifiedCard } from "./src/components/NearbyRideCard";
    `,
    resolveDir: root,
    loader: "ts",
  },
  bundle: true,
  write: false,
  platform: "node",
  format: "esm",
  jsx: "automatic",
  define: { "import.meta.env": "{}" },
  alias: { "react-dom/server": "react-dom/server.browser" },
  plugins: [
    {
      name: "lang-stub",
      setup(b) {
        b.onResolve({ filter: /\/i18n\/lang$/ }, () => ({ path: "lang-stub", namespace: "stub" }));
        b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({
          contents: `export const useLang = () => ({
            lang: globalThis.__lang,
            appText: (ru, ba) => (globalThis.__lang === "ba" ? ba : ru),
            t: (k) => k, pick: (p) => p[0], setLang() {}, toggle() {},
          });`,
          loader: "js",
        }));
      },
    },
  ],
});
globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };
const m = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));

let bad = 0;
async function test(name, run) {
  try {
    await run();
    console.log("✓ " + name);
  } catch (e) {
    bad++;
    console.log("✗ " + name + "\n    " + e.message);
  }
}
/** Разметка на нужном языке. */
function html(lang, element) {
  globalThis.__lang = lang;
  return m.renderToStaticMarkup(element);
}
const noop = () => {};
const count = (s, re) => (s.match(re) ?? []).length;
const labels = (s) => [...s.matchAll(/<span class="nbs-row__label">(.*?)<\/span>/g)].map((x) => x[1]);

const RU = ["Только женщины", "Детское кресло", "С животным", "Багаж", "Кондиционер", "Некурящий", "Тихая поездка"];
const BA = ["Тик ҡатын-ҡыҙ", "Балалар ултырғысы", "Хайуан менән", "Йөк", "Кондиционер", "Тартмаусы", "Тыныс сәфәр"];

// ---------------------------------------------------------------- условия

await test("шторка условий: семь строк-флажков в порядке приложения, на двух языках", async () => {
  const el = m.h(m.NearbyConditionsList, { chosen: [], onChange: noop, verifiedOnly: false });
  assert.deepEqual(labels(html("ru", el)), RU);
  assert.deepEqual(labels(html("ba", el)), BA);
  const ru = html("ru", el);
  assert.equal(count(ru, /role="checkbox"/g), 7);
  assert.equal(count(ru, /aria-checked="false"/g), 7, "ничего не выбрано");
});

await test("у каждого из семи условий есть иконка", async () => {
  const ru = html("ru", m.h(m.NearbyConditionsList, { chosen: [], onChange: noop, verifiedOnly: false }));
  assert.equal(count(ru, /<span class="nbs-row__icon"[^>]*><svg/g), 7);
});

await test("выбранные условия отмечены для читалки (aria-checked) и подсвечены", async () => {
  const ru = html("ru", m.h(m.NearbyConditionsList, { chosen: ["women", "quiet"], onChange: noop, verifiedOnly: false }));
  assert.equal(count(ru, /aria-checked="true"/g), 2);
  assert.equal(count(ru, /aria-checked="false"/g), 5);
  assert.equal(count(ru, /class="nbs-row is-on"/g), 2);
});

await test("«Только женщины» включено — под ним пояснение про женщин за рулём; выключено — нет", async () => {
  const on = html("ru", m.h(m.NearbyConditionsList, { chosen: ["women"], onChange: noop, verifiedOnly: false }));
  assert.ok(on.includes("Женщины за рулём и поездки «только для женщин»."));
  const ba = html("ba", m.h(m.NearbyConditionsList, { chosen: ["women"], onChange: noop, verifiedOnly: false }));
  assert.ok(ba.includes("Рулдә ҡатын-ҡыҙҙар һәм «тик ҡатын-ҡыҙ өсөн» сәфәрҙәр."));
  const off = html("ru", m.h(m.NearbyConditionsList, { chosen: ["pets"], onChange: noop, verifiedOnly: false }));
  assert.ok(!off.includes("Женщины за рулём"));
});

await test("включено «Только проверенные» — в шторке строка про это; выключено — строки нет", async () => {
  const on = html("ru", m.h(m.NearbyConditionsList, { chosen: [], onChange: noop, verifiedOnly: true }));
  assert.ok(on.includes("Показываем только проверенных водителей — так включено в «Безопасности»."));
  assert.ok(html("ba", m.h(m.NearbyConditionsList, { chosen: [], onChange: noop, verifiedOnly: true })).includes("Тик тикшерелгән йөрөтөүселәрҙе күрһәтәбеҙ — «Хәүефһеҙлек» бүлегендә шулай ҡабыҙылған."));
  const off = html("ru", m.h(m.NearbyConditionsList, { chosen: [], onChange: noop, verifiedOnly: false }));
  assert.ok(!off.includes("проверенных водителей"));
});

await test("чип «Фильтры · N»: число выбранных условий, «Фильтры» без выбора", async () => {
  const props = (chosen) => ({ chosen, onChange: noop, verifiedOnly: false, total: 3 });
  const none = html("ru", m.h(m.NearbyConditionsChip, props([])));
  assert.match(none, />Фильтры<\/button>/);
  assert.ok(!none.includes("is-on"));
  const two = html("ru", m.h(m.NearbyConditionsChip, props(["pets", "quiet"])));
  assert.match(two, /Фильтры · 2<\/button>/);
  assert.ok(two.includes("fchip is-on"));
  assert.ok(two.includes('aria-haspopup="dialog"'));
  assert.match(html("ba", m.h(m.NearbyConditionsChip, props(["pets"]))), /Фильтрҙар · 1<\/button>/);
});

// ---------------------------------------------------------------- когда

await test("«Когда едем»: четыре переключателя, выбран ровно один", async () => {
  const el = (day) => m.h(m.NearbyDayOptions, { day, onPick: noop });
  const all = html("ru", el(null));
  assert.equal(count(all, /role="radio"/g), 4);
  assert.equal(count(all, /role="radiogroup"/g), 1);
  assert.deepEqual(labels(all), ["Все дни", "Сегодня", "Завтра", "Другая дата…"]);
  assert.equal(count(all, /aria-checked="true"/g), 1);
  assert.match(all, /role="radio" aria-checked="true" class="nbs-row is-on"><span class="nbs-row__label">Все дни/);
  assert.deepEqual(labels(html("ba", el(null))), ["Бөтә көндәр", "Бөгөн", "Иртәгә", "Башҡа көн…"]);
});

await test("«Когда едем»: выбрано «Завтра» — отмечено оно; выбрана своя дата — «Дата · 05.01» отмечена", async () => {
  const el = (day) => m.h(m.NearbyDayOptions, { day, onPick: noop });
  const tomorrow = m.localDayIso(new Date(), 1);
  const t = html("ru", el(tomorrow));
  assert.match(t, /aria-checked="true" class="nbs-row is-on"><span class="nbs-row__label">Завтра/);
  assert.equal(count(t, /aria-checked="true"/g), 1);

  const custom = html("ru", el("2099-01-05"));
  assert.deepEqual(labels(custom), ["Все дни", "Сегодня", "Завтра", "Дата · 05.01"]);
  assert.match(custom, /aria-checked="true" class="nbs-row is-on"><span class="nbs-row__label">Дата · 05\.01/);
  assert.equal(labels(html("ba", el("2099-01-05")))[3], "Көн · 05.01");
});

await test("чип «Когда»: «Когда», «Когда · 05.01», «Когда · Сегодня» — и на башкирском", async () => {
  const chip = (day) => m.h(m.NearbyWhenChip, { day, onChange: noop });
  assert.match(html("ru", chip(null)), />Когда<\/button>/);
  assert.ok(!html("ru", chip(null)).includes("is-on"));
  const custom = html("ru", chip("2099-01-05"));
  assert.match(custom, /Когда · 05\.01<\/button>/);
  assert.ok(custom.includes("fchip is-on") && custom.includes('aria-haspopup="dialog"'));
  assert.match(html("ru", chip(m.localDayIso(new Date()))), /Когда · Сегодня<\/button>/);
  assert.match(html("ba", chip(m.localDayIso(new Date(), 1))), /Ҡасан · Иртәгә<\/button>/);
  assert.match(html("ba", chip(null)), />Ҡасан<\/button>/);
});

// ---------------------------------------------------------------- откуда и куда

const noopFn = noop;
const baseFilter = {
  from: [], to: [], fromOptions: [], toOptions: [], found: [], active: false, offered: false,
  setFrom: noopFn, setTo: noopFn, addFound: noopFn, clear: noopFn,
};
const options = [{ ru: "Баймак", ba: "Баймаҡ" }, { ru: "Сибай", ba: "Сибай" }, { ru: "Уфа", ba: "Өфө" }];

await test("нет городов с поездками (старый сервер, пустая база) — чипов нет совсем", async () => {
  assert.equal(html("ru", m.h(m.NearbyCityChips, { filter: baseFilter })), "");
});

await test("есть города — два чипа «Откуда» и «Куда»", async () => {
  const f = { ...baseFilter, fromOptions: options, toOptions: options, offered: true };
  const s = html("ru", m.h(m.NearbyCityChips, { filter: f }));
  assert.match(s, />Откуда<\/button>/);
  assert.match(s, />Куда<\/button>/);
  assert.ok(!s.includes("is-on"));
  const ba = html("ba", m.h(m.NearbyCityChips, { filter: f }));
  assert.match(ba, />Ҡайҙан<\/button>/);
  assert.match(ba, />Ҡайҙа<\/button>/);
});

await test("чип «Откуда · Баймак +1» — на языке человека", async () => {
  const f = { ...baseFilter, from: ["Баймак", "Сибай"], to: ["Уфа"], fromOptions: options, toOptions: options, offered: true, active: true };
  const ru = html("ru", m.h(m.NearbyCityChips, { filter: f }));
  assert.match(ru, /Откуда · Баймак \+1<\/button>/);
  assert.match(ru, /Куда · Уфа<\/button>/);
  assert.equal(count(ru, /fchip is-on/g), 2);
  const ba = html("ba", m.h(m.NearbyCityChips, { filter: f }));
  assert.match(ba, /Ҡайҙан · Баймаҡ \+1<\/button>/);
  assert.match(ba, /Ҡайҙа · Өфө<\/button>/);
});

await test("выбран город, список сервера опустел — чипы остаются: снять выбор должно быть чем", async () => {
  const f = { ...baseFilter, from: ["Мелеуз"], offered: true, active: true };
  const s = html("ru", m.h(m.NearbyCityChips, { filter: f }));
  assert.match(s, /Откуда · Мелеуз<\/button>/);
});

// ---------------------------------------------------------------- строка-галочка

await test("строка-галочка: роль, aria-checked, подпись, значок выбора", async () => {
  const row = (extra) => m.h(m.NearbyCheckRow, { label: "Все города", selected: true, onToggle: noop, ...extra });
  const checkbox = html("ru", row());
  assert.match(checkbox, /^<button type="button" role="checkbox" aria-checked="true" class="nbs-row is-on">/);
  assert.ok(checkbox.includes('<span class="nbs-row__label">Все города</span>'));
  assert.ok(checkbox.includes('class="nbs-row__check" aria-hidden="true"'));
  assert.match(html("ru", row({ role: "radio", selected: false })), /role="radio" aria-checked="false" class="nbs-row"/);
});

// ---------------------------------------------------------------- «Есть N поездок непроверенных водителей» (17.10.2026)

await test("подсказка о непроверенных: почему пусто, сколько спрятано, «Показать» и что настройка не меняется", async () => {
  const ru = html("ru", m.h(m.NearbyUnverifiedCard, { hidden: 3, onShow: noop }));
  assert.ok(ru.includes("<strong>Проверенных водителей пока нет</strong>"));
  assert.ok(ru.includes('<span role="status">Есть 3 поездки непроверенных водителей — показать?</span>'), "число озвучивается читалкой");
  assert.match(ru, /<button type="button" class="nrc-unverified__show">Показать<\/button>/);
  assert.ok(ru.includes("Только для этого поиска — настройка «Безопасности» не изменится."));
  assert.ok(!ru.includes("Сбросить фильтры"), "без других фильтров сбрасывать нечего");
  const ba = html("ba", m.h(m.NearbyUnverifiedCard, { hidden: 3, onShow: noop, onReset: noop }));
  assert.ok(ba.includes("Тикшерелгән йөрөтөүселәр әлегә юҡ"));
  assert.ok(ba.includes("Тикшерелмәгән йөрөтөүселәрҙең 3 сәфәре бар — күрһәтәйекме?"));
  assert.match(ba, />Күрһәтергә<\/button>/);
  assert.ok(ba.includes("Тик ошо эҙләү өсөн — «Хәүефһеҙлек» көйләүе үҙгәрмәй."));
  assert.match(ba, />Фильтрҙы бушат<\/button>/, "при других фильтрах — путь назад");
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
