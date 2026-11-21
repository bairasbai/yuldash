import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const cases = checker();
const stamp = day => new Date(day + "T09:00").toISOString();
const done = stamp("2031-06-01");
const rejected = stamp("2031-06-03");
const retained = stamp("2031-06-05");
const intent = { items: [
  { commandKey: "first", depart_at: done, outcome: "applied" },
  { commandKey: "second", depart_at: rejected, outcome: "rejected" },
  { commandKey: "third", depart_at: retained, outcome: "rejected" },
] };

for (const lang of ["ru", "ba"]) {
  const Screen = await bundleComponent("src/components/RideSeriesRecovery.tsx", {
    real: ["/RideSeriesFields", "/utils/dateInput", "/utils/serverTime"],
    mocks: { "/i18n/lang": langMock(lang), "/api/rideSeries": "export const canRepeatRideSeries=()=>false;" },
  });
  await cases.test(`${lang}: сохранённое время блокирует замену до запроса, остальные исходы остаются`, async () => {
    const retries = [];
    const h = mount(Screen, { intent, busy: false, onRetry: (...args) => retries.push(args) });
    try {
      const localized = (ru, ba) => lang === "ba" ? ba : ru;
      h.render();
      button(h.tree, localized("Выбрать даты для новой попытки", "Яңы ынтылыш өсөн көндәрҙе һайларға")).props.onClick();
      await h.settle();
      const retainedCheckbox = ofType(h.tree, "input").filter(node => node.props.type === "checkbox")[1];
      retainedCheckbox.props.onChange();
      await h.settle();
      const calendar = () => all(h.tree).find(node => typeof node.type === "function" && node.type.name === "RideSeriesFields");
      assert.equal(calendar().props.limit, 1);
      calendar().props.onDays(["2031-06-01"]);
      calendar().props.onTime("09:00");
      await h.settle();
      const publish = () => button(h.tree, localized("Опубликовать выбранные даты заново", "Һайланған көндәрҙе яңынан баҫтырырға"));
      assert.equal(publish().props.disabled, true);
      assert.ok(text(h.tree).includes(localized("Для этого времени уже есть результат.", "Был ваҡытҡа һөҙөмтә бар инде.")));
      assert.deepEqual(retries, []);

      calendar().props.onDays(["2031-06-04"]);
      await h.settle();
      assert.equal(publish().props.disabled, false);
      publish().props.onClick();
      assert.deepEqual(retries, [[[stamp("2031-06-04")], [rejected]]]);
    } finally { h.unmount(); }
  });
}

cases.done();
