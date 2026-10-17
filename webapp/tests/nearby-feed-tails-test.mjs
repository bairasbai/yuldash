// ================================================================
//  Три хвоста ленты «Ближайшие поездки» (приёмки PR147, PR148, PR151) — зеркало MapScreen.kt,
//  NearbyFeedSearch.kt и NearbyCityFilter.kt:
//   1. сменил фильтр (день, условие, город, место), а сеть моргнула — под новым чипом не остаётся
//      старый список: лента говорит «нет сети» и даёт «Повторить». Тихое обновление ТОГО ЖЕ поиска
//      раз в ~25 с при сбое список держит;
//   2. найденная поиском и не выбранная деревня уходит, когда шторку закрыли; выбранная остаётся;
//   3. шторка закрывается движением: уезжает вниз, затемнение гаснет — и только потом пропадает.
//      Esc, тап по затемнению, крестик и «Готово» закрывают одинаково; «Уменьшить движение» — сразу.
//
//  Исполняем настоящие HomeScreen.tsx, NearbyFilters.tsx, nearbyCityFilter.ts и nearbyFeed.ts.
//  React заменён крошечной заглушкой хуков (как в ride-booking-path-test): компонент рисуется на
//  один уровень, но состояние, мемо и эффекты настоящие. Сеть, карта и соседние экраны подменены.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { all, browser, FakeEl, finish, flat, JSX, LANG, load, mount, one, PORTAL, REACT, root, rt, settle, test, text } from "./fake-react.mjs";

// ================================================================ 1. Сеть моргнула после смены фильтра

const Home = (
  await load("src/screens/HomeScreen.tsx", [
    [/^react$/, REACT],
    [/^react\/jsx-runtime$/, JSX],
    [/^react-dom$/, PORTAL],
    [/^react-router-dom$/, `export const Link = "Link"; export const useNavigate = () => () => {};`],
    [/\/auth\/AuthProvider$/, `export const useAuth = () => ({ isAuthed: false, status: "guest", user: null });`],
    [/\/i18n\/lang$/, LANG],
    [/\/components\/ScreenHeader$/, `export default "ScreenHeader";`],
    [/\/components\/ModeSwitch$/, `export default "ModeSwitch";`],
    [/\/api\/seasonal$/, `export const fetchSeasonalEvents = async () => ({ items: [] });`],
    [/\/components\/RideSheet$/, `export default "RideSheet";`],
    [/\/components\/YandexMap$/, `export default "YandexMap";`],
    [
      /\/components\/NearbyRideCard$/,
      `export default "NearbyRideCard"; export const NearbySkeletonCard = "NearbySkeletonCard", NearbyMoreCard = "NearbyMoreCard", NearbyEmptyCard = "NearbyEmptyCard", NearbyUnverifiedCard = "NearbyUnverifiedCard";`,
    ],
    [/\/components\/adminUi$/, `export const NearbyChip = "NearbyChip";`],
    [/\/components\/NearbyFilters$/, `export const NearbyCityChips = "NearbyCityChips", NearbyConditionsChip = "NearbyConditionsChip", NearbyWhenChip = "NearbyWhenChip";`],
    [/\/components\/CommunityFeedStrip$/, `export default "CommunityFeedStrip";`],
    [
      /\/api\/discovery$/,
      `export const fetchRidesNear = (q, signal) => globalThis.__rt.fetchRides(q, signal); export const fetchRequestsNear = async () => ({ items: [] });`,
    ],
    // «Только проверенные» из «Безопасности»: в разделе 1 выключены (там про сеть), в разделе 1б — включены.
    [/\/filterPrefs$/, `export const loadFilters = () => ({ conditions: [], onlyTrusted: !!globalThis.__rt.trusted });`],
    [/\/nearbyCityFilter$/, `export const useNearbyCityFilter = () => globalThis.__rt.city;`],
    [/\/components\/Icons$/, `export const IconRides = "Icon", IconGift = "Icon", IconLocate = "Icon", IconClose = "Icon", IconHospital = "Icon", IconChevron = "Icon";`],
    [/\/components\/BrandIcons$/, `export const YuModeTaxi = "Icon";`],
    [/\/components\/PartnerAd$/, `export const PartnerAdSlot = "PartnerAdSlot";`],
    [/\/api\/geo$/, `export const fetchPopularRoutes = async () => [];`],
    [/\/utils\/useVisibleInterval$/, `export const useVisibleInterval = (_ms, cb) => { globalThis.__rt.tick = cb; };`],
    [/\/components\/ComingSoon$/, `export default "ComingSoon";`],
    [/\/components\/States$/, `export const LoadingList = "LoadingList";`],
    [/\/features$/, `export const useFeatures = () => ({ ready: true });`],
    [/\/featureRules$/, `export const isOpen = () => true;`],
  ])
).default;

