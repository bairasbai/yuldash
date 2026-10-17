// ================================================================
//  Запуск волнами: выключатели направлений (GET /config/features).
//
//  Волна 1 — только попутки. Такси, курьеры, посылки, реклама и «Скидки по пути»
//  включает сервер, сайт обновлять не нужно. Поломки, которые эти проверки ловят:
//
//   1. Неизвестное считается включённым — человек попадает на экран, который
//      упирается в отказ сервера (403). Правило одно: НЕИЗВЕСТНО → ВЫКЛЮЧЕНО.
//   2. Битый ответ или битая запись в хранилище роняет приложение или затирает
//      хорошее запомненное.
//   3. Запомненное не стареет — выключенное направление остаётся «включённым» на
//      сутки; или наоборот, сайт молотит сервер при каждом открытии экрана.
//   4. Вход в направление остаётся открытым в обход: прямая ссылка, пуш, плитка
//      профиля, реклама из кеша.
//
//  Первые три — исполняем настоящий код (featureRules.ts) с подставными часами,
//  хранилищем и сетью. Четвёртое — читаем исходники экранов, как states-test.
// ================================================================
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import * as R from "./.bundles/features-bundle.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, "..", "src");
const read = (rel) => readFileSync(join(SRC, rel), "utf8").replace(/\r\n/g, "\n");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b);

const KEYS = ["taxi", "courier", "parcels", "ads", "business"];
const ALL_ON = { rides: true, taxi: true, courier: true, parcels: true, ads: true, business: true };
const ALL_OFF = { rides: true, taxi: false, courier: false, parcels: false, ads: false, business: false };
const flagsOf = (s) => ({ rides: s.rides, taxi: s.taxi, courier: s.courier, parcels: s.parcels, ads: s.ads, business: s.business });

// ---------------------------------------------------------------- 1. разбор ответа
check(eq(R.parseFeatures(ALL_ON), ALL_ON), "ответ «всё включено» разобран как есть");
check(eq(R.parseFeatures({ ...ALL_OFF, parcels: true }), { ...ALL_OFF, parcels: true }), "включённое одно направление не тянет остальные");

for (const [label, junk] of [
  ["null", null],
  ["undefined", undefined],
  ["строка", "{\"taxi\":true}"],
  ["число", 1],
  ["true", true],
  ["массив", [true, true]],
  ["пустой объект", {}],
]) {
  check(eq(R.parseFeatures(junk), ALL_OFF), `неизвестное (${label}) → всё выключено, попутки остаются`);
}

for (const [label, value] of [
  ["строка «true»", "true"],
  ["число 1", 1],
  ["строка «1»", "1"],
  ["null", null],
  ["объект", {}],
  ["массив", [true]],
]) {
  const got = R.parseFeatures({ taxi: value, courier: value, parcels: value, ads: value, business: value });
  check(KEYS.every((k) => got[k] === false), `не-булево значение (${label}) считается выключенным`);
}

check(R.parseFeatures({ ...ALL_ON, rides: false }).rides === true, "rides всегда true, даже если сервер прислал false");
check(R.parseFeatures({ taxi: true, unknown_new_flag: true }).taxi === true &&
  !("unknown_new_flag" in R.parseFeatures({ unknown_new_flag: true })), "незнакомые поля игнорируются");
check(eq(R.parseFeatures({ taxi: true, parcels: "yes", ads: 1 }), { ...ALL_OFF, taxi: true }),
  "частичный ответ: включено только то, что прямо сказано true");

check(R.parseFeaturesResponse(null) === null && R.parseFeaturesResponse("<html>") === null && R.parseFeaturesResponse([]) === null,
  "ответ не-объект (HTML прокси, строка, массив) — это сбой запроса, а не «всё выключено»");
check(eq(R.parseFeaturesResponse({}), ALL_OFF), "пустой объект — это ответ «ничего не включено»");
check(R.FEATURES_OFF.rides === true && KEYS.every((k) => R.FEATURES_OFF[k] === false), "FEATURES_OFF: только попутки");

