// ================================================================
//  Оценка пассажира в кабинете водителя — в ДВА шага, как в Android (17.10.2026).
//
//  Было на сайте: первое касание звезды сразу уходило на сервер. Промахнулся пальцем по первой
//  звезде — человеку упала единица, и вернуть было нечем; подпись читалке «1 звёзд».
//
//  Стало как ProfileScreen.kt (ratableBookings):
//   • выбрал звёзды → подпись «Выбрано 4 звезды — подтверди» → «Отправить оценку»;
//   • уже оценил → «Твоя оценка: 4 звезды» + «Изменить»; при изменении — «Отмена»;
//   • не ушло — звёзды возвращаются к тому, что на сервере, текст ошибки виден.
//
//  Исполняем настоящий DriverPassengers; сеть и язык подменены (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { ROOT, all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const fresh = { booking_id: 7, passenger_id: 17, passenger_name: "Зухра", passenger_rating: null, route: "Сибай → Уфа", status: "done", my_stars: 0 };
const rated = { booking_id: 8, passenger_id: 18, passenger_name: "Ильдар", passenger_rating: 4.8, route: "Уфа → Белебей", status: "done", my_stars: 4 };

async function open(lang, rows = [{ ...fresh }, { ...rated }]) {
  const DriverPassengers = await bundleComponent("src/components/DriverPassengers.tsx", {
    real: ["/utils/format"],
    mocks: {
      "/i18n/lang": langMock(lang),
      // ApiError — из той же заглушки клиента, что видит экран: класс из сборки другого языка
      // экран не узнал бы (instanceof), и вместо текста сервера показал бы запасную фразу.
      "/api/bookings": `import { ApiError } from "../api/client";
        export const fetchDriverBookings=async()=>{globalThis.__loads=(globalThis.__loads??0)+1;return globalThis.__dpRows.map(r=>({...r}));};
        export const markNoShow=async()=>({});
        export const rateBooking=async(id,n)=>{globalThis.__rated.push([id,n]);if(globalThis.__rateHold)await globalThis.__rateHold;
          const f=globalThis.__rateFail;if(f)throw new ApiError(f.status??500,f.message??"Сервер устал");
          const r=globalThis.__dpRows.find(x=>x.booking_id===id);if(r)r.my_stars=n;return {};};`,
      "/api/client": `export class ApiError extends Error{constructor(s,m){super(m);this.status=s;}}`,
    },
  });
  globalThis.__dpRows = rows;
  globalThis.__rated = [];
  globalThis.__rateFail = false;
  const h = mount(DriverPassengers, { onOpenTrip() {} });
  h.render();
  await h.settle();
  return h;
}

const row = (h, id) => all(h.tree).find((n) => n.key === id && String(n.props.className ?? "").includes("list-row"));
const stars = (node) => all(node).filter((n) => n.type === "button" && String(n.props.className ?? "").includes("rate-star"));
const lit = (node) => stars(node).filter((n) => n.props.className.includes("is-on")).length;
const caption = (node) => all(node).find((n) => n.props?.["aria-live"] === "polite");

