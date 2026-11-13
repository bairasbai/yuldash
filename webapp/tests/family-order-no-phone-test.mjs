// ================================================================
//  «За близкого» на сайте: поля «телефон близкого» нет, номер в заявку не уходит (17.10.2026).
//
//  Было: сайт дописывал «Телефон близкого: +7…» в комментарий заявки, а заявку со всем
//  комментарием видят все водители в ленте ещё до отклика — номер чужого человека (мамы,
//  ребёнка) уходил незнакомым людям. PR #168 убрал отправку, но поле осталось: человек вводил
//  номер и ждал, что водитель позвонит маме, а номер никуда не шёл — нечестный экран.
//
//  Решение Александра (17.10.2026, Q12 = «А»): поле убрано и на сайте, и в Android
//  (AccessibilityScreens.kt, FamilyOrderFormContent). Этот набор держит это:
//   1) на экране нет ни одного поля ввода телефона (ни по типу, ни по подсказке, ни по подписи);
//   2) остались ровно имя, «Откуда», «Куда» и заметка;
//   3) в запросе — нейтральная метка и имя, ни одной цифры и никакого ключа с «phone».
//  Исполняем настоящий FamilyOrderScreen (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const t = checker();
const L = (lang, ru, ba) => (lang === "ba" ? ba : ru);

async function openForm(lang) {
  const Screen = await bundleComponent("src/screens/FamilyOrderScreen.tsx", {
    mocks: {
      "react-router-dom": `export const useNavigate=()=>p=>globalThis.__fo.nav.push(p);`,
      "/i18n/lang": langMock(lang),
      "/api/requests": `export const createRequestForAnother=async(body)=>{globalThis.__fo.sent.push(body);return globalThis.__fo.reply;};`,
      "/api/client": `export class ApiError extends Error{}; export const getSessionGeneration=()=>globalThis.__fo.generation;`,
    },
  });
  globalThis.__fo = { nav: [], sent: [], reply: { id: 37 }, generation: "first" };
  const h = mount(Screen);
  h.render();
  await h.settle();
  return h;
}

/** Поле ввода по подсказке внутри него. */
const field = (tree, placeholder) => all(tree).filter((n) => n.props?.placeholder === placeholder);

async function fillForm(h, lang, note) {
  const type = (input, value) => input.props.onChange({ target: { value } });
  type(field(h.tree, L(lang, "Например: Мама, Фаниль", "Мәҫәлән: Әсәй, Фәниль"))[0], "Мама");
  const cities = field(h.tree, L(lang, "Город или село", "Ҡала йәки ауыл"));
  assert.equal(cities.length, 2, "ждали два поля «Откуда/Куда»");
  type(cities[0], "Уфа");
  type(cities[1], "Сибай");
  if (note) type(field(h.tree, L(lang, "Время, вещи, помощь при посадке…", "Ваҡыт, әйберҙәр, ултырышҡа ярҙам…"))[0], note);
  await h.settle();
  return button(h.tree, L(lang, "Оставить заявку", "Заявка ҡалдырырға")).props.onClick;
}

async function fillAndSend(lang, note) {
  const h = await openForm(lang);
  const submit = await fillForm(h, lang, note);
  submit();
  await h.settle();
  assert.equal(globalThis.__fo.sent.length, 1, "заявка должна уйти один раз");
  return h;
}