const NO_CITIES = { from: [], to: [], fromOptions: [], toOptions: [], found: [], active: false, offered: false };
const cityState = (patch = {}) => ({ ...NO_CITIES, setFrom() {}, setTo() {}, addFound() {}, dropUnchosenFound() {}, clear() {}, ...patch });
const ride = (id, extra = {}) => ({
  id,
  from_city: "Уфа",
  to_city: "Сибай",
  depart_at: "2030-01-01T10:00:00",
  seats_left: 2,
  seats_total: 3,
  price: 500,
  child_seat: true,
  ...extra,
});
const ok = (items, count = items.length) => () => Promise.resolve({ items, count });
const offline = () => () => Promise.reject(new TypeError("Failed to fetch"));

/** Карта с двумя поездками на экране; дальше сеть ведёт себя так, как скажет тест. */
async function homeWithList() {
  const calls = [];
  const net = { reply: ok([ride(1), ride(2)]) };
  rt.city = cityState();
  rt.fetchRides = (q) => (calls.push(q), net.reply(q));
  const home = mount(Home, {});
  await settle(home);
  assert.equal(all(home.tree, "NearbyRideCard").length, 2, "исходный список на месте");
  return { home, calls, net };
}
const cards = (home) => all(home.tree, "NearbyRideCard").length;
const errorCard = (home) => all(home.tree, "NearbyEmptyCard").find((n) => n.props.error === true);

await test("сменили день, а сеть упала — вместо старого списка «нет сети» с «Повторить»", async () => {
  const { home, calls, net } = await homeWithList();
  net.reply = offline();
  one(home.tree, "NearbyWhenChip").props.onChange("2030-01-02");
  await settle(home);
  assert.equal(calls.at(-1).date, "2030-01-02", "запрос ушёл уже за новый день");
  assert.equal(cards(home), 0, "поездки других дней под чипом «Когда · 02.01» не остаются");
  assert.ok(errorCard(home), "видно состояние ошибки, а не пустоту");
});

await test("«Повторить» после такого сбоя грузит новый поиск и возвращает список", async () => {
  const { home, calls, net } = await homeWithList();
  net.reply = offline();
  one(home.tree, "NearbyWhenChip").props.onChange("2030-01-02");
  await settle(home);
  net.reply = ok([ride(7)]);
  errorCard(home).props.onRetry();
  await settle(home);
  assert.equal(calls.at(-1).date, "2030-01-02", "повтор — для выбранного дня, а не для прежнего");
  assert.equal(cards(home), 1);
  assert.equal(errorCard(home), undefined);
});

await test("сменили условие при обрыве сети — старый список не остаётся (даже если поездки с креслом)", async () => {
  const { home, net } = await homeWithList();
  net.reply = offline();
  one(home.tree, "NearbyConditionsChip").props.onChange(["child"]);
  await settle(home);
  assert.equal(cards(home), 0);
  assert.ok(errorCard(home));
});

await test("выбрали город «Откуда» при обрыве сети — ошибка, а не поездки из всех городов", async () => {
  const { home, calls, net } = await homeWithList();
  net.reply = offline();
  rt.city = cityState({ from: ["Баймак"], fromOptions: [{ ru: "Баймак", ba: "Баймаҡ" }], active: true, offered: true });
  home.render();
  await settle(home);
  assert.deepEqual(calls.at(-1).from_cities, ["Баймак"]);
  assert.equal(cards(home), 0);
  assert.ok(errorCard(home));
});

