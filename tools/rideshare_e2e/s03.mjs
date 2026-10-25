// W1-E2E-03 — real UI cancellation/refusal/boarding/last-seat/no-show in both client pairs.
// node tools/rideshare_e2e/s03.mjs A|B [runTag]; no-show waits for real server time.
import { Scenario, runScenario } from "./lib/scenario.mjs";
import { A, AF, ACC, q, q1, sleep, log, pwaClient, androidAs, userIdOf, lastRideOf, standHealthy, ufaClock, wireFrom, wireSince } from "./lib/stand.mjs";
import * as P from "./lib/pwa-flows.mjs";

const pair = process.argv[2] || "A";
if (!["A", "B"].includes(pair)) throw new Error("Expected pair A or B");
const tag = process.argv[3] || new Date().toISOString().replace(/[^0-9]/g, "");
const sc = new Scenario(`W1-E2E-03-${pair}-${tag}`, { requirement: "W1-E2E-03", pair: pair === "A" ? "android-passenger+pwa-driver" : "android-driver+pwa-passenger" });
A.setSerial(process.env.ANDROID_SERIAL || "emulator-5560");
const route = { from: "Уфа", to: "Мелеуз", stops: ["Стерлитамак", "Салават"], pickup: "Стерлитамак", dropoff: "Салават", title: "Уфа → Мелеуз" };
const driver = pair === "A" ? ACC.pwaDriver : ACC.andDriver;
const passenger = pair === "A" ? ACC.andPassenger : ACC.pwaPassenger;
const created = [];
let pwa, extra, index = 0;
let initialOwnRideCount = null;
const book = (ride, acc = passenger) => q1("SELECT * FROM booking WHERE ride_id = ? AND passenger_id = ? ORDER BY id DESC LIMIT 1", ride.id, userIdOf(acc));
async function waitDb(predicate, message, timeout = 20000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) { if (predicate()) return; await sleep(350); }
  sc.check(false, message);
}
async function newBooking(name, minutes = 20, pickup = route.pickup) {
  const segment = { pickup, dropoff: route.dropoff };
  const price = (pair === "A" ? 610 : 630) + Number(process.env.PRICE_OFFSET || 0) + index++;
  const before = q1("SELECT COUNT(*) n FROM ride WHERE driver_id = ?", userIdOf(driver)).n;
  const depart = ufaClock(minutes);
  await sc.step(name + ": publish one-seat ride through UI", async () => {
    if (pair === "A") await P.publishRide(pwa.page, { from: route.from, to: route.to, stops: route.stops, departAt: depart.date, seats: 1, price });
    else await AF.publishRide({ from: route.from, to: route.to, stops: route.stops, hh: depart.hh, mm: depart.mm, day: depart.day, seats: 1, price });
  });
  const ride = lastRideOf(driver);
  sc.check(ride && ride.price === price && ride.seats_left === 1, name + ": new UI-published ride has one free seat");
  sc.check(q1("SELECT COUNT(*) n FROM ride WHERE driver_id = ?", userIdOf(driver)).n === before + 1, name + ": exactly one ride created");
  created.push({ case: name, ride_id: ride.id, price });
  await sc.step(name + ": passenger books selected stops through UI", async () => {
    if (pair === "A") { await AF.searchAndOpen({ from: pickup, to: route.dropoff, cardText: `${price} ₽` }); await AF.chooseStopsAndBook(segment); }
    else { await P.searchRide(pwa.page, { from: pickup, to: route.dropoff, routeText: route.title, cardText: `${price} ₽` }); await P.bookRide(pwa.page, segment); }
  });
  const booking = book(ride);
  const storedPickup = pickup === route.from ? "" : pickup; // Server canonical representation of the route start.
  sc.check(booking?.status === "pending" && booking.pickup_stop === storedPickup && booking.dropoff_stop === route.dropoff, name + ": own booking preserves both stops");
  sc.check(q1("SELECT seats_left FROM ride WHERE id = ?", ride.id).seats_left === 0, name + ": last seat reserved once");
  created.at(-1).booking_id = booking.id;
  return { ride, booking, price, segment };
}
async function observedDriverWindow(fixture, from) {
  const end = Date.now() + 25000;
  while (Date.now() < end) {
    const events = await wireSince(from);
    const row = events.filter(e => e.client === (pair === "A" ? "browser" : "android"))
      .flatMap(e => e.driver_windows || []).findLast(r => r.booking_id === fixture.booking.id);
    if (row) return row;
    await sleep(500);
  }
  throw new Error("No timing fields observed in this driver's real UI response");
}
async function confirm(fixture) {
  if (pair === "A") await P.driverDecide(pwa.page, { passenger: passenger.firstName, action: "Подтвердить" });
  else await AF.driverDecide({ passenger: passenger.firstName, action: "Подтвердить" });
  await waitDb(() => book(fixture.ride)?.status === "confirmed", "driver confirmation persisted");
}
async function passengerOpen(fixture, status = null) {
  if (pair === "A") await AF.openMyBooking({ route: route.title, cardText: `${fixture.price} ₽`, status });
  else await P.openMyTrip(pwa.page, { route: route.title, cardText: `${fixture.price} ₽` });
}
async function cancelPassenger(fixture, confirmed = false) {
  await passengerOpen(fixture);
  if (pair === "A") {
    // Pending cancellation is fixed at the bottom; the confirmed action is in
    // scrolling content above the active-trip footer, whose top is around .74.
    await A.scrollTap(confirmed ? /^Отменить поездку$/ : /^Отменить бронь$/, { maxBottom: confirmed ? 0.72 : 0.96 });
    await A.tapText(/^Да, отменить$/);
  } else {
    await P.click(pwa.page, P.btn(pwa.page, confirmed ? /^Отменить поездку$/ : /^Отменить бронь$/), "passenger cancellation");
    await P.click(pwa.page, P.btn(pwa.page, /^Да, отменить$/), "confirm passenger cancellation");
  }
  await waitDb(() => book(fixture.ride)?.status === "cancelled", "passenger cancellation persisted");
  sc.check(q1("SELECT seats_left FROM ride WHERE id = ?", fixture.ride.id).seats_left === 1, "cancellation released the one reserved seat");
}
function oneBooking(fixture) {
  sc.check(q1("SELECT COUNT(*) n FROM booking WHERE ride_id = ?", fixture.ride.id).n === 1, "no duplicate booking after decision/reload");
  sc.check(q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", userIdOf(driver)).n === initialOwnRideCount + created.length, "decision/reload created no extra driver ride");
}
async function undoConsent(fixture, consent) {
  if (pair === "A") await P.driverBoard(pwa.page, { passenger: passenger.firstName });
  else await AF.driverBoard({ passenger: passenger.firstName });
  const original = book(fixture.ride);
  sc.check(original.status === "onboard" && original.onboard_at, "driver boarding recorded");
  if (pair === "A") {
    const row = P.driverRow(pwa.page, passenger.firstName);
    await P.click(pwa.page, row.getByRole("button", { name: /^Исправить посадку/ }), "driver requests boarding correction");
    await row.getByText("Ждём подтверждения пассажира", { exact: true }).waitFor();
  } else {
    await AF.driverCardAction({ passenger: passenger.firstName, action: /^Исправить посадку/ });
    await A.waitFor("Ждём подтверждения пассажира");
  }
  const pending = book(fixture.ride);
  sc.check(pending.status === "onboard" && pending.onboard_at === original.onboard_at, "boarding protection remains until passenger consent");
  await passengerOpen(fixture);
  const answer = consent ? "Я ещё не сел" : "Я в машине";
  if (pair === "A") { await A.scrollTo("Водитель просит исправить посадку"); await A.scrollTap(answer); }
  else {
    await pwa.page.getByText("Водитель просит исправить посадку", { exact: true }).waitFor();
    await P.click(pwa.page, P.btn(pwa.page, new RegExp(`^${answer}$`)), "passenger answers boarding-correction request");
  }
  if (consent) await waitDb(() => book(fixture.ride)?.status === "confirmed" && !book(fixture.ride)?.onboard_at, "passenger-confirmed correction restored confirmed state");
  else {
    await sleep(1000);
    sc.check(book(fixture.ride).status === "onboard" && book(fixture.ride).onboard_at === original.onboard_at, "passenger refusal preserves boarding and original deadline");
    // A refusal is final for this boarding; a second request would enable pressure/spam.
    if (pair === "A") {
      await P.openDriverCabinet(pwa.page);
      const request = P.driverRow(pwa.page, passenger.firstName).getByRole("button", { name: /^Исправить посадку/ });
      sc.check(await request.count() === 0 || await request.first().isDisabled(), "driver cannot request again after passenger refusal");
    } else {
      await AF.openDriverCabinet(); await A.scrollTo(passenger.firstName);
      const request = A.find(A.dump(), /^Исправить посадку/);
      sc.check(!request || request.enabled === "false", "Android driver cannot request again after passenger refusal");
    }
  }
  sc.check(q1("SELECT seats_left FROM ride WHERE id = ?", fixture.ride.id).seats_left === 0, "boarding correction does not release a still-confirmed seat");
}

await runScenario(sc, async () => {
  await standHealthy(sc); await androidAs(pair === "A" ? passenger : driver);
  pwa = await pwaClient(pair === "A" ? driver : passenger); sc.pages = [pwa.page];
  initialOwnRideCount = q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", userIdOf(driver)).n;
  sc.facts.requirement_acceptance = "not_accepted_pending_independent_review";
  sc.facts.coverage = ["driver decline", "passenger pending cancellation", "passenger confirmed cancellation", "passenger-consented boarding correction and refusal", "last-seat reservation/release", "no-show after real waiting"];
  const declined = await newBooking("driver-decline");
  await sc.step("driver declines and passenger sees refusal", async () => {
    if (pair === "A") { await P.driverDecide(pwa.page, { passenger: passenger.firstName, action: "Отклонить" }); await P.click(pwa.page, P.btn(pwa.page, /^Да, отклонить$/), "confirm driver decline"); }
    else await AF.driverCardAction({ passenger: passenger.firstName, action: /^Отклонить$/, confirm: /^Да, отклонить$/ });
    await waitDb(() => book(declined.ride)?.status === "cancelled", "driver decline persisted");
    sc.check(book(declined.ride).cancel_reason === "driver_declined", "driver refusal is distinguished from cancellation");
    sc.check(q1("SELECT seats_left FROM ride WHERE id = ?", declined.ride.id).seats_left === 1, "driver decline restored last seat");
    const history = { route: route.title, bookingId: declined.booking.id, passengerId: userIdOf(passenger), price: declined.price,
      expectedStatus: "cancelled", statusLabel: /Отклонена|Водитель отклонил бронь/ };
    if (pair === "A") {
      const texts = await AF.passengerHistory(history); sc.check(texts.some(t => /Отклонена|Водитель отклонил бронь/.test(t)), "Android current booking history says declined"); A.screencap("declined-history");
    } else { const text = await P.passengerHistory(pwa.page, history); sc.check(/Отклонена|Водитель отклонил бронь/.test(text), "PWA current booking history says declined"); }
    oneBooking(declined);
  });
  const pending = await newBooking("pending-cancel"); await sc.step("passenger cancels pending booking", () => cancelPassenger(pending)); oneBooking(pending);
  const confirmed = await newBooking("confirmed-cancel"); await confirm(confirmed); await sc.step("passenger cancels confirmed booking", () => cancelPassenger(confirmed, true)); oneBooking(confirmed);
  const refusal = await newBooking("boarding-refusal"); await confirm(refusal);
  await sc.step("passenger refusal preserves protection and forbids repeat requests", () => undoConsent(refusal, false));
  await sc.step("finish refused-correction trip through driver UI", async () => {
    if (pair === "A") { await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName }); await P.tripButton(pwa.page, "Завершить поездку"); }
    else await AF.driverComplete({ passenger: passenger.firstName });
    await waitDb(() => book(refusal.ride)?.status === "done", "driver finished refused-correction booking");
  }); oneBooking(refusal);
  const undo = await newBooking("boarding-consent"); await confirm(undo);
  await sc.step("another booking: passenger confirms correction", () => undoConsent(undo, true)); await cancelPassenger(undo, true); oneBooking(undo);
  const last = await newBooking("last-seat");
  await sc.step("second passenger cannot take occupied last seat and can book released seat", async () => {
    extra = await pwaClient(ACC.pwaPassenger2); sc.pages.push(extra.page);
    await extra.page.goto(P.PWA + `/rides/${last.ride.id}`, { waitUntil: "domcontentloaded" });
    await extra.page.getByText(/Поездка больше недоступна для бронирования/).waitFor();
    sc.check(q1("SELECT COUNT(*) n FROM booking WHERE ride_id = ? AND passenger_id = ?", last.ride.id, userIdOf(ACC.pwaPassenger2)).n === 0, "full-ride UI created no extra booking");
    await cancelPassenger(last);
    await extra.page.reload(); await P.bookRide(extra.page, route);
    const second = book(last.ride, ACC.pwaPassenger2);
    sc.check(second?.status === "pending" && q1("SELECT seats_left FROM ride WHERE id = ?", last.ride.id).seats_left === 0, "second passenger reserved exactly the released seat");
    // RideSheet opens /trip even while pending; cancellation belongs to the
    // pending booking reached through My trips → Details, as in cancelPassenger.
    await P.dismissInstall(extra.page);
    await P.openMyTrip(extra.page, { route: route.title, cardText: `${last.price} ₽` });
    sc.check(new URL(extra.page.url()).pathname === `/booking/${second.id}`, "second passenger opened the exact pending booking before cancellation");
    await P.click(extra.page, P.btn(extra.page, /^Отменить бронь$/), "second passenger cancels own booking");
    await P.click(extra.page, P.btn(extra.page, /^Да, отменить$/), "confirm own cancellation");
    await waitDb(() => book(last.ride, ACC.pwaPassenger2)?.status === "cancelled", "second passenger cancellation stored");
    sc.check(q1("SELECT COUNT(*) n FROM booking WHERE ride_id = ?", last.ride.id).n === 2 && q1("SELECT seats_left FROM ride WHERE id = ?", last.ride.id).seats_left === 1, "two deliberate bookings, no duplicate, one restored seat");
    await extra.ctx.close(); extra = null;
  });
  const absent = await newBooking("no-show-real-clock", 6, route.from); await confirm(absent);
  await sc.step("driver announces arrival through UI", async () => {
    const from = await wireFrom();
    if (pair === "A") await P.openDriverCabinet(pwa.page); else { await AF.goHome(); await AF.openDriverCabinet(); }
    const window = await observedDriverWindow(absent, from), opens = window.arriving_opens_at_ms;
    sc.check(Number.isFinite(opens) && opens - Date.now() < 25 * 60000, "arrival uses the bounded server window observed by the real UI");
    sc.facts.arrival_window = window;
    while (Date.now() < opens + 500) await sleep(Math.min(15000, Math.max(1, opens + 500 - Date.now())));
    if (pair === "A") {
      await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName });
      await P.tripButton(pwa.page, "Выехал");
      await waitDb(() => book(absent.ride)?.driver_phase === "departed", "driver departure saved by real action");
      await P.tripButton(pwa.page, "Подъезжаю");
    } else {
      await AF.driverCardAction({ passenger: passenger.firstName, action: /^Открыть поездку$/ });
      await A.scrollTap(/^Я выехал$/);
      await waitDb(() => book(absent.ride)?.driver_phase === "departed", "driver departure saved by real action");
      await A.scrollTap(/^Подъезжаю$/);
    }
    await waitDb(() => !!book(absent.ride)?.arriving_at, "arrival saved by real driver action");
  });
  await sc.step("wait for real no-show deadline; no clock or database rewriting", async () => {
    const from = await wireFrom();
    if (pair === "A") await P.openDriverCabinet(pwa.page); else { await AF.goHome(); await AF.openDriverCabinet(); }
    const window = await observedDriverWindow(absent, from), ready = window.no_show_at_ms;
    sc.facts.no_show_window = window;
    sc.check(Number.isFinite(ready), "real UI response contains a finite server no-show deadline");
    const waitMs = ready - Date.now() + 1500;
    sc.check(Number.isFinite(waitMs) && waitMs < 25 * 60000, "real no-show deadline is bounded", { wait_seconds: Math.max(0, Math.ceil(waitMs / 1000)) });
    if (pair === "A") {
      await P.openDriverCabinet(pwa.page);
      const button = P.driverRow(pwa.page, passenger.firstName).getByRole("button", { name: /^Не пришёл$/ });
      if (waitMs > 2000) sc.check(await button.isDisabled(), "no-show control is disabled before real deadline");
    }
    while (Date.now() < ready + 1500) { log("wait.real_no_show", { seconds_left: Math.ceil((ready - Date.now()) / 1000) }); await sleep(Math.min(15000, Math.max(1, ready + 1500 - Date.now()))); }
  });
  await sc.step("driver marks no-show and seat is returned", async () => {
    if (pair === "A") { await P.openDriverCabinet(pwa.page); await P.click(pwa.page, P.driverRow(pwa.page, passenger.firstName).getByRole("button", { name: /^Не пришёл$/ }), "no-show"); await P.click(pwa.page, P.btn(pwa.page, /^Да, не пришёл$/), "confirm no-show"); }
    else await AF.driverCardAction({ passenger: passenger.firstName, action: /^Не пришёл$/, confirm: /^Да, не пришёл$/ });
    await waitDb(() => book(absent.ride)?.status === "cancelled" && book(absent.ride)?.cancel_reason === "no_show", "real no-show persisted");
    sc.check(q1("SELECT seats_left FROM ride WHERE id = ?", absent.ride.id).seats_left === 1, "no-show restores one seat"); oneBooking(absent);
  });
  sc.check(q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", userIdOf(driver)).n === initialOwnRideCount + created.length, "final own ride count equals baseline plus exactly the UI publications");
  sc.facts.own_ride_counts = { before: initialOwnRideCount, publications: created.length, after: q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", userIdOf(driver)).n };
  sc.facts.created = created; sc.facts.local_execution = "passed";
}, async () => {
  sc.facts.created = created;
  if (extra) await extra.ctx.close();
  if (pwa) await pwa.ctx.close();
});