// ---------------------------------------------------------------- 2. запомненное
const AT = 1_800_000_000_000;
const good = R.serializeFeatures({ ...ALL_OFF, parcels: true }, AT);
check(eq(R.parseCachedFeatures(good), { flags: { ...ALL_OFF, parcels: true }, at: AT }), "запись → чтение возвращает те же флаги и время");
for (const [label, text] of [
  ["null", null],
  ["пусто", ""],
  ["битый JSON", "{\"at\": 17,"],
  ["не объект", "[1,2]"],
  ["нет метки времени", JSON.stringify({ v: 1, flags: ALL_ON })],
  ["метка — строка", JSON.stringify({ v: 1, at: "1800000000000", flags: ALL_ON })],
  ["метка — ноль", JSON.stringify({ v: 1, at: 0, flags: ALL_ON })],
  ["метка — минус", JSON.stringify({ v: 1, at: -5, flags: ALL_ON })],
]) {
  check(R.parseCachedFeatures(text) === null, `запись в хранилище испорчена (${label}) → как будто её нет`);
}
check(eq(R.parseCachedFeatures(JSON.stringify({ v: 1, at: AT, flags: { taxi: "true", ads: true } })).flags, { ...ALL_OFF, ads: true }),
  "в запомненном не-булево значение тоже считается выключенным");
check(eq(R.parseCachedFeatures(JSON.stringify({ v: 1, at: AT })).flags, ALL_OFF), "в запомненном нет флагов → всё выключено");

const stored = JSON.parse(R.serializeFeatures(ALL_ON, AT));
check(eq(Object.keys(stored).sort(), ["at", "flags", "v"]) && eq(Object.keys(stored.flags).sort(), [...KEYS, "rides"].sort()),
  "в хранилище лежат только метка времени и шесть флагов — ничего личного");

const memory = () => {
  const map = new Map();
  return { map, getItem: (k) => (map.has(k) ? map.get(k) : null), setItem: (k, v) => map.set(k, String(v)) };
};
{
  const s = memory();
  R.writeFeaturesCache(s, ALL_ON, AT);
  check(s.map.has("yuldash.features") && R.FEATURES_KEY === "yuldash.features", "флаги пишутся под ключом yuldash.features");
  check(eq(R.readFeaturesCache(s), { flags: ALL_ON, at: AT }), "readFeaturesCache читает то, что записал writeFeaturesCache");
}
{
  const closed = { getItem() { throw new Error("SecurityError"); }, setItem() { throw new Error("QuotaExceeded"); } };
  let threw = false;
  try { R.writeFeaturesCache(closed, ALL_ON, AT); } catch { threw = true; }
  check(!threw && R.readFeaturesCache(closed) === null, "закрытое хранилище (приватный режим) не роняет ни чтение, ни запись");
  check(R.readFeaturesCache(null) === null && (R.writeFeaturesCache(null, ALL_ON, AT), true), "нет хранилища вовсе — тоже не падаем");
}

// ---------------------------------------------------------------- 3. устаревание
const MIN = 60 * 1000;
check(R.isStale(AT, AT + 4 * MIN) === false, "запомненному 4 минуты — свежее");
check(R.isStale(AT, AT + 5 * MIN) === true, "ровно 5 минут — уже устарело");
check(R.isStale(AT, AT + 60 * MIN) === true, "час — устарело");
check(R.isStale(AT, AT - 10 * MIN) === true, "часы откатили назад → устарело (метка из будущего не держит ответ вечно)");
check(R.isStale(0, AT) === true && R.isStale(NaN, AT) === true, "нет метки времени → устарело");
check(R.shouldRefresh(AT, AT + MIN, 0) === false, "свежее — сервер не трогаем");
check(R.shouldRefresh(0, AT, 0) === true, "ни разу не спрашивали — спрашиваем");
check(R.shouldRefresh(AT, AT + 6 * MIN, AT + 6 * MIN - 5000) === false, "устарело, но попытка была 5 секунд назад — не молотим");
check(R.shouldRefresh(AT, AT + 6 * MIN, AT + 6 * MIN - 20000) === true, "устарело, последняя попытка 20 секунд назад — можно снова");
check(R.FEATURES_MAX_AGE_MS === 5 * MIN, "свежесть запомненного — пять минут, как в Android");

