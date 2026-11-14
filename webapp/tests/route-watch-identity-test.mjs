import assert from "node:assert/strict";
import { matchingRouteWatch, routeWatchPath, validWatchPlace, watchLocalDay } from "./.bundles/route-watch-identity.mjs";

const ride = {
  id: 7, from_city: " Ургаза ", to_city: "АКЪЯР", direction: "forward", watch_kind: "rides",
  watch_date: "2030-05-01T19:00:00", expires_at: "2030-05-14T19:00:00",
};
const route = { from: "ургаза", to: "Акъяр", day: "2030-05-02" };
const other = [
  { ...ride, id: 1, watch_kind: "requests" },
  { ...ride, id: 2, watch_kind: "both" },
  { ...ride, id: 3, direction: "both" },
  { ...ride, id: 4, from_city: "Акъяр", to_city: "Ургаза" },
  { ...ride, id: 5, watch_date: "2030-05-02T19:00:00" },
];

assert.equal(watchLocalDay(ride.watch_date), "2030-05-02");
assert.equal(watchLocalDay("2030-05-02T00:00:00+05:00"), "2030-05-02");
assert.equal(matchingRouteWatch(other, route), undefined);
assert.equal(matchingRouteWatch([...other, { ...ride, id: 9 }, ride], route)?.id, 7);
assert.equal(matchingRouteWatch([ride], { ...route, day: null }), undefined);
assert.equal(matchingRouteWatch([{ ...ride, watch_date: null }], { ...route, day: null })?.id, 7);
assert.equal(matchingRouteWatch([{ ...ride, from_city: "Straße" }], { ...route, from: "STRASSE" })?.id, 7);
assert.equal(matchingRouteWatch([{ ...ride, from_city: "Işık" }], { ...route, from: "İşık" }), undefined);
assert.equal(matchingRouteWatch([{ ...ride, expires_at: "2000-01-01T00:00:00" }], route)?.id, 7);
assert.equal(validWatchPlace("Өфө"), true);
assert.equal(validWatchPlace("Көҙгө Йылға (район)"), true);
assert.equal(validWatchPlace("Уфа ⑤"), false);
assert.equal(validWatchPlace("А"), false);
assert.equal(validWatchPlace("А".repeat(120)), true);
assert.equal(validWatchPlace("А".repeat(121)), false);
const link = new URL(routeWatchPath({ from: " Ургаза & Сибай ", to: "", day: route.day }), "https://fixture.invalid");
assert.equal(link.pathname, "/route-watches");
assert.equal(link.searchParams.get("from"), "Ургаза & Сибай");
assert.equal(link.searchParams.get("to"), null);
assert.equal(link.searchParams.get("date"), route.day);
console.log("19 passed");