await test("включили «Ближайшие» (место) при обрыве сети — ошибка, а не список без радиуса", async () => {
  const { home, calls, net } = await homeWithList();
  net.reply = offline();
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: { geolocation: { getCurrentPosition: (yes) => yes({ coords: { latitude: 54.73, longitude: 55.95 } }) } },
  });
  one(home.tree, "NearbyChip").props.onClick();
  await settle(home);
  assert.equal(calls.at(-1).radius_km, 50, "запрос ушёл уже с местом");
  assert.equal(cards(home), 0);
  assert.ok(errorCard(home));
});

await test("тихое обновление ТОГО ЖЕ поиска при сбое держит список — без экрана ошибки", async () => {
  const { home, calls, net } = await homeWithList();
  net.reply = offline();
  const before = calls.length;
  rt.tick();
  await settle(home);
  assert.equal(calls.length, before + 1, "обновление правда ходило в сеть");
  assert.equal(cards(home), 2, "сеть моргнула — человек продолжает видеть поездки");
  assert.equal(errorCard(home), undefined);
});

await test("после такого сбоя тихое обновление не прячет ошибку, а вернувшаяся сеть сама возвращает список", async () => {
  const { home, net } = await homeWithList();
  net.reply = offline();
  one(home.tree, "NearbyWhenChip").props.onChange("2030-01-02");
  await settle(home);
  rt.tick();
  await settle(home);
  assert.equal(cards(home), 0, "старый список не воскрес");
  assert.ok(errorCard(home));
  net.reply = ok([ride(9)]);
  rt.tick();
  await settle(home);
  assert.equal(cards(home), 1);
  assert.equal(errorCard(home), undefined);
});

await test("«Показать ещё» при сбое не стирает показанное: это тот же поиск", async () => {
  const { home, net } = await homeWithList();
  net.reply = ok([ride(1), ride(2)], 40);
  rt.tick();
  await settle(home);
  net.reply = offline();
  one(home.tree, "NearbyMoreCard").props.onMore();
  await settle(home);
  assert.equal(cards(home), 2);
  assert.equal(errorCard(home), undefined);
});

// ================================================================ 1б. «Только проверенные» по умолчанию (17.10.2026)
//
// Публиковать поездку может каждый, а пассажир по умолчанию видит только проверенных водителей. Пустая
// лента проверенных не врёт «поездок нет»: сервер говорит, сколько поездок этого поиска спрятал фильтр
// (`hidden_unverified`), лента предлагает «Показать» — только для этого поиска, настройку не трогая.

/** Сервер как настоящий: с verified_only проверенных нет, но [hidden] поездок непроверенных спрятано. */
const verifiedServer = (hidden) => (q) =>
  Promise.resolve(q.verified_only ? { items: [], count: 0, hidden_unverified: hidden } : { items: [ride(41), ride(42)], count: 2 });

async function homeVerifiedOnly(hidden = 2, city = {}) {
  const calls = [];
  rt.trusted = true;
  rt.city = cityState(city);
  rt.fetchRides = (q) => (calls.push(q), verifiedServer(hidden)(q));
  const home = mount(Home, {});
  await settle(home);
  return { home, calls };
}
const hint = (home) => all(home.tree, "NearbyUnverifiedCard")[0];

await test("гость, никто ничего не выбирал — лента просит только проверенных", async () => {
  const { calls } = await homeVerifiedOnly();
  assert.equal(calls[0].verified_only, true, "безопасный выбор по умолчанию — и у гостя (стенд: status «guest»)");
});

