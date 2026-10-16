// ================================================================
//  Ряд фильтров ленты и его шторки — то, что видно и слышно человеку, но не видно сборке:
//  четыре чипа вместо двенадцати, галочки с ролями для читалки экрана, шторка-диалог с Esc,
//  ровно семь условий в «Фильтрах по умолчанию», стили только со шкалы и цель нажатия ≥ 48.
//
//  Читает исходники напрямую — сборка не нужна. Поведение (параметры запроса, состояния ленты,
//  подписи чипов) проверяют nearby-feed-test и nearby-cities-test, которые исполняют настоящий код.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const read = (p) => readFileSync(root + p, "utf8");
const home = read("src/screens/HomeScreen.tsx");
const filtersScreen = read("src/screens/FiltersScreen.tsx");
const panel = read("src/components/NearbyFilters.tsx");
const feed = read("src/nearbyFeed.ts");
const css = read("src/ui.css");

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

/** Код без комментариев. */
const code = (s) => s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");

// ---------------------------------------------------------------- лента

await test("в ленте четыре вопроса: Откуда · Куда · Когда · Фильтры — не двенадцать чипов", async () => {
  const c = code(home);
  for (const part of ["<NearbyCityChips", "<NearbyWhenChip", "<NearbyConditionsChip"]) assert.ok(c.includes(part), part);
  assert.equal((c.match(/<NearbyChip\b/g) ?? []).length, 1, "остался один чип — «Ближайшие» (браузеру нужно разрешение на место)");
  for (const old of ["Сбросить ·", "Только женщины", "Детское кресло", "Тихая поездка", "toggleAmenity", "prefs.amenities"]) {
    assert.ok(!c.includes(old), "в ленте не должно остаться: " + old);
  }
});

await test("в ленте нет ни «города», ни «потолка цены»", async () => {
  assert.ok(!/maxPrice|\.city\b|prefs\.city/.test(code(home)));
  assert.ok(!/maxPrice|Мой город|Цена до/.test(code(filtersScreen)));
});