// ---------------------------------------------------------------- 4. решения гейтов
const combos = [];
for (const parcels of [false, true]) for (const courier of [false, true]) combos.push({ ...ALL_OFF, parcels, courier });
for (const f of combos) {
  const tag = `parcels=${f.parcels}, courier=${f.courier}`;
  check(R.isOpen("delivery", f) === (f.parcels || f.courier), `доставка открыта, если есть хоть одна половина (${tag})`);
  check(R.isOpen("courier", f) === f.courier, `курьерский режим открыт только при courier (${tag})`);
  const kind = R.soonKind("courier", f);
  const want = f.courier ? null : f.parcels ? "courier" : "delivery";
  check(kind === want, `«скоро» для /courier: ${want ?? "открыто"} (${tag})`);
  check(R.soonKind("delivery", f) === (f.parcels || f.courier ? null : "delivery"), `«скоро» для доставки (${tag})`);
  const types = R.deliveryTypes(f);
  check(
    types.includes("poputka") === f.parcels && types.includes("courier") === f.courier && types.includes("buy_bring") === f.courier,
    `мастер отправки: «по пути» = parcels, курьер и «купи-привези» = courier (${tag})`
  );
  check(R.defaultDeliveryType(f) === (f.parcels ? "poputka" : "courier"), `способ по умолчанию (${tag})`);
  check(R.isCarryOpen(f) === f.parcels, `«Возить → Доступные» открыто только при parcels (${tag})`);
}
check(["delivery", "courier", "business", "ads"].every((n) => R.isOpen(n, R.FEATURES_OFF) === false && R.soonKind(n, R.FEATURES_OFF) !== null),
  "неизвестное состояние закрывает каждый вход");
check(["delivery", "courier", "business", "ads"].every((n) => R.isOpen(n, ALL_ON) && R.soonKind(n, ALL_ON) === null),
  "всё включено — все входы открыты");
check(R.isOpen("business", { ...ALL_OFF, ads: true }) === false && R.isOpen("ads", { ...ALL_OFF, business: true }) === false,
  "реклама и «Скидки по пути» включаются независимо друг от друга");
check(R.isTaxiOpen(R.FEATURES_OFF) === false && R.isTaxiOpen({ ...ALL_OFF, taxi: true }) === true, "такси для гостя: закрыто, пока не включено");

// ---------------------------------------------------------------- 5. хранилище
function rig({ cache, fetchRaw, startAt = AT, timeoutMs, online } = {}) {
  const clock = { t: startAt };
  const storage = memory();
  if (cache) storage.map.set("yuldash.features", cache);
  const calls = { n: 0 };
  const store = R.createFeatureStore({
    storage: () => storage,
    now: () => clock.t,
    timeoutMs, online,
    fetchRaw: (signal) => {
      calls.n++;
      return fetchRaw(signal);
    },
  });
  return { clock, storage, calls, store };
}
const ok = (payload) => () => Promise.resolve(payload);
const fail = () => Promise.reject(new Error("offline"));