const t = checker();

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);

  await t.test(`${lang}: касание звезды ничего не отправляет — сначала выбор, потом «Отправить оценку»`, async () => {
    const h = await open(lang);
    assert.equal(caption(row(h, 7)) && text(caption(row(h, 7))), L("Выбери оценку", "Баһа һайла"));
    assert.equal(button(row(h, 7), L("Отправить оценку", "Баһаны ебәреү")), undefined, "пока ничего не выбрано — кнопки нет");
    stars(row(h, 7))[0].props.onClick();
    await h.settle();
    assert.equal(globalThis.__rated.length, 0, "промах по первой звезде не должен сразу ставить единицу");
    assert.equal(lit(row(h, 7)), 1);
    assert.equal(text(caption(row(h, 7))), L("Выбрано 1 звезда — подтверди", "1 йондоҙ һайланды — раҫла"));
    stars(row(h, 7))[4].props.onClick();
    await h.settle();
    assert.equal(lit(row(h, 7)), 5, "передумал — выбрал пять");
    assert.equal(button(row(h, 7), L("Отмена", "Кире алыу")), undefined, "отменять нечего — оценки ещё не было");
    button(row(h, 7), L("Отправить оценку", "Баһаны ебәреү")).props.onClick();
    await h.settle();
    assert.deepEqual(globalThis.__rated, [[7, 5]], "ушла ровно одна оценка — выбранная");
    assert.equal(text(caption(row(h, 7))), L("Твоя оценка: 5 звёзд", "Һинең баһаң: 5 йондоҙ"));
    assert.ok(text(h.tree).includes(L("Спасибо за оценку", "Баһа өсөн рәхмәт")));
    assert.ok(stars(row(h, 7)).every((s) => s.props.disabled), "после отправки звёзды только показывают оценку");
  });

  await t.test(`${lang}: уже оценил — «Твоя оценка» и «Изменить»; «Отмена» возвращает как было`, async () => {
    const h = await open(lang);
    assert.equal(text(caption(row(h, 8))), L("Твоя оценка: 4 звезды", "Һинең баһаң: 4 йондоҙ"));
    assert.equal(lit(row(h, 8)), 4);
    assert.ok(stars(row(h, 8)).every((s) => s.props.disabled), "без «Изменить» звёзды не нажимаются");
    button(row(h, 8), L("Изменить", "Үҙгәртеү")).props.onClick();
    await h.settle();
    assert.equal(text(caption(row(h, 8))), L("Выбрано 4 звезды — подтверди", "4 йондоҙ һайланды — раҫла"), "начинаем с поставленной");
    stars(row(h, 8))[1].props.onClick();
    await h.settle();
    assert.equal(lit(row(h, 8)), 2);
    button(row(h, 8), L("Отмена", "Кире алыу")).props.onClick();
    await h.settle();
    assert.equal(lit(row(h, 8)), 4, "«Отмена» вернула поставленную оценку");
    assert.equal(text(caption(row(h, 8))), L("Твоя оценка: 4 звезды", "Һинең баһаң: 4 йондоҙ"));
    assert.equal(globalThis.__rated.length, 0, "ничего не ушло");
  });

  await t.test(`${lang}: не ушло — звёзды возвращаются к тому, что на сервере, и видна причина`, async () => {
    const h = await open(lang);
    button(row(h, 8), L("Изменить", "Үҙгәртеү")).props.onClick();
    await h.settle();
    stars(row(h, 8))[0].props.onClick();
    await h.settle();
    globalThis.__rateFail = { status: 500, message: "Сервер устал" };
    button(row(h, 8), L("Отправить оценку", "Баһаны ебәреү")).props.onClick();
    await h.settle();
    assert.equal(globalThis.__rated.length, 1);
    assert.equal(lit(row(h, 8)), 4, "на экране не должна остаться единица, которой нет на сервере");
    assert.ok(text(h.tree).includes("Сервер устал"), "человек видит, что оценка не ушла");
    assert.ok(button(row(h, 8), L("Отправить оценку", "Баһаны ебәреү")), "можно повторить");
  });

  await t.test(`${lang}: подпись звезды для читалки — с правильным числом`, async () => {
    const h = await open(lang);
    const labels = stars(row(h, 7)).map((s) => s.props["aria-label"]);
    assert.deepEqual(
      labels,
      lang === "ba"
        ? ["1 йондоҙ", "2 йондоҙ", "3 йондоҙ", "4 йондоҙ", "5 йондоҙ"]
        : ["1 звезда", "2 звезды", "3 звезды", "4 звезды", "5 звёзд"]
    );
  });
}

await t.test("пока отправляется — повторное нажатие не шлёт вторую оценку", async () => {
  const h = await open("ru");
  stars(row(h, 7))[2].props.onClick();
  await h.settle();
  button(row(h, 7), "Отправить оценку").props.onClick();
  h.render(); // React перерисовывает между двумя нажатиями
  const again = button(row(h, 7), "Отправляем…");
  assert.ok(again, "пока идёт отправка — «Отправляем…»");
  assert.equal(again.props.disabled, true);
  again.props.onClick(); // даже если нажатие всё же дошло
  await h.settle();
  assert.deepEqual(globalThis.__rated, [[7, 3]]);
});

await t.test("во время отправки звёзды и «Отмена» заперты, «Изменить» не виден; aria-pressed — только при правке", async () => {
  const h = await open("ru");
  const group = all(row(h, 8)).find((n) => n.props?.role === "group");
  assert.equal(group.props["aria-label"], "Оценка пассажира");
  assert.ok(stars(row(h, 8)).every((s) => s.props["aria-pressed"] === undefined), "показанная оценка — не кнопки-переключатели");
  button(row(h, 8), "Изменить").props.onClick();
  await h.settle();
  assert.equal(button(row(h, 8), "Изменить"), undefined, "во время правки «Изменить» не нужен");
  stars(row(h, 8))[2].props.onClick();
  await h.settle();
  assert.deepEqual(stars(row(h, 8)).map((s) => s.props["aria-pressed"]), [false, false, true, false, false]);
  let release;
  globalThis.__rateHold = new Promise((r) => (release = r));
  button(row(h, 8), "Отправить оценку").props.onClick();
  h.render();
  assert.ok(stars(row(h, 8)).every((s) => s.props.disabled), "пока уходит — звёзды не меняются");
  assert.equal(button(row(h, 8), "Отмена").props.disabled, true);
  release();
  globalThis.__rateHold = null;
  await h.settle();
});

