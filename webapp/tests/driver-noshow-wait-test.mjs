// ================================================================
//  «Не вышел» — через 15 минут после выезда и с вопросом «Точно?» (решение 18.10.2026).
//  Паритет с Android (RideshareNoShow.kt, DriverBoardingControlsTest): то же поведение 1-в-1.
//
//  Что было. Водитель мог отметить неявку в любой момент — хоть за неделю до поездки — одним
//  касанием, и вернуть было нельзя: промахнулся, и бронь человека, который стоит у дороги, снята,
//  а место ушло в продажу. У BlaBlaCar водитель ждёт 15 минут после времени выезда.
//
//  Что стало. Срок знает сервер (`no_show_at` в строке брони), сайт 15 минут у себя не держит:
//   • до срока вместо кнопки — подпись «можно отметить с 18.10, 14:35»;
//   • после — кнопка, нажатие открывает «Точно не вышел?» и ничего не отправляет;
//   • «Да, не вышел» отправляет (зовёт родителя), «Подождать» закрывает окно, бронь не тронута;
//   • срока нет (старый сервер) — кнопка есть, последнее слово за сервером (409 no_show_too_early).
//
//  Исполняем настоящий DriverNoShow (shallow-harness.mjs): часы и таймер подменены, чтобы «подпись
//  → кнопка» проверялась точно, а не «подождём минуту».
// ================================================================
import assert from "node:assert/strict";
import { bundleComponent, button, checker, langMock, mount, ofType, text, all } from "./shallow-harness.mjs";

const t = checker();

async function view(lang) {
  return bundleComponent("src/components/DriverNoShow.tsx", {
    mocks: { "/i18n/lang": langMock(lang) },
    real: ["/utils/format", "/utils/serverTime"],
  });
}

/** Часы и setInterval, которыми управляет тест. Возвращает управление и «снять подмену». */
function fakeClock(startMs) {
  const real = { now: Date.now, setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval };
  const clock = { ms: startMs, timers: new Map(), seq: 0, cleared: 0 };
  Date.now = () => clock.ms;
  globalThis.setInterval = (fn) => { clock.timers.set(++clock.seq, fn); return clock.seq; };
  globalThis.clearInterval = (id) => { if (clock.timers.delete(id)) clock.cleared++; };
  clock.tick = (ms) => { clock.ms += ms; [...clock.timers.values()].forEach((fn) => fn()); };
  clock.restore = () => { Date.now = real.now; globalThis.setInterval = real.setInterval; globalThis.clearInterval = real.clearInterval; };
  return clock;
}

/** Наивный UTC как отдаёт сервер: «2030-01-01T05:15:00» (без «Z»). */
const naiveUtc = (ms) => new Date(ms).toISOString().slice(0, 19);
/** Та же строка «18.10, 14:35» в часах человека — как `formatDepart` в приложении. */
const localClock = (ms) => {
  const d = new Date(ms), p = (n) => String(n).padStart(2, "0");
  return `${p(d.getDate())}.${p(d.getMonth() + 1)}, ${p(d.getHours())}:${p(d.getMinutes())}`;
};