await test("проверенных нет, непроверенные есть — «Есть 2 поездки…», а не «поездок нет»", async () => {
  const { home } = await homeVerifiedOnly(2);
  assert.ok(hint(home), "нет подсказки о спрятанных поездках");
  assert.equal(hint(home).props.hidden, 2);
  assert.equal(hint(home).props.onReset, undefined, "других фильтров нет — сбрасывать нечего");
  assert.equal(all(home.tree, "NearbyEmptyCard").length, 0, "не голое «поездок рядом пока нет»");
});

await test("«Показать» — тот же поиск без фильтра; пояснение в шторке больше не обещает «только проверенных»", async () => {
  const { home, calls } = await homeVerifiedOnly(2);
  hint(home).props.onShow();
  await settle(home);
  assert.equal(calls.at(-1).verified_only, undefined, "запрос ушёл без verified_only");
  assert.equal(cards(home), 2, "поездки непроверенных водителей на экране");
  assert.equal(hint(home), undefined);
  assert.equal(one(home.tree, "NearbyConditionsChip").props.verifiedOnly, false, "строка «Показываем только проверенных» не врёт");
});

await test("сменил день после «Показать» — снова только проверенные (это уже другой поиск)", async () => {
  const { home, calls } = await homeVerifiedOnly(2);
  hint(home).props.onShow();
  await settle(home);
  one(home.tree, "NearbyWhenChip").props.onChange("2030-01-02");
  await settle(home);
  assert.equal(calls.at(-1).date, "2030-01-02");
  assert.equal(calls.at(-1).verified_only, true, "«Показать» действовал только на прежний поиск");
  assert.ok(hint(home), "на новый день — снова честная подсказка");
});

await test("Завтра → «Показать» → Все дни → Завтра: фильтр снова режет, карточка снова на месте (приёмка PR167)", async () => {
  // На живом сайте после такого круга лента шла без фильтра — без карточки и без нажатия.
  const { home, calls } = await homeVerifiedOnly(2, { offered: true, fromOptions: [{ ru: "Баймак", ba: "Баймаҡ" }] });
  one(home.tree, "NearbyWhenChip").props.onChange("2030-01-02");
  await settle(home);
  hint(home).props.onShow();
  await settle(home);
  assert.equal(calls.at(-1).verified_only, undefined, "«Показать» на «Завтра» сработал");
  one(home.tree, "NearbyWhenChip").props.onChange(null);
  await settle(home);
  assert.equal(calls.at(-1).verified_only, true, "«Все дни» — другой поиск, снова только проверенные");
  one(home.tree, "NearbyWhenChip").props.onChange("2030-01-02");
  await settle(home);
  assert.equal(calls.at(-1).date, "2030-01-02");
  assert.equal(calls.at(-1).verified_only, true, "вернулся на «Завтра» — нажатие не воскресло");
  assert.ok(hint(home), "снова честная карточка «Есть N поездок…»");
});

await test("место уточнилось после «Показать» — непроверенные остаются: это тот же поиск (как в приложении)", async () => {
  const { home, calls } = await homeVerifiedOnly(2);
  hint(home).props.onShow();
  await settle(home);
  assert.equal(cards(home), 2);
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    value: { geolocation: { getCurrentPosition: (yes) => yes({ coords: { latitude: 54.73, longitude: 55.95 } }) } },
  });
  one(home.tree, "NearbyChip").props.onClick();
  await settle(home);
  assert.equal(calls.at(-1).radius_km, 50, "запрос ушёл уже с местом");
  assert.equal(calls.at(-1).verified_only, undefined, "место не вернуло фильтр, который человек снял");
  assert.equal(cards(home), 2);
});

await test("при условиях подсказка даёт и «Сбросить фильтры» — путь назад, как в «с такими условиями»", async () => {
  // На сервере есть города с поездками — ряд фильтров виден и при пустой ленте (`filtersRowVisible`).
  const { home, calls } = await homeVerifiedOnly(1, { offered: true, fromOptions: [{ ru: "Баймак", ba: "Баймаҡ" }] });
  one(home.tree, "NearbyConditionsChip").props.onChange(["child"]);
  await settle(home);
  assert.deepEqual(calls.at(-1).conditions, ["child"]);
  assert.equal(typeof hint(home).props.onReset, "function");
  hint(home).props.onReset();
  await settle(home);
  assert.equal(calls.at(-1).conditions, undefined, "сброс снял условия");
  assert.equal(calls.at(-1).verified_only, true, "а «проверенных» — нет");
});