await t.test("оценка соседней карточки не запирает эту — у каждой своя блокировка", async () => {
  const h = await open("ru");
  stars(row(h, 7))[3].props.onClick();
  button(row(h, 8), "Изменить").props.onClick();
  await h.settle();
  stars(row(h, 8))[1].props.onClick();
  await h.settle();
  let release;
  globalThis.__rateHold = new Promise((r) => (release = r));
  button(row(h, 7), "Отправить оценку").props.onClick();
  h.render();
  const other = button(row(h, 8), "Отправить оценку");
  assert.equal(other.props.disabled, false, "соседняя «Отправить оценку» не должна молча не работать");
  other.props.onClick();
  release();
  globalThis.__rateHold = null;
  await h.settle();
  assert.deepEqual(globalThis.__rated.map((r) => r[0]).sort(), [7, 8]);
});

await t.test("«Спасибо» и причина отказа — у самой карточки; 409 перечитывает список; без текста — запасная фраза", async () => {
  const h = await open("ru");
  stars(row(h, 7))[4].props.onClick();
  await h.settle();
  button(row(h, 7), "Отправить оценку").props.onClick();
  await h.settle();
  assert.ok(text(row(h, 7)).includes("Спасибо за оценку"), "«Спасибо» видно у карточки, а не за краем экрана");
  const loads = globalThis.__loads;
  globalThis.__rateFail = { status: 409, message: "Эту поездку оценить уже нельзя" };
  button(row(h, 8), "Изменить").props.onClick();
  await h.settle();
  stars(row(h, 8))[0].props.onClick();
  await h.settle();
  button(row(h, 8), "Отправить оценку").props.onClick();
  await h.settle();
  assert.ok(text(row(h, 8)).includes("Эту поездку оценить уже нельзя"));
  assert.ok(!text(row(h, 7)).includes("Спасибо за оценку"), "старое «Спасибо» не висит у соседней карточки");
  assert.ok(globalThis.__loads > loads, "409 — список перечитан");
  globalThis.__rateFail = { status: 500, message: "" };
  stars(row(h, 8))[0].props.onClick();
  await h.settle();
  button(row(h, 8), "Отправить оценку").props.onClick();
  await h.settle();
  assert.ok(text(row(h, 8)).includes("Не получилось оценить"));
  globalThis.__rateFail = null;
});

await t.test("вёрстка: имя своей строкой во всю ширину, кнопки без отступа сверху, «Изменить» 48", () => {
  const css = readFileSync(ROOT + "src/ui.css", "utf8");
  const block = (sel) => css.slice(css.indexOf(sel)).split("}")[0];
  assert.match(block(".list-row--stack > .list-row__main"), /flex-basis:\s*100%/, "имя сжималось до «Зу…» рядом со звёздами");
  assert.match(block(".list-row--stack > .list-row__main"), /\.list-row--stack > \.rate-row/);
  assert.match(block(".rate-confirm .btn-primary"), /margin-top:\s*0/, "у .btn-primary свой отступ сверху — кнопки стояли криво");
  assert.match(block(".rate-row .rate-edit"), /min-height:\s*48px/);
  assert.match(block(".rate-stars--sm .rate-star {"), /color:\s*var\(--muted\)/, "пустая звезда почти не видна на --border");
  assert.match(block(".rate-stars--sm .rate-star.is-on {"), /color:\s*var\(--canon-star\)/, "горящая звезда стала такой же серой, как пустая");
  assert.ok(css.indexOf(".rate-stars--sm .rate-star.is-on {") > css.indexOf(".rate-stars--sm .rate-star {"), "горящая — ниже по каскаду, чем пустая");
  assert.match(css, /\.rate-star:not\(:disabled\):hover/, "наведение не зажигает звезду, которую нельзя нажать");
});

await t.test("надписи совпадают с Android слово в слово", () => {
  const kt = ROOT + "../android/app/src/main/java/com/yuldash/app/ProfileScreen.kt";
  if (!existsSync(kt)) return console.log("    (Android рядом нет — сверка пропущена)");
  const src = readFileSync(kt, "utf8");
  for (const s of [
    'appText("Выбери оценку", "Баһа һайла")',
    'appText("Изменить", "Үҙгәртеү")',
    'appText("Отправить оценку", "Баһаны ебәреү")',
    'appText("Отправляем…", "Ебәрәбеҙ…")',
    'appText("Отмена", "Кире алыу")',
    '"Твоя оценка: ${starsText(stars)}", "Һинең баһаң: ${starsText(stars)}"',
    '"Выбрано ${starsText(stars)} — подтверди", "${starsText(stars)} һайланды — раҫла"',
    'appText("Спасибо за оценку", "Баһа өсөн рәхмәт")',
  ]) assert.ok(src.includes(s), "в Android нет: " + s);
});

t.done();