for (const lang of ["ru", "ba"]) {
  await t.test(`${lang}: на экране нет поля телефона`, async () => {
    const h = await openForm(lang);
    const inputs = [...ofType(h.tree, "input"), ...ofType(h.tree, "textarea")];
    const phoneLike = inputs.filter(
      (n) =>
        n.props?.type === "tel" ||
        n.props?.inputMode === "tel" ||
        /^\+?7?[\s\d()-]{6,}$/.test(String(n.props?.placeholder ?? "")) ||
        /tel|phone/i.test(String(n.props?.autoComplete ?? "") + String(n.props?.name ?? "") + String(n.props?.id ?? ""))
    );
    assert.equal(phoneLike.length, 0, "осталось поле телефона: " + JSON.stringify(phoneLike.map((n) => n.props)));
    assert.ok(!/телефон/i.test(text(h.tree)), "в подписях экрана осталось слово «телефон»: " + text(h.tree));
  });

  await t.test(`${lang}: остались ровно имя, «Откуда», «Куда» и заметка`, async () => {
    const h = await openForm(lang);
    assert.equal(ofType(h.tree, "input").length, 3, "ждали три строки ввода: имя, откуда, куда");
    assert.equal(ofType(h.tree, "textarea").length, 1, "ждали одно поле заметки");
  });

  await t.test(`${lang}: в заявку уходят только метка и имя — ни цифры, ни ключа phone`, async () => {
    await fillAndSend(lang, "");
    const [body] = globalThis.__fo.sent;
    const wire = JSON.stringify(body);
    assert.ok(!/\d/.test(wire), "в запросе есть цифра: " + wire);
    assert.ok(!/phone|tel/i.test(Object.keys(body).join(",")), "в запросе есть ключ телефона: " + wire);
    assert.equal(body.comment, L(lang, "Заказ за близкого", "Яҡын кеше өсөн заказ"));
    assert.equal(body.for_relative_name, "Мама");
    assert.equal(body.assisted, true);
  });

  await t.test(`${lang}: заметка человека сохраняется после нейтральной метки`, async () => {
    await fillAndSend(lang, "Нужна помощь с сумкой");
    const [body] = globalThis.__fo.sent;
    assert.equal(body.comment, L(lang, "Заказ за близкого", "Яҡын кеше өсөн заказ") + ". Нужна помощь с сумкой");
    assert.ok(!/\d/.test(JSON.stringify(body)), "в запросе есть цифра: " + JSON.stringify(body));
  });

  await t.test(`${lang}: переходы к откликам созданной заявки и списку`, async () => {
    const h = await fillAndSend(lang, "");
    button(h.tree, L(lang, "Смотреть отклики", "Яуаптарҙы ҡарарға")).props.onClick();
    button(h.tree, L(lang, "Мои заявки", "Минең заявкалар")).props.onClick();
    assert.deepEqual(globalThis.__fo.nav, ["/requests/37/responses", "/my-requests"]);
  });
}

await t.test("повторное нажатие не отправляет ещё одну заявку", async () => {
  const h = await openForm("ru");
  const submit = await fillForm(h, "ru");
  const first = submit();
  await submit();
  await first;
  await h.settle();
  await submit();
  assert.equal(globalThis.__fo.sent.length, 1);
});

await t.test("ответ прежнего аккаунта не открывает его отклики и не оставляет черновик", async () => {
  const h = await openForm("ru");
  const submit = await fillForm(h, "ru");
  let resolve;
  globalThis.__fo.reply = new Promise((done) => { resolve = done; });
  const sent = submit();
  globalThis.__fo.generation = "second";
  resolve({ id: 37 });
  await sent;
  h.render();
  assert.equal(h.tree, null);
  await submit();
  assert.equal(globalThis.__fo.sent.length, 1);
  assert.deepEqual(globalThis.__fo.nav, []);
});

await t.test("уход со страницы отзывает незавершённое действие", async () => {
  const h = await openForm("ru");
  const submit = await fillForm(h, "ru");
  let resolve;
  globalThis.__fo.reply = new Promise((done) => { resolve = done; });
  const sent = submit();
  h.unmount();
  resolve({ id: 37 });
  await sent;
  await submit();
  assert.equal(globalThis.__fo.sent.length, 1);
  assert.deepEqual(globalThis.__fo.nav, []);
});

await t.test("кнопки завершённой заявки не открывают данные после смены аккаунта", async () => {
  const h = await fillAndSend("ru", "");
  const responses = button(h.tree, "Смотреть отклики").props.onClick;
  const list = button(h.tree, "Мои заявки").props.onClick;
  globalThis.__fo.generation = "second";
  responses();
  list();
  assert.deepEqual(globalThis.__fo.nav, []);
});

t.done();