await test("сервер не насчитал спрятанных (или старый сервер) — обычная пустота с «Обновить»", async () => {
  const { home } = await homeVerifiedOnly(0);
  assert.equal(hint(home), undefined);
  assert.ok(all(home.tree, "NearbyEmptyCard").some((n) => n.props.error === false));
});

rt.trusted = false; // дальше — шторки: «Безопасность» им ни к чему

// ================================================================ 2–3. Шторки ленты

const F = await load(
  `export * from "./src/components/NearbyFilters"; export { useNearbyCityFilter } from "./src/nearbyCityFilter"; export { keepChosenFound } from "./src/nearbyFeed";`,
  [
    [/^react$/, REACT],
    [/^react\/jsx-runtime$/, JSX],
    [/^react-dom$/, PORTAL],
    [/\/i18n\/lang$/, LANG],
    [/\/CityField$/, `export default "CityField";`],
    [/\/conditionIcons$/, `export const ConditionIcon = "ConditionIcon";`],
    [/\/Icons$/, `export const IconCalendar = "Icon", IconCheck = "Icon", IconClose = "Icon", IconFlag = "Icon", IconPin = "Icon", IconShield = "Icon", IconTune = "Icon";`],
    [/\/api\/discovery$/, `export const fetchRideCities = async () => globalThis.__rt.cities;`],
  ]
);

/** Содержимое шторки: функцией (новое) — с закрытием шторки, узлами — как есть. */
const sheetBody = (sheetEl, close) => (typeof sheetEl.props.children === "function" ? sheetEl.props.children(close) : sheetEl.props.children);

// ---------------------------------------------------------------- 2. найденная деревня

const KIPCHAK = { ru: "Кипчак-Аскарово", ba: "Ҡыпсаҡ-Асҡар" };
const ASKAR = { ru: "Аскарово", ba: "Асҡар" };
const TEMYAS = { ru: "Темясово", ba: "Темәс" };

await test("закрыли шторку — найденные и не выбранные ни в «Откуда», ни в «Куда» уходят", async () => {
  assert.equal(typeof F.keepChosenFound, "function", "нет правила «что остаётся после закрытия шторки»");
  assert.deepEqual(F.keepChosenFound([KIPCHAK, ASKAR, TEMYAS], ["Аскарово"], ["Темясово"]), [ASKAR, TEMYAS]);
  assert.deepEqual(F.keepChosenFound([KIPCHAK], [], []), []);
});

/** «Откуда»/«Куда» целиком: настоящее состояние городов + настоящие чипы. */
async function cityChips() {
  rt.cities = { from: [{ ru: "Баймак", ba: "Баймаҡ" }], to: [{ ru: "Уфа", ba: "Өфө" }] };
  const host = mount(() => {
    const f = F.useNearbyCityFilter(0);
    rt.f = f;
    return F.NearbyCityChips({ filter: f });
  }, {});
  await settle(host);
  const chips = all(host.tree, "FeedChip");
  assert.equal(chips.length, 2, "чипы «Откуда» и «Куда» на месте");
  /** Открыть шторку чипа; вернуть шторку и её элемент NearbySheet. */
  const open = async (label) => {
    all(host.tree, "FeedChip").find((n) => n.props.label.startsWith(label)).props.onClick();
    await settle(host);
    const el = one(host.tree, "NearbyCitySheet");
    const sheet = mount(el.type, el.props);
    const sync = async () => {
      await settle(host, sheet);
      const now = all(host.tree, "NearbyCitySheet")[0];
      if (now) sheet.render(now.props);
    };
    const nbs = () => one(sheet.tree, "NearbySheet");
    const rows = (close = () => {}) => all(sheetBody(nbs(), close), "NearbyCheckRow");
    return { sheet, sync, nbs, rows, body: (close) => sheetBody(nbs(), close) };
  };
  return { host, open };
}