{
  const r = rig({ fetchRaw: ok(ALL_ON) });
  const s = r.store.getSnapshot();
  check(s.ready === false && eq(flagsOf(s), ALL_OFF), "холодный старт без запомненного: ответ неизвестен, всё выключено");
}
{
  const r = rig({ cache: R.serializeFeatures({ ...ALL_OFF, ads: true }, AT), fetchRaw: ok(ALL_OFF), startAt: AT + MIN });
  const s = r.store.getSnapshot();
  check(s.ready === false && eq(flagsOf(s), ALL_OFF), "enabled-кеш не открывает холодный процесс без живого ответа");
  await r.store.refresh();
  check(r.calls.n === 1 && eq(flagsOf(r.store.getSnapshot()), ALL_OFF), "холодный старт требует живого подтверждения даже при свежем кеше");
}
{
  const r = rig({ cache: R.serializeFeatures({ ...ALL_OFF, ads: true }, AT), fetchRaw: ok({ ...ALL_OFF, business: true }), startAt: AT + 6 * MIN });
  let notified = 0;
  r.store.subscribe(() => notified++);
  await r.store.refresh();
  const s = r.store.getSnapshot();
  check(r.calls.n === 1 && s.business === true && s.ads === false && s.ready === true, "запомненное устарело → спросили сервер, ответ заменил старое");
  check(notified === 1, "экран узнаёт о новом ответе ровно один раз");
  check(eq(R.readFeaturesCache(r.storage).flags, { ...ALL_OFF, business: true }) && R.readFeaturesCache(r.storage).at === r.clock.t,
    "ответ сервера записан в хранилище с меткой времени");
}
{
  const r = rig({ fetchRaw: fail });
  await r.store.refresh();
  const s = r.store.getSnapshot();
  check(s.ready === true && eq(flagsOf(s), ALL_OFF), "сеть упала, запомненного нет → «известно: выключено», а не вечная загрузка");
  check(eq(R.readFeaturesCache(r.storage).flags, ALL_OFF), "сбой записывает безопасное OFF в хранилище");
}
{
  const r = rig({ cache: R.serializeFeatures({ ...ALL_OFF, parcels: true }, AT), fetchRaw: fail, startAt: AT + 6 * MIN });
  await r.store.refresh();
  const s = r.store.getSnapshot();
  check(eq(flagsOf(s), ALL_OFF) && s.ready === true, "сеть упала — даже прежний enabled-кеш закрывается");
  check(eq(R.readFeaturesCache(r.storage).flags, ALL_OFF), "сбой очищает все enabled в запомненном");
}
for (const [label, payload] of [["HTML", "<html>502</html>"], ["массив", []], ["null", null]]) {
  const r = rig({ cache: R.serializeFeatures({ ...ALL_OFF, parcels: true }, AT), fetchRaw: ok(payload), startAt: AT + 6 * MIN });
  await r.store.refresh();
  check(eq(flagsOf(r.store.getSnapshot()), ALL_OFF) && eq(R.readFeaturesCache(r.storage).flags, ALL_OFF),
    `ответ-мусор (${label}) закрывает прежние enabled`);
}
{
  const r = rig({ fetchRaw: ok({ taxi: true, parcels: "yes", courier: 1 }) });
  await r.store.refresh();
  const s = r.store.getSnapshot();
  check(s.taxi === true && s.parcels === false && s.courier === false, "частичный ответ сервера: включено только прямое true");
}
{
  const r = rig({ cache: "{broken", fetchRaw: ok(ALL_ON) });
  const s = r.store.getSnapshot();
  check(s.ready === false && eq(flagsOf(s), ALL_OFF), "битая запись в хранилище при старте = запомненного нет");
  await r.store.refresh();
  check(r.store.getSnapshot().taxi === true && R.readFeaturesCache(r.storage) !== null, "и она заменяется нормальным ответом сервера");
}
{
  const r = rig({ fetchRaw: ok(ALL_ON) });
  const a = r.store.refresh();
  const b = r.store.refresh();
  await Promise.all([a, b]);
  check(r.calls.n === 1, "два экрана спросили одновременно — ушёл один запрос");
}
{
  const r = rig({ fetchRaw: fail });
  await r.store.refresh();
  await r.store.refresh();
  check(r.calls.n === 1, "после сбоя повтор в ту же секунду не уходит (не молотим лежащую сеть)");
  r.clock.t += 20_000;
  await r.store.refresh();
  check(r.calls.n === 2, "через 20 секунд повтор уходит");
  await r.store.refresh({ force: true });
  check(r.calls.n === 3, "force спрашивает сразу");
}
{
  const r = rig({ fetchRaw: () => { throw new Error("sync boom"); } });
  let threw = false;
  try { await r.store.refresh(); } catch { threw = true; }
  check(!threw && r.store.getSnapshot().ready === true, "исключение вместо промиса в сети не вылетает наружу");
}
{
  const r = rig({ fetchRaw: ok(ALL_ON) });
  const first = r.store.getSnapshot();
  check(r.store.getSnapshot() === first, "снимок один и тот же, пока ничего не менялось (иначе useSyncExternalStore зациклит отрисовку)");
  let n = 0;
  const off = r.store.subscribe(() => n++);
  off();
  await r.store.refresh();
  check(n === 0 && r.store.getSnapshot() !== first, "отписавшийся экран больше не получает обновлений");
}
{
  const r = rig({ cache: R.serializeFeatures({ ...ALL_OFF, parcels: true }, AT), fetchRaw: ok({ ...ALL_OFF }), startAt: AT + 10 * MIN });
  await r.store.refresh();
  check(r.store.getSnapshot().parcels === false, "направление выключили на сервере → через пять минут экран закрывается");
}