const NOW = Date.UTC(2030, 0, 1, 5, 0, 0);   // «сейчас» в тесте
const MIN = 60_000;

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  const NO_SHOW = L("Не вышел", "Сыҡманы");

  async function mountAt({ noShowAt, busy = false, now = NOW }) {
    const Ctl = await view(lang);   // сборка — до подмены часов
    const clock = fakeClock(now);
    const sent = [];
    const h = mount(Ctl, { noShowAt, busy, onConfirm: () => sent.push("confirm") });
    h.render();
    return { h, clock, sent };
  }

  await t.test(`${lang}: до срока вместо кнопки — подпись «можно отметить с …», диалога нет`, async () => {
    const opens = NOW + 2 * 60 * MIN;
    const { h, clock, sent } = await mountAt({ noShowAt: naiveUtc(opens) });
    try {
      assert.equal(button(h.tree, NO_SHOW), undefined, "кнопки «Не вышел» до срока быть не должно");
      assert.equal(ofType(h.tree, "AlertDialog").length, 0);
      const note = all(h.tree).find((n) => n.props?.role === "note");
      assert.ok(note, "вместо кнопки — спокойная подпись");
      assert.equal(
        text(note),
        L(`Не вышел — можно отметить с ${localClock(opens)}`, `Сыҡманы — ${localClock(opens)} үткәс кенә билдәләп була`),
        "время — сервера (UTC), показано в часах человека"
      );
      assert.deepEqual(sent, []);
    } finally { h.unmount(); clock.restore(); }
  });

  await t.test(`${lang}: срок пришёл — кнопка; нажатие открывает «Точно?» и ничего не отправляет`, async () => {
    const { h, clock, sent } = await mountAt({ noShowAt: naiveUtc(NOW - MIN) });
    try {
      assert.equal(all(h.tree).find((n) => n.props?.role === "note"), undefined, "подписи после срока нет");
      button(h.tree, NO_SHOW).props.onClick();
      await h.settle();
      assert.deepEqual(sent, [], "с первого касания неявка не уходит");
      const dlg = ofType(h.tree, "AlertDialog")[0];
      assert.ok(dlg, "открылся вопрос");
      assert.equal(dlg.props.title, L("Точно не вышел?", "Ысынлап та сыҡманымы?"));
      assert.equal(
        dlg.props.text,
        L(
          "Бронь снимется, место вернётся в продажу. Если пассажир уже идёт — лучше подожди.",
          "Бронь алына, урын кире һатыуға ҡайта. Пассажир килеп барһа — әҙ генә көт."
        )
      );
      assert.equal(dlg.props.confirm.label, L("Да, не вышел", "Эйе, сыҡманы"));
      assert.equal(dlg.props.confirm.tone, "danger");
      assert.equal(dlg.props.dismiss.label, L("Подождать", "Көтөргә"));
    } finally { h.unmount(); clock.restore(); }
  });

  await t.test(`${lang}: «Подождать» и Esc закрывают вопрос, бронь не тронута`, async () => {
    const { h, clock, sent } = await mountAt({ noShowAt: naiveUtc(NOW - MIN) });
    try {
      button(h.tree, NO_SHOW).props.onClick();
      await h.settle();
      ofType(h.tree, "AlertDialog")[0].props.dismiss.onClick();
      await h.settle();
      assert.equal(ofType(h.tree, "AlertDialog").length, 0, "«Подождать» закрывает окно");
      assert.ok(button(h.tree, NO_SHOW), "кнопка на месте");
      button(h.tree, NO_SHOW).props.onClick();
      await h.settle();
      ofType(h.tree, "AlertDialog")[0].props.onClose();
      await h.settle();
      assert.equal(ofType(h.tree, "AlertDialog").length, 0, "тап мимо / Esc закрывают окно");
      assert.deepEqual(sent, [], "ни «Подождать», ни закрытие ничего не отправили");
    } finally { h.unmount(); clock.restore(); }
  });

  await t.test(`${lang}: «Да, не вышел» отправляет ровно один раз`, async () => {
    const { h, clock, sent } = await mountAt({ noShowAt: naiveUtc(NOW - MIN) });
    try {
      button(h.tree, NO_SHOW).props.onClick();
      await h.settle();
      ofType(h.tree, "AlertDialog")[0].props.confirm.onClick();
      await h.settle();
      assert.deepEqual(sent, ["confirm"]);
      assert.equal(ofType(h.tree, "AlertDialog").length, 0, "окно закрылось");
    } finally { h.unmount(); clock.restore(); }
  });

  await t.test(`${lang}: подпись сама сменяется кнопкой в нужную секунду, таймер после этого снимается`, async () => {
    const opens = NOW + 90 * 1000;   // через полторы минуты
    const { h, clock } = await mountAt({ noShowAt: naiveUtc(opens) });
    try {
      assert.equal(button(h.tree, NO_SHOW), undefined);
      assert.equal(clock.timers.size, 1, "пока ждём — тикает один таймер");
      clock.tick(89 * 1000);
      await h.settle();
      assert.equal(button(h.tree, NO_SHOW), undefined, "за секунду до срока кнопки ещё нет");
      clock.tick(1000);
      await h.settle();
      assert.ok(button(h.tree, NO_SHOW), "ровно в срок кнопка появилась");
      assert.equal(clock.timers.size, 0, "срок наступил — таймер снят");
    } finally { h.unmount(); clock.restore(); }
  });

  await t.test(`${lang}: срока нет (старый сервер, null, кривая строка) — кнопка есть, решает сервер`, async () => {
    for (const noShowAt of [undefined, null, "", "не дата"]) {
      const { h, clock } = await mountAt({ noShowAt });
      try {
        assert.ok(button(h.tree, NO_SHOW), `срок «${noShowAt}» — кнопку не прячем`);
        assert.equal(clock.timers.size, 0, "считать нечего — таймера нет");
      } finally { h.unmount(); clock.restore(); }
    }
  });

  await t.test(`${lang}: пока запрос идёт, кнопка заблокирована`, async () => {
    const { h, clock } = await mountAt({ noShowAt: naiveUtc(NOW - MIN), busy: true });
    try {
      assert.equal(button(h.tree, NO_SHOW).props.disabled, true);
    } finally { h.unmount(); clock.restore(); }
  });
}

t.done();