await test("нашёл деревню, снял галочку, закрыл шторку — её нет ни в «Откуда», ни в «Куда»", async () => {
  const { host, open } = await cityChips();
  const from = await open("Откуда");
  one(from.body(), "CityField").props.onPick({ name_ru: KIPCHAK.ru, name_ba: KIPCHAK.ba });
  await from.sync();
  assert.deepEqual(rt.f.from, [KIPCHAK.ru], "найденная сразу выбрана");
  from.rows().find((r) => r.props.label === KIPCHAK.ru).props.onToggle();
  await from.sync();
  const row = from.rows().find((r) => r.props.label === KIPCHAK.ru);
  assert.ok(row && row.props.selected === false, "пока шторка открыта, снятую можно вернуть одной галочкой");

  from.nbs().props.onClose(); // так шторка убирает себя, когда уехала
  await settle(host);
  assert.equal(all(host.tree, "NearbyCitySheet").length, 0, "шторка закрыта");
  assert.ok(!rt.f.found.some((c) => c.ru === KIPCHAK.ru), "снятая деревня не висит в найденных");

  const to = await open("Куда");
  assert.ok(!to.rows().some((r) => r.props.label === KIPCHAK.ru), "в «Куда» она тоже не всплывает");
});

await test("выбранная найденная деревня после закрытия остаётся — по ней подписан чип", async () => {
  const { host, open } = await cityChips();
  const from = await open("Откуда");
  one(from.body(), "CityField").props.onPick({ name_ru: ASKAR.ru, name_ba: ASKAR.ba });
  await from.sync();
  from.nbs().props.onClose();
  await settle(host);
  assert.deepEqual(rt.f.found, [ASKAR]);
  assert.ok(all(host.tree, "FeedChip").some((n) => n.props.label === "Откуда · Аскарово"));
});

await test("«Сбросить фильтры» снимает и найденные: деревни нет ни в «Откуда», ни в «Куда» (приёмка PR154)", async () => {
  const { host, open } = await cityChips();
  const from = await open("Откуда");
  one(from.body(), "CityField").props.onPick({ name_ru: KIPCHAK.ru, name_ba: KIPCHAK.ba });
  await from.sync();
  from.nbs().props.onClose();
  await settle(host);
  assert.deepEqual(rt.f.found, [KIPCHAK], "выбранная после закрытия шторки осталась");
  rt.f.clear(); // так зовёт «Сбросить фильтры» в ленте (resetFeedFilters в HomeScreen)
  await settle(host);
  assert.deepEqual(rt.f.from, []);
  assert.deepEqual(rt.f.found, [], "найденные сброшены вместе с выбором");
  for (const label of ["Откуда", "Куда"]) {
    const sheet = await open(label);
    assert.ok(!sheet.rows().some((r) => r.props.label === KIPCHAK.ru), `в «${label}» деревни нет`);
    sheet.nbs().props.onClose();
    await settle(host);
  }
});

await test("в ленте «Сбросить фильтры» правда зовёт сброс городов", async () => {
  let cleared = 0;
  rt.city = cityState({ from: [KIPCHAK.ru], fromOptions: [{ ru: "Баймак", ba: "Баймаҡ" }], found: [KIPCHAK], active: true, offered: true, clear: () => cleared++ });
  rt.fetchRides = () => Promise.resolve({ items: [], count: 0 });
  const home = mount(Home, {});
  await settle(home);
  const reset = flat(home.tree).find((n) => n.type === "button" && n.props.className === "nearby-filtered__reset");
  assert.ok(reset, "пусто из-за города — есть «Сбросить фильтры»");
  reset.props.onClick();
  assert.equal(cleared, 1);
});

// ---------------------------------------------------------------- 3. закрытие шторки движением