{
  let response = ALL_ON;
  const r = rig({ fetchRaw: () => response instanceof Error ? Promise.reject(response) : Promise.resolve(response) });
  await r.store.refresh();
  check(eq(flagsOf(r.store.getSnapshot()), ALL_ON), "живой ответ открывает направления");
  response = new Error("network lost");
  await r.store.refresh({ force: true });
  check(eq(flagsOf(r.store.getSnapshot()), ALL_OFF) && r.store.getSnapshot().ready, "сбой после живого enabled закрывает всё, кроме попуток");
}
{
  let signal;
  const r = rig({ cache: R.serializeFeatures(ALL_ON, AT), timeoutMs: 10,
    fetchRaw: (seen) => { signal = seen; return new Promise(() => {}); } });
  await r.store.refresh();
  check(signal.aborted && r.store.getSnapshot().ready && eq(flagsOf(r.store.getSnapshot()), ALL_OFF),
    "реальный таймер отменяет молчащий запрос и завершает загрузку с OFF");
  check(eq(R.readFeaturesCache(r.storage).flags, ALL_OFF), "таймаут очищает enabled-кеш");
}
{
  let resolveOld;
  let turn = 0;
  const r = rig({ fetchRaw: () => ++turn === 1 ? new Promise((resolve) => { resolveOld = resolve; }) : Promise.resolve(ALL_ON) });
  const old = r.store.refresh();
  await Promise.resolve();
  r.store.disable();
  check(r.store.getSnapshot().ready && eq(flagsOf(r.store.getSnapshot()), ALL_OFF), "системный offline немедленно закрывает направления");
  await r.store.refresh({ force: true });
  resolveOld(ALL_OFF);
  await old;
  check(eq(flagsOf(r.store.getSnapshot()), ALL_ON) && r.calls.n === 2, "поздний отменённый ответ не затирает новое живое подтверждение");
}
{
  const r = rig({ cache: R.serializeFeatures(ALL_ON, AT), online: () => false, fetchRaw: ok(ALL_ON) });
  await r.store.refresh();
  check(r.calls.n === 0 && r.store.getSnapshot().ready && eq(flagsOf(r.store.getSnapshot()), ALL_OFF),
    "браузер offline: нет HTTP и enabled-кеш не открывает направления");
}

// ---------------------------------------------------------------- 6. проводка на экранах
//
// Читаем исходники: закрытый вход не должен остаться открытым в обход гейта.
const app = read("App.tsx");

/** Кусок App.tsx от `path="…"` до следующего `<Route`. */
function routeBlock(path) {
  const start = app.indexOf(`path="${path}"`);
  if (start < 0) return null;
  const next = app.indexOf("<Route", start + 1);
  return app.slice(start, next < 0 ? undefined : next);
}

