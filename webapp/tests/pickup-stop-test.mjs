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
  await t.test(lang + ": choices are accessible radios and include no destination", () => {
    let picked; const s = mount(Choice, { ride, picked: null, onPick: place => { picked = place; } }); s.render();
    const choices = all(s.tree).filter(n => n.type === "input");
    assert.equal(s.tree.type, "fieldset"); assert.equal(choices.length, 3);
    assert.ok(choices.every(n => n.props.type === "radio" && !n.props.checked));
    assert.ok(!choices.some(n => n.props.value === "Уфа")); choices[2].props.onChange(); assert.equal(picked, "Сибай");
    s.unmount();
  });
  await t.test(lang + ": feed hint is public server metadata, never a private passenger choice", () => {
    let s = mount(Hint, { ride }); s.render(); assert.equal(s.tree, null); s.unmount();
    s = mount(Hint, { ride: { ...ride, matched_stop: "Акъяр" } }); s.render(); assert.ok(text(s.tree).includes("Акъяр")); s.unmount();
    s = mount(Hint, { ride: { ...ride, matched_stop: "Уфа" } }); s.render(); assert.equal(s.tree, null); s.unmount();
  });
}
t.done();
