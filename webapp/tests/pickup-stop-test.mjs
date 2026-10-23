import assert from "node:assert/strict";
import { all, bundleComponent, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const ride = { id: 7, from_city: "Баймак", to_city: "Уфа", waypoints: "Акъяр | Сибай" };
for (const lang of ["ru", "ba"]) {
  const options = { real: ["/utils/pickupStops"], mocks: { "/i18n/lang": langMock(lang) } };
  const Note = await bundleComponent("src/components/PickupStopChoice.tsx", { ...options, exportName: "PickupStopNote" });
  const Choice = await bundleComponent("src/components/PickupStopChoice.tsx", { ...options, exportName: "PickupStopChoice" });
  const Hint = await bundleComponent("src/components/PickupStopChoice.tsx", { ...options, exportName: "MatchedPickupStopNote" });
  await t.test(lang + ": unknown private place asks in chat and never claims start", () => {
    const s = mount(Note, { pickupStop: null, rideHasStops: true, from: ride.from_city }); s.render();
    assert.equal(text(s.tree), lang === "ru" ? "Место посадки не указано — уточни в чате" : "Ултырыу урыны күрһәтелмәгән — чатта асыҡла");
    assert.ok(!text(s.tree).includes(ride.from_city)); s.unmount();
  });
  await t.test(lang + ": start, waypoint and old server remain distinct", () => {
    let s = mount(Note, { pickupStop: "", rideHasStops: true, from: ride.from_city }); s.render(); assert.ok(text(s.tree).includes("Баймак")); s.unmount();
    s = mount(Note, { pickupStop: "Акъяр", rideHasStops: true, from: ride.from_city }); s.render(); assert.ok(text(s.tree).includes("Акъяр")); assert.ok(!text(s.tree).includes("Баймак")); s.unmount();
    s = mount(Note, {}); s.render(); assert.equal(s.tree, null); s.unmount();
  });
  // Строка про высадку — зеркало защиты посадки: на рейсе без остановок и на старых бронях «не указано» было бы ложью.
  const Segment = await bundleComponent("src/components/PickupStopChoice.tsx", { ...options, exportName: "BookingSegmentNote" });
  const expand = n => Array.isArray(n) ? n.map(expand) : n && typeof n === "object"
    ? (typeof n.type === "function" ? expand(n.type(n.props)) : { ...n, props: { ...n.props, children: expand(n.props?.children) } }) : n;
  const segment = props => { const s = mount(Segment, props); s.render(); const out = text(expand(s.tree)); s.unmount(); return out; };
  const unset = lang === "ru" ? "не указано" : "күрһәтелмәгән";
  await t.test(lang + ": рейс без остановок и старая бронь не врут «место высадки не указано»", () => {
    const direct = segment({ pickupStop: "", dropoffStop: null, rideHasStops: false, from: "Баймак", to: "Уфа" });
    assert.ok(!direct.includes(unset), direct);
    assert.ok(direct.includes(lang === "ru" ? "Посадка: начало маршрута" : "Ултырыу: юлдың башы"), "посадка у начала по-прежнему названа");
    assert.ok(!direct.includes(lang === "ru" ? "Высадка" : "Төшөү"), "про высадку молчим, пока она не названа");
    for (const old of [{}, { pickupStop: null, dropoffStop: null }, { pickupStop: undefined, dropoffStop: undefined, rideHasStops: undefined }, { pickupStop: null, dropoffStop: null, rideHasStops: false }])
      assert.equal(segment(old), "", "старый сервер без полей — нет ни одной строки «не указано»: " + JSON.stringify(old));
  });
  await t.test(lang + ": на рейсе с остановками неназванная высадка честно просит уточнить в чате; названная показана всегда", () => {
    const asked = segment({ pickupStop: null, dropoffStop: null, rideHasStops: true });
    assert.equal((asked.match(new RegExp(unset, "g")) || []).length, 2, "и посадка, и высадка: " + asked);
    assert.ok(asked.includes(lang === "ru" ? "Место высадки не указано — уточни в чате" : "Төшөү урыны күрһәтелмәгән — чатта асыҡла"));
    const named = segment({ pickupStop: "Акъяр", dropoffStop: "Сибай", rideHasStops: true });
    assert.ok(named.includes(lang === "ru" ? "Высадка: Сибай" : "Төшөү: Сибай") && !named.includes(unset));
    // высадка названа, хотя флага рейса нет (старая бронь с названием) — не прячем
    assert.ok(segment({ pickupStop: "", dropoffStop: "Сибай", rideHasStops: false, from: "Баймак" }).includes(lang === "ru" ? "Высадка: Сибай" : "Төшөү: Сибай"));
    assert.ok(segment({ pickupStop: "", dropoffStop: "", rideHasStops: false, from: "Баймак", to: "Уфа" }).includes((lang === "ru" ? "Высадка: конец маршрута" : "Төшөү: юлдың аҙағы") + " · Уфа"));
  });
  await t.test(lang + ": choices are accessible radios and include no destination", () => {
    let picked; const s = mount(Choice, { ride, picked: null, onPick: place => { picked = place; } }); s.render();
    const choices = all(s.tree).filter(n => n.type === "input");
    assert.equal(s.tree.type, "fieldset"); assert.equal(choices.length, 3);
    assert.ok(choices.every(n => n.props.type === "radio" && !n.props.checked));
    assert.ok(!choices.some(n => n.props.value === "Уфа")); choices[2].props.onChange(); assert.equal(picked, "Сибай");
    s.unmount();
  });
  // Радио — цвета бренда (.checkbox, accent-color), не браузерный синий; выбранная строка подсвечена (.list-row--on).
  const Dropoff = await bundleComponent("src/components/PickupStopChoice.tsx", { ...options, exportName: "DropoffStopChoice" });
  await t.test(lang + ": радио посадки и высадки — класс бренда, выбранная строка подсвечена, невыбранные нет", () => {
    for (const [Comp, props, pick, name] of [[Choice, { ride, picked: "Акъяр", onPick() {} }, "Акъяр", "pickup-stop-7"], [Dropoff, { ride, pickup: "Баймак", picked: "Сибай", onPick() {} }, "Сибай", "dropoff-stop-7"]]) {
      const s = mount(Comp, props); s.render();
      const rows = all(s.tree).filter(n => n.type === "label");
      assert.ok(rows.length >= 3);
      for (const row of rows) {
        const input = all(row).find(n => n.type === "input");
        assert.equal(input.props.className, "checkbox", "радио без .checkbox красится браузерным синим");
        assert.equal(input.props.name, name);
        assert.equal(row.props.className.includes("list-row--on"), input.props.value === pick, "подсвечена только выбранная строка: " + input.props.value);
      }
      s.unmount();
    }
  });
  await t.test(lang + ": feed hint is public server metadata, never a private passenger choice", () => {
    let s = mount(Hint, { ride }); s.render(); assert.equal(s.tree, null); s.unmount();
    s = mount(Hint, { ride: { ...ride, matched_stop: "Акъяр" } }); s.render(); assert.ok(text(s.tree).includes("Акъяр")); s.unmount();
    s = mount(Hint, { ride: { ...ride, matched_stop: "Уфа" } }); s.render(); assert.equal(s.tree, null); s.unmount();
  });
}
t.done();