const GATED = {
  "/courier": "courier",
  "/courier-earnings": "courier",
  "/coupons": "business",
  "/partner": "business",
  "/ads": "ads",
  "/ads/new": "ads",
  "/ads/:id/edit": "ads",
};
for (const [path, need] of Object.entries(GATED)) {
  const block = routeBlock(path);
  check(!!block && block.includes(`<FeatureGate need="${need}">`), `маршрут ${path} стоит за гейтом «${need}»`);
}
{
  const block = routeBlock("/parcels");
  check(!!block && block.includes('<FeatureGate need="delivery" guestOnly>'),
    "/parcels: гостю закрытой доставки — «скоро», вошедшему экран открыт (мои посылки доживают)");
}
{
  const block = routeBlock("/taxi");
  check(!!block && block.includes("<TaxiGuestGate>") && app.includes("taxiGateView({"),
    "/taxi: гость при выключенном такси видит «Такси скоро», а не просьбу войти");
  const gate = app.match(/function TaxiGuestGate[\s\S]*?\n}\n/)?.[0] ?? "";
  check(gate.includes('status !== "guest"') && gate.includes("isTaxiOpen(features)"),
    "гейт такси не закрывает вошедшего: у него может быть живой заказ");
}
{
  const block = routeBlock("/courier-onboarding");
  check(!!block && !block.includes("FeatureGate"), "/courier-onboarding открыт всегда: набор курьеров идёт и до запуска");
}
{
  const adminBlocks = [...app.matchAll(/path="(\/admin[^"]*)"/g)].map((m) => [m[1], routeBlock(m[1])]);
  check(adminBlocks.length > 20 && adminBlocks.every(([, b]) => b && !b.includes("FeatureGate") && !b.includes("TaxiGuestGate")),
    "админка флагами не закрывается: админ готовит направления заранее", `маршрутов: ${adminBlocks.length}`);
}
for (const tag of ["CouponsScreen", "PartnerCabinetScreen", "AdsCabinetScreen", "AdEditorScreen", "CourierScreen", "CourierEarningsScreen"]) {
  const uses = [...app.matchAll(new RegExp(`<${tag} />`, "g"))];
  const unguarded = uses.filter((m) => !/<FeatureGate need="[a-z]+"[^>]*>\s*(<RequireAuth>\s*)?$/.test(app.slice(Math.max(0, m.index - 140), m.index)));
  check(uses.length > 0 && unguarded.length === 0, `${tag} нигде не подключён в обход гейта`);
}

// Уведомления и ссылки идут через маршруты — значит, получают заглушку сами.
{
  const notif = read("screens/NotificationsScreen.tsx");
  const targets = [...notif.matchAll(/return "(\/[a-z-]+)";/g)].map((m) => m[1]);
  const closed = ["/parcels", "/taxi", "/partner", "/ads"];
  check(closed.every((p) => targets.includes(p)), "уведомления ведут на закрываемые входы только по маршрутам", targets.join(" "));
  check(closed.every((p) => routeBlock(p) && /FeatureGate|TaxiGuestGate/.test(routeBlock(p))), "каждый такой маршрут стоит за гейтом");
}

