import assert from "node:assert/strict";
import { all, button, bundleComponent, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const t = checker();
globalThis.window ??= { setTimeout, clearTimeout };
const towns = [
  { id: 1, name_ru: "Берёзовка", name_ba: "Ҡайынлыҡ", district: "Иглинский р-н", kind: "village", region: "РБ" },
  { id: 2, name_ru: "Берёзовка", name_ba: "Ҡайынлыҡ", district: "Уфимский р-н", kind: "village", region: "РБ" },
  { id: 3, name_ru: "Акъяр", name_ba: "Аҡъяр", district: "Хайбуллинский р-н", kind: "district_center", region: "РБ" },
  { id: 4, name_ru: "Темясово", name_ba: "Темәс", district: "Баймакский р-н", kind: "village", region: "РБ" },
  { id: 5, name_ru: "Зилаир", name_ba: "Йылайыр", district: "Зилаирский р-н", kind: "district_center", region: "РБ" },
  { id: 6, name_ru: "Исянгулово", name_ba: "Иҫәнғол", district: "Зианчуринский р-н", kind: "district_center", region: "РБ" },
  { id: 7, name_ru: "Абзаково", name_ba: "Абҙаҡ", district: "Белорецкий р-н", kind: "village", region: "РБ" },
  { id: 8, name_ru: "Абзаково", name_ba: "Абҙаҡ", district: "Учалинский р-н", kind: "village", region: "РБ" },
];
const stop = "Берёзовка (Иглинский р-н)";
const fixture = globalThis.__createRideStops = { queries: [], published: [], owner: "A", hydrated: false, cleared: 0 };
function fresh() {
  Object.assign(fixture, { queries: [], published: [], owner: "A", hydrated: false, cleared: 0, reject: false,
    draft: { from: "Уфа", to: "Сибай", when: "2030-01-02T10:00", waypoints: [stop] } });
  fixture.search = async (q) => {
    fixture.queries.push(q);
    return towns.filter((town) => [town.name_ru, town.name_ba].some((name) => name.toLowerCase().startsWith(q.toLowerCase())));
  };
}
const geoMock = "export const searchSettlements=(...args)=>globalThis.__createRideStops.search(...args);";
const clientMock = `export class ApiError extends Error{};globalThis.__stopApiError=ApiError;export const getSessionGeneration=()=>globalThis.__createRideStops.owner;`;
const resolve = await bundleComponent("src/components/StopSuggestField.tsx", { exportName: "resolveRideStops", mocks: { "/api/geo": geoMock } });

await t.test("название и район берутся из справочника; черновик не меняется", async () => {
  fresh(); const values = [stop, "Акъяр"];
  assert.deepEqual(await resolve(values, "Уфа", "Сибай", false), [stop, "Акъяр (Хайбуллинский р-н)"]);
  assert.deepEqual(values, [stop, "Акъяр"]);
  assert.deepEqual(await resolve(["Ҡайынлыҡ (Иглинский р-н)"], "Өфө", "Сибай", true), ["Ҡайынлыҡ (Иглинский р-н)"]);
});
await t.test("телефон, адрес с номером и длинный ввод не уходят даже в поиск", async () => {
  for (const value of ["+7 900 111-22-33", "ул. Ленина 5", "123", "Д".repeat(61), "дом_у_реки"]) {
    fresh(); await assert.rejects(() => resolve([value], "Уфа", "Сибай", false), (e) => e.problem === "invalid");
    assert.deepEqual(fixture.queries, []);
  }
});
await t.test("неизвестное имя и тёзки без района требуют выбора", async () => {
  fresh();
  for (const value of ["Вымышленное", "Берёзовка"]) await assert.rejects(() => resolve([value], "Уфа", "Сибай", false), (e) => e.problem === "invalid");
});
await t.test("начало и конец исключаются на обоих языках и с приставкой села", async () => {
  fresh();
  await assert.rejects(() => resolve(["Акъяр"], "с. Аҡъяр", "Сибай", false), (e) => e.problem === "endpoint");
  await assert.rejects(() => resolve(["Аҡъяр"], "Уфа", "Акъяр", true), (e) => e.problem === "endpoint");
});
await t.test("одну остановку нельзя повторить под русским и башкирским именем", async () => {
  fresh(); await assert.rejects(() => resolve([stop, "Ҡайынлыҡ (Иглинский р-н)"], "Уфа", "Сибай", false), (e) => e.problem === "duplicate");
});
await t.test("четыре остановки допустимы, пятая не отбрасывается молча", async () => {
  fresh(); const four = ["Акъяр", "Темясово", "Зилаир", "Исянгулово"];
  assert.equal((await resolve(four, "Уфа", "Сибай", false)).length, 4);
  fixture.queries = [];
  await assert.rejects(() => resolve([...four, stop], "Уфа", "Сибай", false), (e) => e.problem === "tooMany");
  assert.deepEqual(fixture.queries, []);
});
await t.test("два Абзаково разных районов не дубли", async () => {
  fresh(); const values = ["Абзаково (Белорецкий р-н)", "Абзаково (Учалинский р-н)"];
  assert.deepEqual(await resolve(values, "Уфа", "Сибай", false), values);
});
await t.test("тёзка начала в другом районе допустима, та же запись RU/BA — начало", async () => {
  fresh();
  assert.deepEqual(await resolve(["Абзаково (Учалинский р-н)"], "с. Абҙаҡ (Белорецкий р-н)", "Уфа", false), ["Абзаково (Учалинский р-н)"]);
  await assert.rejects(() => resolve(["Абҙаҡ (Белорецкий р-н)"], "Абзаково (Белорецкий р-н)", "Уфа", true), (e) => e.problem === "endpoint");
});
await t.test("неуточнённый конец с тёзками не разрешает угадать район", async () => {
  fresh(); await assert.rejects(() => resolve(["Абзаково (Учалинский р-н)"], "Уфа", "Абзаково", false), (e) => e.problem === "endpoint");
});
await t.test("русское и башкирское имя той же записи остаются дублем", async () => {
  fresh(); await assert.rejects(() => resolve(["Абзаково (Белорецкий р-н)", "Абҙаҡ (Белорецкий р-н)"], "Уфа", "Сибай", false), (e) => e.problem === "duplicate");
});

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => lang === "ba" ? ba : ru;
  const Field = await bundleComponent("src/components/StopSuggestField.tsx", {
    mocks: { "/api/geo": geoMock, "/api/client": clientMock, "/i18n/lang": langMock(lang) },
  });
  await t.test(`${lang}: справочная подсказка различает районы; выбор записывает район`, async () => {
    fresh();
    const h = mount(Field, { value: "", endpoints: ["Уфа", "Сибай"], onChange(value) { h.props = { ...h.props, value }; } });
    try {
      h.render(); ofType(h.tree, "input")[0].props.onChange({ target: { value: L("Бер", "Ҡай") } });
      await h.settle(); await new Promise((r) => setTimeout(r, 280)); await h.settle();
      assert.ok(button(h.tree, L("Берёзовка (Уфимский р-н)", "Ҡайынлыҡ (Уфимский р-н)")));
      const picked = L(stop, "Ҡайынлыҡ (Иглинский р-н)");
      const row = button(h.tree, picked);
      assert.ok(row); assert.equal(row.props.className, "city-field__item");
      row.props.onClick(); await h.settle();
      assert.equal(ofType(h.tree, "input")[0].props.value, picked);
      assert.equal(ofType(h.tree, "ul").length, 0);
    } finally { h.unmount(); }
  });
  await t.test(`${lang}: разметка списка для диктора — listbox владеет только option, <li> между ними прозрачны`, async () => {
    fresh();
    const h = mount(Field, { value: "", endpoints: ["Уфа", "Сибай"], onChange(value) { h.props = { ...h.props, value }; } });
    try {
      h.render(); ofType(h.tree, "input")[0].props.onChange({ target: { value: L("Бер", "Ҡай") } });
      await h.settle(); await new Promise((r) => setTimeout(r, 280)); await h.settle();
      const lists = ofType(h.tree, "ul"); assert.equal(lists.length, 1);
      assert.equal(lists[0].props.role, "listbox"); assert.ok(lists[0].props["aria-label"], "у списка есть название");
      const rows = [lists[0].props.children].flat(Infinity).filter(Boolean);
      assert.ok(rows.length >= 2, "в списке несколько вариантов");
      for (const row of rows) {
        assert.equal(row.type, "li"); assert.equal(row.props.role, "presentation", "<li> без роли станет listitem внутри listbox — это ошибка разметки");
        const options = ofType(row, "button"); assert.equal(options.length, 1);
        assert.equal(options[0].props.role, "option"); assert.equal(typeof options[0].props["aria-selected"], "boolean");
      }
      assert.equal(ofType(lists[0], "button").length, rows.length, "кнопок-вариантов ровно столько же, сколько строк");
    } finally { h.unmount(); }
  });
  await t.test(`${lang}: ошибка подсказок сохраняет ввод, повтор восстанавливает список`, async () => {
    fresh(); const search = fixture.search;
    fixture.search = async () => { throw new globalThis.__stopApiError("Справочник временно закрыт"); };
    const h = mount(Field, { value: "Берёзовка", endpoints: [], onChange() {} });
    try {
      h.render(); ofType(h.tree, "input")[0].props.onFocus();
      await h.settle(); await new Promise((r) => setTimeout(r, 280)); await h.settle();
      assert.ok(text(h.tree).includes("Справочник временно закрыт"));
      assert.equal(ofType(h.tree, "input")[0].props.value, "Берёзовка");
      fixture.search = search;
      button(h.tree, L("Повторить", "Ҡабатлау")).props.onClick();
      await h.settle(); await new Promise((r) => setTimeout(r, 280)); await h.settle();
      assert.equal(ofType(h.tree, "ul").length, 1);
    } finally { h.unmount(); }
  });

  const Form = await bundleComponent("src/screens/CreateRideScreen.tsx", {
    real: ["/StopSuggestField", "/seatOptions"], mocks: {
      "/api/geo": geoMock, "/api/client": clientMock, "/i18n/lang": langMock(lang),
      "/features": "export const useFeatures=()=>({parcels:true});export const getFeatures=useFeatures;",
      "/WeatherWarningCard": "export default 'Weather';export const useRouteWeather=()=>null;",
      "/api/driver": `export const fetchPriceHint=async()=>({});export const publishRide=async body=>{const f=globalThis.__createRideStops;f.published.push(body);if(f.reject)throw new globalThis.__stopApiError('Остановка временно недоступна');return{id:42};};`,
      "/utils/formDraft": `export const useDraftSync=(key,value,apply)=>{const f=globalThis.__createRideStops;if(!f.hydrated){f.hydrated=true;apply(f.draft);}};export const clearDraft=()=>globalThis.__createRideStops.cleared++;`,
    },
  });
  const stops = (tree) => all(tree).filter((node) => typeof node.type === "function" && node.type.name === "StopSuggestField");
  const publish = (tree) => button(tree, L("Опубликовать", "Баҫтырырға"));
  await t.test(`${lang}: отказ сервера показан дословно; остановка и черновик остаются, повтор успешен`, async () => {
    fresh(); fixture.reject = true;
    const h = mount(Form);
    try {
      h.render(); await h.settle(); await publish(h.tree).props.onClick(); await h.settle();
      assert.ok(text(h.tree).includes("Остановка временно недоступна"));
      assert.equal(stops(h.tree)[0].props.value, stop);
      assert.equal(fixture.cleared, 0);
      fixture.reject = false;
      await publish(h.tree).props.onClick(); await h.settle();
      assert.equal(fixture.published.length, 2);
      const expected = L(stop, "Ҡайынлыҡ (Иглинский р-н)");
      fixture.published.forEach((body) => assert.equal(body.waypoints, expected));
      assert.equal(fixture.cleared, 1);
    } finally { h.unmount(); }
  });
  await t.test(`${lang}: старый черновик с адресом не публикуется и не стирается`, async () => {
    fresh(); const raw = "ул. Ленина 5, +7 900 111-22-33"; fixture.draft.waypoints = [raw];
    const h = mount(Form);
    try {
      h.render(); await h.settle(); await publish(h.tree).props.onClick(); await h.settle();
      assert.equal(stops(h.tree)[0].props.value, raw);
      assert.deepEqual(fixture.published, []); assert.deepEqual(fixture.queries, []); assert.equal(fixture.cleared, 0);
    } finally { h.unmount(); }
  });
  await t.test(`${lang}: повторный быстрый тап и новый вход во время проверки не публикуют лишнего`, async () => {
    fresh(); let release; const search = fixture.search;
    fixture.search = (q) => new Promise((resolve) => { release = async () => resolve(await search(q)); });
    const h = mount(Form);
    try {
      h.render(); await h.settle(); const submit = publish(h.tree).props.onClick;
      const first = submit(); const second = submit();
      fixture.owner = "B"; await release(); await Promise.all([first, second]); await h.settle();
      assert.deepEqual(fixture.published, []); assert.equal(fixture.queries.length, 1);
      assert.equal(stops(h.tree)[0].props.value, stop); assert.equal(fixture.cleared, 0);
    } finally { h.unmount(); }
  });
}
t.done();
