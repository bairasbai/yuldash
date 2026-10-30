// Независимый текстовый блок RS-07А. Запуск: node webapp/tests/booking-terms-test.mjs
// Не принимает сервер, отмену, pay_method в HTTP или интеграцию с RideSheet.
// Общий run.mjs занят PR #212; после очереди этот набор надо зарегистрировать там.
import assert from "node:assert/strict";
import { all, button, bundleComponent, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const previousWindow = globalThis.window;
const previousFetch = globalThis.fetch;
const storageDescriptor = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
let reduce = true;
const queries = [];
globalThis.window = {
  matchMedia(query) {
    assert.equal(query, "(prefers-reduced-motion: reduce)");
    const listeners = new Set();
    const media = {
      get matches() { return reduce; },
      addEventListener(type, handler) { assert.equal(type, "change"); listeners.add(handler); },
      removeEventListener(type, handler) { assert.equal(type, "change"); listeners.delete(handler); },
      notify: () => listeners.forEach((fn) => fn()),
      get listenerCount() { return listeners.size; },
    };
    queries.push(media);
    return media;
  },
};
globalThis.fetch = () => assert.fail("информационный блок не должен отправлять запросы");
Object.defineProperty(globalThis, "localStorage", { configurable: true, get() { assert.fail("информационный блок не должен читать личное хранилище"); } });

for (const lang of ["ru", "ba"]) {
  const Component = await bundleComponent("src/components/BookingTerms.tsx", { mocks: { "/i18n/lang": langMock(lang) } });
  const details = lang === "ru" ? "Подробнее" : "Тулыраҡ";
  const collapse = lang === "ru" ? "Свернуть" : "Йыйырға";
  const open = async (policy) => {
    const h = mount(Component, { policy });
    h.render(); await h.settle();
    return h;
  };

  await t.test(`${lang}: без ответа сервера условия видны сразу, без выдуманных чисел`, async () => {
    const h = await open(undefined);
    const visible = text(h.tree);
    assert.ok(visible.includes(lang === "ru" ? "Как платить" : "Нисек түләргә"));
    assert.ok(visible.includes(lang === "ru" ? "Через Юлдаш деньги не идут" : "Аҡса Юлдаш аша үтмәй"));
    assert.ok(visible.includes(lang === "ru" ? "Незадолго до выезда" : "Ҡуҙғалыр алдынан"));
    assert.ok(!/\d/.test(visible));
    assert.equal(button(h.tree, details).props["aria-expanded"], false);
    assert.equal(all(h.tree).filter((node) => node.type === "button").length, 1);
    h.unmount();
  });

  await t.test(`${lang}: оба порога берутся из переданной политики и обновляются в открытом блоке`, async () => {
    const h = await open({ late_cancel_before_min: 91, no_show_wait_min: 27 });
    assert.ok(text(h.tree).includes("91"));
    assert.ok(!text(h.tree).includes("27"), "подробности пока скрыты");
    button(h.tree, details).props.onClick(); await h.settle();
    assert.ok(text(h.tree).includes("27"));
    h.props.policy = { late_cancel_before_min: 43, no_show_wait_min: 18 };
    h.render(); await h.settle();
    const visible = text(h.tree);
    assert.ok(visible.includes("43") && visible.includes("18"));
    assert.ok(!visible.includes("91") && !visible.includes("27"));
    assert.equal(button(h.tree, collapse).props["aria-expanded"], true);
    h.unmount();
  });

  await t.test(`${lang}: неверные, отсутствующие и потерянные пороги дают запасной текст`, async () => {
    const h = await open({ late_cancel_before_min: 91, no_show_wait_min: 27 });
    button(h.tree, details).props.onClick(); await h.settle();
    for (const invalid of [undefined, null, NaN, Infinity, -1, 1.5, "60", Number.MAX_SAFE_INTEGER + 1]) {
      h.props.policy = { late_cancel_before_min: invalid, no_show_wait_min: invalid };
      h.render(); await h.settle();
      assert.ok(!/\d|NaN|Infinity|undefined|null/.test(text(h.tree)));
      assert.equal(button(h.tree, collapse).props["aria-expanded"], true);
    }
    h.props.policy = null; h.render(); await h.settle();
    assert.ok(!/\d/.test(text(h.tree)));
    h.unmount();
  });

  await t.test(`${lang}: нулевой порог сервера не подменяется другим значением`, async () => {
    const h = await open({ late_cancel_before_min: 0, no_show_wait_min: 0 });
    assert.ok(text(h.tree).includes("0"));
    button(h.tree, details).props.onClick(); await h.settle();
    assert.equal((text(h.tree).match(/0/g) ?? []).length, 2);
    h.unmount();
  });

  await t.test(`${lang}: подробности включают ожидание от последнего события и запрет отмены в машине`, async () => {
    const h = await open({ no_show_wait_min: 27 });
    button(h.tree, details).props.onClick(); await h.settle();
    const visible = text(h.tree);
    for (const phrase of lang === "ru"
      ? ["после самого позднего", "«Подъезжаю»", "расчётный приезд на остановку", "только если разбор подтвердит", "Уже в машине — отменить нельзя", "SOS"]
      : ["иң һуңғы ваҡиғанан", "«Яҡынлашам»", "туҡталышҡа иҫәпләнгән килеп етеү", "тик тикшереү раҫлаһа", "Машиналаһың — кире алырға ярамай", "SOS"]) {
      assert.ok(visible.includes(phrase), phrase);
    }
    button(h.tree, collapse).props.onClick(); await h.settle();
    assert.equal(button(h.tree, details).props["aria-expanded"], false);
    assert.ok(!text(h.tree).includes("27"));
    h.unmount();
  });

  await t.test(`${lang}: быстрое двойное нажатие не оставляет подробности открытыми`, async () => {
    const h = await open();
    const toggle = button(h.tree, details).props.onClick;
    toggle(); toggle(); await h.settle();
    assert.equal(button(h.tree, details).props["aria-expanded"], false);
    h.unmount();
  });

  await t.test(`${lang}: уменьшение движения отключает анимацию и подписка снимается после ухода`, async () => {
    reduce = false;
    const h = await open();
    const query = queries.at(-1);
    assert.equal(query.listenerCount, 1);
    assert.equal(h.tree.props.style, undefined);
    reduce = true; query.notify(); await h.settle();
    button(h.tree, details).props.onClick(); await h.settle();
    for (const node of all(h.tree).filter((node) => /(^| )(act-card|faq-item|faq-a|faq-q__chev)( |$)/.test(node.props.className ?? ""))) {
      assert.equal(node.props.style.animation, "none");
      assert.equal(node.props.style.transition, "none");
    }
    h.unmount(); assert.equal(query.listenerCount, 0);
    query.notify();
  });

  await t.test(`${lang}: без браузерного matchMedia блок остаётся доступным без анимации`, async () => {
    const previous = globalThis.window;
    globalThis.window = {};
    const h = await open();
    assert.equal(h.tree.props.style.animation, "none");
    button(h.tree, details).props.onClick(); await h.settle();
    assert.ok(button(h.tree, collapse));
    h.unmount(); globalThis.window = previous;
  });
}

globalThis.window = previousWindow;
globalThis.fetch = previousFetch;
if (storageDescriptor) Object.defineProperty(globalThis, "localStorage", storageDescriptor);
else delete globalThis.localStorage;
t.done();