// Все исходные места, откуда можно прийти на закрытое, идут через navigate/Link — не мимо роутера.
{
  const offenders = [];
  for (const rel of ["screens/ProfileScreen.tsx", "screens/HomeScreen.tsx", "screens/CouponsScreen.tsx", "screens/NotificationsScreen.tsx"]) {
    const code = read(rel);
    if (/window\.location\.(href|assign)\s*=?\s*\(?\s*["'`]\/(coupons|partner|ads|parcels|courier)/.test(code)) offenders.push(rel);
  }
  check(offenders.length === 0, "никто не переходит на закрытое прямой ссылкой в обход роутера", offenders.join(", "));
}

// Профиль: плитки закрытого не показываются.
{
  const profile = read("screens/ProfileScreen.tsx");
  const block = (key) => {
    const i = profile.indexOf(`key: "${key}"`);
    return i < 0 ? "" : profile.slice(i, profile.indexOf("key: ", i + 8));
  };
  const needOf = { parcels: "delivery", courier: "delivery", coupons: "business", partner: "business", ads: "ads" };
  for (const [key, need] of Object.entries(needOf)) {
    check(block(key).includes(`need: "${need}"`), `плитка профиля «${key}» зависит от «${need}»`);
  }
  // Паритет с Android (17.10.2026): такси выключено — такси-плиток нет (taxiOn в ProfileScreen.kt).
  check(["taxi", "taxi-drive", "scheduled"].every((k) => block(k).includes('need: "taxi"')),
    "плитки «Быстрый заказ», «Я на линии (такси)», «Мои предзаказы» зависят от «taxi»");
  check(!block("driver").includes("need:") && !block("promo").includes("need:"),
    "плитки водителя и промокода флагами не прячутся");
  check(profile.includes("(!r.need || isOpen(r.need, features))"), "список плиток фильтруется по флагам");
  check(profile.includes("if (!items.length) return null;"), "заголовок раздела без единой плитки тоже прячется");
}

// Реклама: ни запроса, ни показа из кеша при выключенной рекламе.
{
  const ad = read("components/PartnerAd.tsx");
  const hook = ad.slice(ad.indexOf("export function usePartnerAds"), ad.indexOf("function telHref"));
  const fetchAt = hook.indexOf("fetchAds(");
  const guardAt = hook.indexOf("if (!adsOpen)");
  check(hook.includes("useFeatures().ads") && guardAt >= 0 && fetchAt > guardAt, "реклама не запрашивается, пока ads выключен");
  check(/return adsOpen \? ads : EMPTY_ADS;/.test(hook), "уже загруженная реклама не показывается, когда ads выключили");
}

// Мастер отправки и вкладки посылок.
{
  const wiz = read("components/ParcelSendWizard.tsx");
  check(wiz.includes('allowed.includes("poputka") &&') && wiz.includes('allowed.includes("courier") &&') && wiz.includes('allowed.includes("buy_bring") &&'),
    "в мастере нет карточек закрытых способов доставки");
  check(!/setPickedType\("poputka"\)/.test(wiz) && wiz.includes("setPickedType(firstType)"), "после отправки мастер возвращается к первому ДОСТУПНОМУ способу");
  const parcels = read("screens/ParcelsScreen.tsx");
  check(parcels.includes('picked ?? (canSend ? "send" : "mine")'), "при закрытой доставке /parcels открывается на «Мои»");
  check(parcels.includes("deliveryTypes(features)") && parcels.includes('<ComingSoon kind="delivery" variant="inline" />'),
    "«Отправить» при закрытой доставке показывает заглушку, при открытой — мастер с доступными способами");
  check(parcels.includes("isCarryOpen(features)") && /carryOpen \? <AvailableList \/> : <ComingSoon/.test(parcels),
    "«Возить → Доступные» при выключенных посылках — заглушка (запрос вернул бы 403)");
}

// Главная: вкладка «Курьер».
{
  const home = read("screens/HomeScreen.tsx");
  check(home.includes('isOpen("delivery", features)') && home.includes("setDeliveryTab(true)"), "главная: «Курьер» при закрытой доставке остаётся на месте");
  check(home.includes('<ComingSoon kind="delivery" variant="inline" onFindRide={() => setDeliveryTab(false)} />'),
    "главная: встроенная заглушка доставки, «Найти попутку» возвращает на карту");
  check(/else if \(m === "courier"\) \{\s*if \(deliveryOpen\) navigate\("\/parcels"\);/.test(home), "доставка открыта — «Курьер» ведёт в посылки, как раньше");
}

// Сеть и ключ.
{
  const features = read("features.ts");
  check(features.includes('"/config/features"') && /auth:\s*false/.test(features), "флаги запрашиваются без токена со своего сервера");
  const guard = readFileSync(join(HERE, "privacy-guard-test.mjs"), "utf8");
  check(/DEVICE_KEYS = new Set\(\[[\s\S]*?"yuldash\.features"[\s\S]*?\]\)/.test(guard), "yuldash.features отнесён к настройкам устройства (не личное)");
  const privacy = read("utils/privacy.ts");
  check(!privacy.includes("yuldash.features"), "флаги не стираются при выходе: они общие для всех, а не чьи-то");
}

// Тексты «Скоро в Юлдаше» — слово в слово с Android (ComingSoon.kt).
{
  const soon = read("components/ComingSoon.tsx");
  const must = [
    "Скоро в Юлдаше", "Тиҙҙән Юлдашта",
    "Посылки и курьеры скоро появятся в Юлдаше. Пока Юлдаш — это попутки: найди машину по пути или предложи свою.",
    "Бандеролдәр һәм курьерҙар тиҙҙән Юлдашта булыр. Әлегә Юлдаш — юлдаш менән сәфәр: юл ыңғайындағы машина тап йәки үҙ сәфәреңде тәҡдим ит.",
    "Реклама в Юлдаше скоро откроется. Готовим честную маркировку, как требует закон.",
    "Юлдашта реклама тиҙҙән асыла. Закон талап иткәнсә, намыҫлы билдәләмә әҙерләйбеҙ.",
    "«Скидки по пути» скоро откроются: местные кафе, магазины и мастерские со скидками для попутчиков.",
    "«Юл буйынса ташламалар» тиҙҙән асыла: ерле кафелар, кибеттәр һәм оҫтаханалар юлдаштарға ташлама бирәсәк.",
    "Работа курьером скоро откроется. А пока можно отвезти посылку по пути.",
    "Курьер эше тиҙҙән асыла. Ә әлегә бандеролде юл ыңғайында илтергә була.",
    "Найти попутку", "Юлдаш табыу",
    "Стать курьером заранее", "Алдан курьер булыу",
  ];
  const missing = must.filter((t) => !soon.includes(t));
  check(missing.length === 0, "тексты заглушки совпадают с Android слово в слово", missing.map((t) => t.slice(0, 30)).join("; "));
  check(soon.includes('"/courier-onboarding"') && soon.includes('navigate("/map")'), "кнопки ведут: «Найти попутку» → карта, «Стать курьером заранее» → набор курьеров");
  const css = read("ui.css");
  check(css.includes(".coming-soon__actions") && /\.coming-soon__actions \.btn-primary,\s*\.coming-soon__actions \.btn-soft/.test(css),
    "кнопки заглушки — штатные .btn-primary / .btn-soft (54px, не меньше 48)");
}



// Старые доставки доступны отдельным путём без новой ленты и линии.
{
  const gate = read("components/FeatureGate.tsx");
  check(gate.includes('need === "courier" && status === "authed"') && gate.includes("<CourierHistoryScreen />"), "закрытый курьер сохраняет вошедшему безопасный экран «Везу»");
  const history = read("screens/CourierHistoryScreen.tsx");
  check(history.includes("<CarryOrders") && !history.includes("courierOnline") && !history.includes("fetchCourierAvailable"), "история переиспользует доставку без линии и новой ленты");
  check(read("screens/ProfileScreen.tsx").includes('key: "delivery-history"'), "история доставки доступна из профиля при выключенных направлениях");
}

{
  const gate = read("components/FeatureGate.tsx");
  check(gate.includes('need === "business" && status === "authed"') && gate.includes("<BenefitHistoryScreen"), "закрытый бизнес сохраняет только старые коды и погашение вошедшему");
  const profile = read("screens/ProfileScreen.tsx"), soon = read("components/ComingSoon.tsx");
  check(profile.includes('key: "coupon-history"') && profile.includes('key: "coupon-redeem"'), "старые коды и погашение имеют безопасные входы в профиле");
  check(soon.includes('navigate(isAuthed ? "/coupons" : "/login")') && soon.includes('navigate(isAuthed ? "/partner" : "/login")'), "заглушка ведёт к старому коду и погашению, гостю сначала вход");
}

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