await test("при выбранном городе лента не просит радиус и не берёт маршрут-фокус", async () => {
  // логика — в nearbyQuery (проверена настоящим кодом в nearby-feed-test); здесь — что экран ею пользуется
  assert.match(code(home), /fetchRidesNear\(nearbyQuery\(filters, limit\)/);
  assert.ok(!/radius_km:\s*200/.test(code(home)), "прежний радиус 200 км с экрана убран");
});

await test("браузерный фильтр оставлен только подстраховкой для старого сервера", async () => {
  assert.match(home, /matchesNearbyConditions\(r, conditions, verifiedOnly\)/);
  assert.ok(!/applyRideFilters/.test(code(home)), "ленту режет сервер, а не прежний клиентский фильтр");
});

await test("«Показать ещё» держится на count сервера, а не на размере порции", async () => {
  assert.match(code(home), /canShowMore\(rides\.length, total\)/);
  assert.match(code(home), /setTotal\(r\.count\)/);
  assert.ok(!/rides\.length >= limit/.test(code(home)));
});

await test("ряд скрывается не раньше, чем снять нечего (условие показа ряда)", async () => {
  assert.match(code(home), /filtersRowVisible\(\{[^}]*citiesOffered: cityFilter\.offered/);
});

await test("состояния ленты: загрузка, ошибка с повтором, пусто, «условия всё срезали» со сбросом, список", async () => {
  for (const s of ["loading", "error", "empty", "filtered", "list"]) assert.match(home, new RegExp(`feedState === "${s}"`), s);
  assert.match(home, /Нет поездок с такими условиями\. Сними часть фильтров\./);
  assert.match(home, /Был шарттар менән сәфәр юҡ\. Фильтрҙың бер өлөшөн ал\./);
  assert.match(home, /Сбросить фильтры/);
  assert.match(home, /<NearbyEmptyCard error onRetry=\{retry\}/, "у ошибки есть повтор");
});

// ---------------------------------------------------------------- шторки

await test("строка-галочка: роль checkbox/radio и aria-checked — читалка слышит «флажок, отмечен»", async () => {
  assert.match(panel, /role=\{role\}/);
  assert.match(panel, /aria-checked=\{selected\}/);
  assert.match(panel, /role\?: "checkbox" \| "radio"/);
  assert.match(panel, /role = "checkbox"/, "по умолчанию — флажок (города, условия)");
  // День — выбор одного из четырёх (три быстрых и «Другая дата…»): строки задаются двумя местами в разметке.
  assert.equal((code(panel).match(/role="radio"/g) ?? []).length, 2);
  assert.match(panel, /role="radiogroup"/);
});

await test("шторка — диалог: заголовок связан, Esc закрывает, фокус внутрь и обратно, Tab не уходит наружу", async () => {
  assert.match(panel, /role="dialog"/);
  assert.match(panel, /aria-modal="true"/);
  assert.match(panel, /aria-labelledby=\{titleId\}/);
  assert.match(panel, /event\.key === "Escape"/);
  assert.match(panel, /previous\?\.focus\(\)/);
  assert.match(panel, /event\.key !== "Tab"/);
  assert.match(panel, /createPortal\(/, "шторка вынесена в body: никакой предок не обрежет и не сдвинет её");
});

await test("у шторки есть крестик на двух языках", async () => {
  assert.match(panel, /aria-label=\{appText\("Закрыть", "Ябырға"\)\}/);
});

await test("чипы, открывающие шторки, сообщают об этом читалке (aria-haspopup)", async () => {
  assert.match(panel, /aria-haspopup="dialog"/);
});

await test("«Другая дата…»: родной календарь, только от сегодняшнего дня, прошедшее не принимается", async () => {
  assert.match(panel, /type="date"/);
  assert.match(panel, /min=\{localDayIso\(now\)\}/);
  assert.match(panel, /isPickableDay\(e\.target\.value, now\)/);
});

await test("шторка условий: «Сбросить» только условия, кнопка «Показать N поездок» озвучивается", async () => {
  assert.match(panel, /onChange\(\[\]\)/);
  assert.match(panel, /Сбросить", "Бушатырға"/);
  assert.match(panel, /showRidesLabel\(total, appText\)/);
  assert.match(panel, /aria-live="polite"/);
});

await test("строка «Показываем только проверенных водителей…» и пояснение к «Только женщины» — слово в слово как в приложении", async () => {
  assert.ok(panel.includes("Показываем только проверенных водителей — так включено в «Безопасности»."));
  assert.ok(panel.includes("Тик тикшерелгән йөрөтөүселәрҙе күрһәтәбеҙ — «Хәүефһеҙлек» бүлегендә шулай ҡабыҙылған."));
  assert.ok(panel.includes("Женщины за рулём и поездки «только для женщин»."));
  assert.ok(panel.includes("Рулдә ҡатын-ҡыҙҙар һәм «тик ҡатын-ҡыҙ өсөн» сәфәрҙәр."));
});

await test("тексты шторок городов и дня — как в приложении, на обоих языках", async () => {
  for (const pair of [
    ["Откуда", "Ҡайҙан"], ["Куда", "Ҡайҙа"], ["Все города", "Бөтә ҡалалар"], ["Любое направление", "Теләһә ҡайҙа"],
    ["Найти город или деревню", "Ҡала йәки ауыл табыу"], ["Готово", "Әҙер"], ["Когда едем", "Ҡасан барабыҙ"],
  ]) {
    assert.ok(panel.includes(`"${pair[0]}", "${pair[1]}"`), pair.join(" / "));
  }
  for (const pair of [["Когда", "Ҡасан"], ["Все дни", "Бөтә көндәр"], ["Сегодня", "Бөгөн"], ["Завтра", "Иртәгә"], ["Другая дата…", "Башҡа көн…"]]) {
    assert.ok(feed.includes(`"${pair[0]}"`) && feed.includes(`"${pair[1]}"`), pair.join(" / "));
  }
});

await test("поиск города — справочник, как при публикации поездки (CityField)", async () => {
  assert.match(panel, /<CityField/);
  assert.match(panel, /filter\.addFound\(\{ ru: s\.name_ru, ba: s\.name_ba \|\| s\.name_ru \}\)/);
});

// ---------------------------------------------------------------- «Фильтры по умолчанию»

await test("«Фильтры по умолчанию»: ровно семь условий из общего списка, тумблерами", async () => {
  const c = code(filtersScreen);
  assert.match(c, /NEARBY_CONDITIONS\.map/);
  assert.match(c, /SettingSwitchRow/);
  assert.ok(!/key: "(women_only|non_smoking|air_conditioner|child_seat)"/.test(c), "прежних имён условий на экране нет");
  assert.ok(filtersScreen.includes('"Фильтры по умолчанию", "Ғәҙәти фильтрҙар"'));
  assert.ok(filtersScreen.includes("Эти условия применятся к «Ближайшим поездкам» автоматически при открытии карты."));
});

await test("в списке условий нет пустых или одноязычных подписей", async () => {
  const rows = [...feed.matchAll(/\{ key: "(\w+)", ru: "([^"]+)", ba: "([^"]+)", hintRu: "([^"]+)", hintBa: "([^"]+)" \}/g)];
  assert.equal(rows.length, 7);
  for (const [, key, ru, ba, hintRu, hintBa] of rows) {
    assert.ok(ru && ba && hintRu && hintBa, key);
    assert.ok(/[а-яё]/i.test(ru) && /[а-яё]/i.test(ba), key);
  }
});

// ---------------------------------------------------------------- стили

const cssBlock = (() => {
  const start = css.indexOf("/* ---- Шторки ленты «Ближайшие поездки»");
  const end = css.indexOf("/* ClinicRidesEntryCard:", start);
  return css.slice(start, end);
})();

await test("все классы шторок описаны в ui.css", async () => {
  assert.ok(cssBlock.length > 500, "блок стилей найден");
  const used = new Set(
    [...(panel + filtersScreen).matchAll(/className=(?:"([^"]+)"|\{"([^"]+)")/g)]
      .flatMap((m) => (m[1] ?? m[2]).split(/\s+/))
      .filter((c) => /^(nbs|filters-intro)/.test(c))
  );
  assert.ok(used.size >= 10, "классы найдены: " + used.size);
  for (const cls of used) assert.ok(css.includes("." + cls), "нет стиля для ." + cls);
});

await test("стили шторок — только токены: ни цвета, ни радиуса, ни кегля, ни времени числом", async () => {
  assert.ok(!/#[0-9a-f]{3,8}\b|rgba?\(/i.test(cssBlock), "цвет числом");
  assert.ok(!/border-radius:\s*\d/.test(cssBlock), "радиус числом");
  assert.ok(!/font-size:\s*\d/.test(cssBlock), "кегль числом");
  assert.ok(!/(?:transition|animation)[^;]*\d+m?s\b/.test(cssBlock.replace(/var\(--motion-[\w-]+\)/g, "")), "время числом");
  assert.match(cssBlock, /var\(--motion-quick\)/);
});

await test("цель нажатия — не меньше 48: строка-галочка, «Сбросить», крестик", async () => {
  for (const sel of [".nbs-row", ".nbs__reset", ".nbs__close"]) {
    const m = cssBlock.match(new RegExp(sel.replace(/[.]/g, "\\.") + "\\s*\\{([^}]*)\\}"));
    assert.ok(m, sel + " описан");
    const h = m[1].match(/(?:min-)?height:\s*(\d+)px/);
    assert.ok(h && Number(h[1]) >= 48, `${sel}: ${h ? h[1] + "px" : "высота не задана"}`);
  }
});

await test("выбранная строка отличима не только цветом: у неё появляется галочка", async () => {
  assert.match(cssBlock, /\.nbs-row\.is-on \.nbs-row__check\s*\{[^}]*opacity:\s*1/);
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