/** Шторка с кнопкой «Готово» внутри; закрытия считаем. */
function sheetWithDone() {
  const closes = [];
  const sheet = mount(F.NearbySheet, {
    title: "Откуда",
    onClose: () => closes.push(1),
    children: (close) => ({ type: "button", props: { className: "btn-primary nbs__cta", onClick: close, children: "Готово" } }),
  });
  const backdrop = () => sheet.tree;
  const dialog = () => flat(sheet.tree).find((n) => n.props?.role === "dialog");
  const leaving = () => /\bis-leaving\b/.test(backdrop().props.className);
  const ended = (name = "sheetDown", fromChild = false) => {
    const el = dialog();
    const target = fromChild ? {} : el;
    el.props.onAnimationEnd?.({ target, currentTarget: el, animationName: name });
  };
  return { sheet, closes, backdrop, dialog, leaving, ended };
}
const ESC = { key: "Escape", stopPropagation() {}, preventDefault() {} };

for (const [how, act] of [
  ["тап по затемнению", (s) => s.backdrop().props.onClick()],
  ["Esc", (s) => s.dialog().props.onKeyDown(ESC)],
  ["крестик", (s) => flat(s.sheet.tree).find((n) => n.props?.className === "nbs__close").props.onClick()],
  ["«Готово»", (s) => flat(s.sheet.tree).find((n) => n.props?.className === "btn-primary nbs__cta").props.onClick()],
]) {
  await test(`${how}: шторка сначала уезжает вниз (затемнение гаснет), и только потом пропадает`, async () => {
    browser.reduced = false;
    const s = sheetWithDone();
    act(s);
    s.sheet.render();
    assert.equal(s.closes.length, 0, "не пропала мгновенно");
    assert.ok(s.leaving(), "включилось движение закрытия (is-leaving)");
    s.ended();
    assert.equal(s.closes.length, 1, "доехала — убрана");
    act(s);
    s.ended();
    assert.equal(s.closes.length, 1, "второе нажатие во время ухода не закрывает дважды");
  });
}

await test("уходящая шторка ленты — inert: «Показать N поездок», «Готово», галочки и дни во время ухода не нажать (приёмка PR154)", async () => {
  browser.reduced = false;
  const s = sheetWithDone();
  const node = s.dialog().props.ref.current;
  node.querySelectorAll().at(-1).focus(); // фокус на «Готово»
  s.dialog().props.onKeyDown(ESC);
  s.sheet.render();
  assert.equal(node.inert, true, "в уходящую шторку ни нажать, ни перейти по Tab");
  assert.equal(globalThis.document.activeElement, globalThis.document.body, "фокус снят сразу: пробел следом за Esc галочку не переключит");
  flat(s.sheet.tree).find((n) => n.props?.className === "btn-primary nbs__cta").props.onClick();
  s.ended();
  assert.equal(s.closes.length, 1, "«Готово» во время ухода не закрывает второй раз");
});

await test("конец движения строки или кнопки внутри шторку не закрывает — только её собственное", async () => {
  browser.reduced = false;
  const s = sheetWithDone();
  s.backdrop().props.onClick();
  s.sheet.render();
  s.ended("fadeIn", true);
  assert.equal(s.closes.length, 0);
  s.ended();
  assert.equal(s.closes.length, 1);
});

await test("конец движения не пришёл (вкладка в фоне) — шторка всё равно закрывается по его длительности", async () => {
  browser.reduced = false;
  browser.timers.length = 0;
  const s = sheetWithDone();
  s.backdrop().props.onClick();
  s.sheet.render();
  const pending = browser.timers.filter((t) => t.on);
  assert.equal(pending.length, 1, "есть страховочный таймер");
  pending[0].fn();
  assert.equal(s.closes.length, 1);
  s.ended();
  assert.equal(s.closes.length, 1, "и не закрывается второй раз");
});

await test("«Уменьшить движение» — шторка закрывается сразу, без ухода вниз", async () => {
  browser.reduced = true;
  try {
    const s = sheetWithDone();
    s.backdrop().props.onClick();
    s.sheet.render();
    assert.equal(s.closes.length, 1);
    assert.ok(!s.leaving());
  } finally {
    browser.reduced = false;
  }
});

