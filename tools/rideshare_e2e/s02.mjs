// node tools/rideshare_e2e/s02.mjs A|B <unique-tag>
// Pair A: Android passenger / PWA driver; pair B reverses the actual clients.
import { Scenario, runScenario } from "./lib/scenario.mjs";
import { A, AF, ACC, q, q1, sleep, pwaClient, androidAs, userIdOf, lastRideOf, standHealthy, ufaClock } from "./lib/stand.mjs";
import * as P from "./lib/pwa-flows.mjs";
import * as RQ from "./lib/request-flows.mjs";
const pair = process.argv[2], tag = process.argv[3];
if (!["A", "B"].includes(pair) || !tag) throw new Error("Expected A|B and a unique run tag");
const sc = new Scenario(`W1-E2E-02-${pair}-${tag}`, { requirement: "W1-E2E-02", pair: pair === "A" ? "android-passenger+pwa-driver" : "android-driver+pwa-passenger" });
const route = { from: "Уфа", to: "Мелеуз", stops: ["Стерлитамак", "Салават"], pickup: "Стерлитамак", dropoff: "Салават" };
const price = Number(process.env.RSACC_PRICE || (pair === "A" ? 531 : 532));
const driver = pair === "A" ? ACC.pwaDriver : ACC.andDriver;
const passenger = pair === "A" ? ACC.andPassenger : ACC.pwaPassenger;
let pwa;
await runScenario(sc, async () => {
  await standHealthy(sc);
  pwa = await pwaClient(pair === "A" ? driver : passenger); sc.pages = [pwa.page];
  await androidAs(pair === "A" ? passenger : driver);
  const departure = ufaClock(20), pid = userIdOf(passenger), did = userIdOf(driver);
  const rideCountBefore = q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", did).n;
  await sc.step("driver publishes one existing ride", async () => {
    if (pair === "A") await P.publishRide(pwa.page, { ...route, departAt: departure.date, seats: 3, price });
    else await AF.publishRide({ ...route, ...departure, seats: 3, price });
  });
  const ride = lastRideOf(driver); sc.facts.ride_id = ride.id;
  sc.check(ride.seats_left === 3 && rideCountBefore + 1 === q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", did).n, "exactly one published ride before the request");
  await sc.step("passenger creates request for the intermediate section", async () => {
    const input = { from: route.pickup, to: route.dropoff, price, departAt: departure.date };
    if (pair === "A") await RQ.androidCreateRequest(input); else await RQ.pwaCreateRequest(pwa.page, input);
  });
  const request = await RQ.until(() => RQ.requestRow(pid), (r) => r?.from_city === route.pickup && r.to_city === route.dropoff && r.status === "active", "request saved");
  sc.facts.request_id = request.id;
  await sc.step("driver explicitly offers own existing ride", async () => {
    if (pair === "A") await RQ.pwaOfferOwnRide(pwa.page, { passenger: passenger.firstName, price });
    else await RQ.androidOfferOwnRide({ passenger: passenger.firstName, price });
  });
  const response = await RQ.until(() => RQ.responseRow(request.id, did), (r) => r?.ride_id === ride.id && r.status === "offered", "bound response saved");
  sc.check(response.pickup_stop === route.pickup && response.dropoff_stop === route.dropoff, "offer binds both explicit stops and the same ride");
  await sc.step("passenger accepts by a real double tap", async () => {
    if (pair === "A") await RQ.androidAcceptOffer(route); else await RQ.pwaAcceptOffer(pwa.page, route);
  });
  const accepted = await RQ.until(() => RQ.responseRow(request.id, did), (r) => r?.status === "accepted" && r.booking_id, "offer accepted");
  const booking = q1("SELECT * FROM booking WHERE id=?", accepted.booking_id); sc.facts.booking_id = booking.id;
  const assertSingle = () => {
    sc.check(q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", did).n === rideCountBefore + 1, "accept/reload creates no second ride");
    sc.check(q1("SELECT COUNT(*) n FROM booking WHERE ride_id=? AND passenger_id=?", ride.id, pid).n === 1, "accept double tap creates exactly one booking");
    sc.check(q1("SELECT seats_left FROM ride WHERE id=?", ride.id).seats_left === 2, "one seat is reserved exactly once");
    sc.check(q1("SELECT COUNT(*) n FROM payment WHERE booking_id=?", booking.id).n === 0 && q1("SELECT COUNT(*) n FROM ledgerentry WHERE booking_id=?", booking.id).n === 0, "the trust payment flow creates no platform payment or debit");
  };
  sc.check(booking.ride_id === ride.id && booking.status === "confirmed" && booking.pickup_stop === route.pickup && booking.dropoff_stop === route.dropoff && booking.price === price, "accepted booking preserves ride, section, status and agreed amount");
  assertSingle();
  await sc.step("reload both clients without repeating acceptance", async () => {
    A.forceStop(); A.launch(); await sleep(2500); await pwa.page.reload(); await sleep(1500);
    if (pair === "A") await AF.openMyBooking({ route: `${route.from} → ${route.to}`, status: "Подтверждена", cardText: `${price} ₽` });
    else await P.openMyTrip(pwa.page, { route: `${route.from} → ${route.to}`, cardText: `${price} ₽` });
  });
  assertSingle();
  await sc.step("driver boards then completes the same booking", async () => {
    if (pair === "A") await P.driverBoard(pwa.page, { passenger: passenger.firstName }); else await AF.driverBoard({ passenger: passenger.firstName });
    await RQ.until(() => q1("SELECT status FROM booking WHERE id=?", booking.id), (r) => r.status === "onboard", "boarded");
    if (pair === "A") { await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName }); await P.tripButton(pwa.page, "Завершить поездку"); }
    else await AF.driverComplete({ passenger: passenger.firstName });
  });
  await RQ.until(() => q1("SELECT status FROM booking WHERE id=?", booking.id), (r) => r.status === "done", "trip completed");
  await sc.step("both clients reach ratings, receipts and history", async () => {
    if (pair === "A") {
      await AF.passengerRateAndReceipt({ stars: 5, route: `${route.from} → ${route.to}`, cardText: `${price} ₽`, expect: [`${route.pickup} → ${route.dropoff}`] });
      await P.driverRatePassenger(pwa.page, { bookingId: booking.id, stars: 4 }); await P.driverReceipt(pwa.page, { bookingId: booking.id });
      await AF.passengerHistory({ route: `${route.from} → ${route.to}` }); await P.driverArchive(pwa.page, { route: `${route.from} → ${route.to}` });
    } else {
      await P.passengerRateAndReceipt(pwa.page, { bookingId: booking.id, stars: 5 }); await AF.driverRateAndReceipt({ passenger: passenger.firstName, stars: 4 });
      await AF.driverArchive({ route: `${route.from} → ${route.to}` }); await P.passengerHistory(pwa.page, { route: `${route.from} → ${route.to}`, cardText: `${price} ₽` });
    }
  });
  sc.check(q("SELECT id FROM rating WHERE booking_id=?", booking.id).length === 2, "both ratings refer to the single booking");
  assertSingle();
}, async () => { if (pwa) await pwa.ctx.close(); });
