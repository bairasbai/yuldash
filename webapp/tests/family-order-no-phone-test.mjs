// ================================================================
//  «За близкого» на сайте: телефон близкого не уходит в заявку (17.10.2026).
//
//  Было: сайт дописывал «Телефон близкого: +7…» в комментарий заявки, а заявку со всем
//  комментарием видят все водители в ленте ещё до отклика — номер чужого человека (мамы,
//  ребёнка) уходил незнакомым людям. В Android это давно не так (AccessibilityScreens.kt,
//  FamilyOrderScreen: в комментарии только нейтральная метка).
//
//  Стало как в Android: в комментарии «Заказ за близкого» и заметка самого человека; ни одна
//  цифра номера в запрос не попадает. Исполняем настоящий FamilyOrderScreen (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount } from "./shallow-harness.mjs";

const t = checker();
const L = (lang, ru, ba) => (lang === "ba" ? ba : ru);
const PHONE = "+7 917 123-45-67";

async function openForm(lang) {
  const Screen = await bundleComponent("src/screens/FamilyOrderScreen.tsx", {
    mocks: {
      "react-router-dom": `export const useNavigate=()=>p=>globalThis.__fo.nav.push(p);`,
      "/i18n/lang": langMock(lang),
      "/api/requests": `export const createRequest=async(body)=>{globalThis.__fo.sent.push(body);return{id:1};};`,
      "/api/client": `export class ApiError extends Error{}`,
    },
  });
  globalThis.__fo = { nav: [], sent: [] };
  const h = mount(Screen);
  h.render();
  await h.settle();
  return h;
}

/** Поле ввода по подсказке внутри него. */
const field = (tree, placeholder) => all(tree).filter((n) => n.props?.placeholder === placeholder);

async function fillAndSend(lang, note) {
  const h = await openForm(lang);
  const type = (input, value) => input.props.onChange({ target: { value } });
  type(field(h.tree, L(lang, "Например: Мама, Фаниль", "Мәҫәлән: Әсәй, Фәниль"))[0], "Мама");
  type(field(h.tree, "+7 999 000-00-00")[0], PHONE);
  const cities = field(h.tree, L(lang, "Город или село", "Ҡала йәки ауыл"));
  assert.equal(cities.length, 2, "ждали два поля «Откуда/Куда»");
  type(cities[0], "Уфа");
  type(cities[1], "Сибай");
  if (note) type(field(h.tree, L(lang, "Время, вещи, помощь при посадке…", "Ваҡыт, әйберҙәр, ултырышҡа ярҙам…"))[0], note);
  await h.settle();
  button(h.tree, L(lang, "Оставить заявку", "Заявка ҡалдырырға")).props.onClick();
  await h.settle();
  assert.equal(globalThis.__fo.sent.length, 1, "заявка должна уйти один раз");
  return globalThis.__fo.sent[0];
}

const digitsOf = (s) => s.replace(/\D/g, "");

for (const lang of ["ru", "ba"]) {
  await t.test(`${lang}: номер близкого не попадает в заявку, которую видят все водители`, async () => {
    const body = await fillAndSend(lang, "");
    const wire = JSON.stringify(body);
    assert.ok(!digitsOf(wire).includes(digitsOf(PHONE)), "номер ушёл в заявку: " + wire);
    assert.ok(!wire.includes("917"), "часть номера ушла в заявку: " + wire);
    assert.equal(body.comment, L(lang, "Заказ за близкого", "Яҡын кеше өсөн заказ"));
    assert.equal(body.for_relative_name, "Мама");
    assert.equal(body.assisted, true);
  });

  await t.test(`${lang}: заметка человека сохраняется после нейтральной метки`, async () => {
    const body = await fillAndSend(lang, "Нужна помощь с сумкой");
    assert.equal(body.comment, L(lang, "Заказ за близкого", "Яҡын кеше өсөн заказ") + ". Нужна помощь с сумкой");
    assert.ok(!digitsOf(JSON.stringify(body)).includes(digitsOf(PHONE)));
  });
}

t.done();