await test("после закрытия фокус возвращается на чип, с которого открыли", async () => {
  browser.reduced = false;
  const chip = new FakeEl("chip");
  chip.focus();
  const s = sheetWithDone();
  s.backdrop().props.onClick();
  s.sheet.render();
  s.ended();
  s.sheet.unmount();
  assert.equal(chip.focused, 2, "фокус вернулся на чип");
});

await test("«Показать N поездок» в «Фильтрах» и выбор дня закрывают шторку тем же движением", async () => {
  browser.reduced = false;
  // «Фильтры»
  const cond = mount(F.NearbyConditionsChip, { chosen: ["child"], onChange() {}, verifiedOnly: false, total: 5 });
  one(cond.tree, "FeedChip").props.onClick();
  cond.render();
  let closing = 0;
  const cta = flat(sheetBody(one(cond.tree, "NearbySheet"), () => closing++)).find((n) => /nbs__cta/.test(n.props?.className ?? ""));
  assert.match(text(cta), /Показать 5 поездок/);
  cta.props.onClick();
  assert.equal(closing, 1, "кнопка просит шторку уйти, а не выдёргивает её");
  // «Когда»
  const picked = [];
  const when = mount(F.NearbyWhenChip, { day: null, onChange: (d) => picked.push(d) });
  one(when.tree, "FeedChip").props.onClick();
  when.render();
  closing = 0;
  one(sheetBody(one(when.tree, "NearbySheet"), () => closing++), "NearbyDayOptions").props.onPick("2030-01-02");
  assert.deepEqual(picked, ["2030-01-02"], "день применился сразу");
  assert.equal(closing, 1, "и шторка ушла тем же движением");
});

await test("«Готово» в шторке города — то же движение", async () => {
  browser.reduced = false;
  const { open } = await cityChips();
  const from = await open("Откуда");
  let closing = 0;
  const done = flat(from.body(() => closing++)).find((n) => /nbs__cta/.test(n.props?.className ?? ""));
  assert.equal(text(done), "Готово");
  done.props.onClick();
  assert.equal(closing, 1);
});

await test("стили: пара к sheetUp/fadeIn — шторка уезжает вниз, затемнение гаснет; время только со шкалы", async () => {
  const css = readFileSync(root + "src/ui.css", "utf8");
  const sheetAt = css.indexOf("/* ---- Нижняя шторка (bottom sheet) ---- */");
  const formsAt = css.indexOf("/* ---- Формы (заявка, отклик) ---- */");
  const block = css.slice(sheetAt, formsAt);
  assert.ok(sheetAt > 0 && formsAt > sheetAt, "блок шторки найден");
  assert.ok(
    /@keyframes sheetDown\s*\{[^}]*from\s*\{\s*transform:\s*translateY\(0\);?\s*\}\s*to\s*\{\s*transform:\s*translateY\(100%\);?\s*\}/.test(block),
    "нет @keyframes sheetDown — пары к sheetUp (вверх → вниз)"
  );
  const leave = block.match(/\.sheet-backdrop\.is-leaving\s*\{([^}]*)\}/);
  assert.ok(leave, "у затемнения есть уход");
  assert.match(leave[1], /animation:\s*\w+\s+var\(--motion-[\w-]+\)/, "затемнение гаснет за время со шкалы");
  const down = block.match(/\.sheet-backdrop\.is-leaving \.sheet\s*\{([^}]*)\}/);
  assert.ok(down, "у шторки есть уход");
  assert.match(down[1], /animation:\s*sheetDown\s+var\(--motion-[\w-]+\)/);
  assert.ok(!/\d+m?s\b/.test((leave[1] + down[1]).replace(/var\(--motion-[\w-]+\)/g, "")), "время числом");
  const index = readFileSync(root + "src/index.css", "utf8");
  assert.match(index, /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\*\s*\{\s*animation:\s*none !important;/, "при «Уменьшить движение» анимаций нет — NearbySheet закрывается сразу");
});

finish();
