// W1-E2E-01 — main trip through both real clients.
// Pair A: Android passenger + PWA driver.  Pair B: Android driver + PWA passenger.
// node s01.mjs A|B [runTag]
import { Scenario, runScenario } from "./lib/scenario.mjs";
import * as P from "./lib/pwa-flows.mjs";
import { A, AF, ACC, q, q1, log, sleep, pwaClient, androidAs, userIdOf, lastRideOf, standHealthy, ufaClock } from "./lib/stand.mjs";

const pair = process.argv[2] || "A";
const tag = process.argv[3] || new Date().toISOString().slice(11, 19).replace(/:/g, "");
A.setSerial(process.env.ANDROID_SERIAL || "emulator-5560");
const sc = new Scenario(`W1-E2E-01-${pair}-${tag}`, { requirement: "W1-E2E-01", pair: pair === "A" ? "android-passenger+pwa-driver" : "android-driver+pwa-passenger" });
const PRICE = (pair === "A" ? 510 : 520) + Number(process.env.PRICE_OFFSET || 0);
const R = { from: "Уфа", to: "Мелеуз", stops: ["Стерлитамак", "Салават"], pickup: "Стерлитамак", dropoff: "Салават" };
const MSG1 = (pair === "A" ? "Здравствуйте! Я у автовокзала в Стерлитамаке" : "Добрый день! Буду у автовокзала Стерлитамака") + `, рейс за ${PRICE} ₽`;
const MSG2 = (pair === "A" ? "Вижу вас, подъезжаю через пять минут" : "Хорошо, жду вас у входа") + `, рейс за ${PRICE} ₽`;
let pwa;

function booking(rideId, passengerId) { return q1("SELECT * FROM booking WHERE ride_id = ? AND passenger_id = ? ORDER BY id DESC LIMIT 1", rideId, passengerId); }

await runScenario(sc, async () => {
  await standHealthy(sc);
  const driverAcc = pair === "A" ? ACC.pwaDriver : ACC.andDriver;
  const passAcc = pair === "A" ? ACC.andPassenger : ACC.pwaPassenger;
  pwa = await pwaClient(pair === "A" ? ACC.pwaDriver : ACC.pwaPassenger);
  const page = pwa.page;
  sc.pages = [page];
  await sc.step("android account", () => androidAs(pair === "A" ? ACC.andPassenger : ACC.andDriver));
  const depart = ufaClock(20);

  // 1. Publication
  await sc.step("publish ride with two stops", async () => {
    if (pair === "A") await P.publishRide(page, { from: R.from, to: R.to, pickupChip: "Автовокзал Южный", departAt: depart.date, seats: 3, price: PRICE, stops: R.stops });
    else await AF.publishRide({ from: R.from, to: R.to, stops: R.stops, hh: depart.hh, mm: depart.mm, day: depart.day, seats: 3, price: PRICE });
  });
  const ride = lastRideOf(driverAcc);
  sc.facts.ride_id = ride.id;
  sc.check(ride.price === PRICE && ride.waypoints === "Стерлитамак | Салават" && ride.seats_left === 3 && ride.status === "active",
    "ride stored with both stops, 3 seats, price", { ride_id: ride.id, waypoints: ride.waypoints, seats_left: ride.seats_left });

  // 2-3. Search by stops, choose pickup and dropoff, book
  await sc.step("search by stops and open the card", async () => {
    if (pair === "A") {
      const card = await AF.searchAndOpen({ from: R.pickup, to: R.dropoff, cardText: `${PRICE} ₽` });
      sc.facts.android_card = card;
      const nodes = A.dump();
      sc.check(!A.find(nodes, "Позвонить"), "Android: no call button before booking");
      const note = await A.scrollTo(/^Телефон откроется после подтверждения/).catch(() => null);
      sc.check(!!note, "Android: «phone opens after confirmation» note on ride details");
    } else {
      const seg = await P.searchRide(page, { from: R.pickup, to: R.dropoff, routeText: "Уфа → Мелеуз", cardText: `${PRICE} ₽` });
      sc.check(/Твой участок: Стерлитамак → Салават/.test(seg), "PWA card shows the passenger segment", { seg: seg.replace(/\s+/g, " ").slice(0, 120) });
    }
  });
  await sc.step("choose pickup/dropoff and book", async () => {
    if (pair === "A") await AF.chooseStopsAndBook({ pickup: R.pickup, dropoff: R.dropoff });
    else await P.bookRide(page, { pickup: R.pickup, dropoff: R.dropoff });
  });
  const pid = userIdOf(passAcc);
  let b = booking(ride.id, pid);
  sc.facts.booking_id = b?.id;
  sc.check(b && b.status === "pending" && b.pickup_stop === R.pickup && b.dropoff_stop === R.dropoff && b.seats === 1,
    "booking pending with chosen pickup and dropoff", { booking_id: b?.id, status: b?.status, pickup: b?.pickup_stop, dropoff: b?.dropoff_stop });

  // contacts closed before confirmation (both sides)
  await sc.step("contacts closed while pending", async () => {
    if (pair === "A") {
      await P.openDriverCabinet(page);
      const telLinks = await page.locator('a[href^="tel:"]').count();
      sc.check(telLinks === 0, "PWA driver: no phone link for a pending booking", { telLinks });
      await P.shot(page, `pwa-driver-pending.png`);
    } else {
      const telLinks = await page.locator('a[href^="tel:"]').count();
      sc.check(telLinks === 0, "PWA passenger: no driver phone link while pending", { telLinks });
    }
  });

  // 4. Confirmation by the driver
  await sc.step("driver confirms", async () => {
    if (pair === "A") await P.driverDecide(page, { passenger: passAcc.firstName, action: "Подтвердить" });
    else await AF.driverDecide({ passenger: passAcc.firstName, action: "Подтвердить" });
  });
  b = booking(ride.id, pid);
  sc.check(b.status === "confirmed" && !!b.confirmed_at, "booking confirmed in DB", { status: b.status });

  // 5. Passenger sees confirmation, phone and boarding code
  await sc.step("passenger sees confirmation, phone and boarding code", async () => {
    if (pair === "A") {
      await AF.openMyBooking({ route: "Уфа → Мелеуз", status: "Подтверждена", action: /^Открыть поездку$/, cardText: `${PRICE} ₽` });
      await A.waitFor("Позвонить", { timeout: 30000 });
      const code = await A.waitFor(/^\d{6}$/, { timeout: 20000 });
      sc.check(code.text === b.boarding_code, "Android shows the same boarding code as the server", {});
      A.screencap("android-passenger-confirmed-trip");
    } else {
      await P.openMyTrip(page, { route: "Уфа → Мелеуз", cardText: `${PRICE} ₽` });
      await page.getByText("Код посадки").first().waitFor({ timeout: 30000 });
      const shown = await page.locator("text=/^\\d{6}$/").first().innerText();
      sc.check(shown.trim() === b.boarding_code, "PWA shows the same boarding code as the server");
      const tel = await page.locator('a[href^="tel:"]').count();
      sc.check(tel >= 1, "PWA passenger: driver phone link after confirmation", { tel });
      await P.shot(page, `pwa-passenger-confirmed.png`);
    }
  });

  // 6. Chat both ways
  await sc.step("chat: passenger → driver → passenger", async () => {
    if (pair === "A") {
      await A.tapText("Написать");
      await AF.chatSend(MSG1);
      await P.chatOpenFromInbox(page, { name: passAcc.firstName, text: MSG1 });
      await P.chatSee(page, MSG1);
      const header = await page.locator("body").innerText();
      sc.check(header.split("\n").slice(0, 6).some((l) => l.trim() === passAcc.firstName), "PWA driver chat header names the passenger (parity with Android)");
      await P.chatSend(page, MSG2);
      await AF.chatSee(MSG2);
      A.screencap("android-chat");
      await P.shot(page, `pwa-chat.png`);
    } else {
      await P.tripButton(page, "Написать");
      await P.chatSend(page, MSG1);
      await AF.chatOpenFromInbox({ name: passAcc.firstName, text: MSG1 });
      await AF.chatSee(MSG1);
      await AF.chatSend(MSG2);
      await P.chatSee(page, MSG2);
      A.screencap("android-chat");
      await P.shot(page, `pwa-chat.png`);
    }
  });
  const msgs = q("SELECT sender_id, text FROM message WHERE booking_id = ? ORDER BY id", b.id);
  sc.check(msgs.length === 2 && msgs[0].text === MSG1 && msgs[1].text === MSG2, "exactly two chat messages stored in order", { count: msgs.length });

  // 7. Boarding marked by the driver
  await sc.step("driver marks boarded", async () => {
    if (pair === "A") await P.driverBoard(page, { passenger: passAcc.firstName });
    else await AF.driverBoard({ passenger: passAcc.firstName });
  });
  b = booking(ride.id, pid);
  sc.check(b.status === "onboard" && !!b.onboard_at, "booking onboard in DB", { status: b.status });

  // 8. Restart both clients: Android process death, PWA reload; stage preserved
  await sc.step("restart both clients, stage preserved", async () => {
    const before = A.pid();
    A.forceStop();
    await sleep(1000);
    A.launch();
    await sleep(3000);
    const after = A.pid();
    sc.check(before && after && before !== after, "Android process really restarted", { before, after });
    if (pair === "A") {
      await AF.openMyBooking({ route: "Уфа → Мелеуз", status: "В пути", cardText: `${PRICE} ₽` });
      A.screencap("android-after-restart");
      await page.reload(); await sleep(2500);
      await P.openDriverCabinet(page);
      await P.driverRow(page, passAcc.firstName).filter({ hasText: "Пассажир в машине" }).first().waitFor({ timeout: 20000 });
    } else {
      await AF.openDriverCabinet();
      await A.scrollTo(/Пассажир в машине|В пути/);
      A.screencap("android-after-restart");
      await page.reload(); await sleep(2500);
      await P.openMyTrip(page, { route: "Уфа → Мелеуз", cardText: `${PRICE} ₽` });
      await page.getByText(/В пути|Едем|Ты в машине|Водитель отметил посадку/).first().waitFor({ timeout: 20000 });
      await P.shot(page, "pwa-passenger-after-reload.png");
    }
  });

  // 9. Completion
  await sc.step("driver completes the trip", async () => {
    if (pair === "A") { await P.driverOpenTrip(page, { passenger: passAcc.firstName }); await P.tripButton(page, "Завершить поездку"); }
    else await AF.driverComplete({ passenger: passAcc.firstName });
  });
  b = booking(ride.id, pid);
  sc.check(b.status === "done" && !!b.done_at, "booking done in DB", { status: b.status });

  // 10-11. Two ratings and both receipts
  await sc.step("passenger rates driver and opens receipt", async () => {
    if (pair === "A") {
      const seen = await AF.passengerRateAndReceipt({ stars: 5, route: "Уфа → Мелеуз", cardText: `${PRICE} ₽`, expect: ["Стерлитамак → Салават", `${PRICE} ₽`, driverAcc.firstName] });
      sc.check(true, "Android receipt shows segment, price and driver", { lines: seen.length });
    }
    else await P.passengerRateAndReceipt(page, { stars: 5, bookingId: b.id });
  });
  await sc.step("driver rates passenger and opens receipt", async () => {
    if (pair === "A") { await P.driverRatePassenger(page, { bookingId: b.id, stars: 4 }); await P.driverReceipt(page, { bookingId: b.id }); }
    else await AF.driverRateAndReceipt({ passenger: passAcc.firstName, stars: 4 });
  });
  const ratings = q("SELECT rater_id, ratee_id, stars FROM rating WHERE booking_id = ? ORDER BY id", b.id);
  sc.check(ratings.length === 2 && ratings.some((r) => r.stars === 5 && r.ratee_id === userIdOf(driverAcc)) && ratings.some((r) => r.stars === 4 && r.ratee_id === pid),
    "two ratings stored: passenger→driver 5, driver→passenger 4", { ratings: ratings.map((r) => r.stars) });

  // 12. History on both sides
  await sc.step("history on both sides", async () => {
    if (pair === "A") { await AF.passengerHistory({ route: "Уфа → Мелеуз" }); await P.driverArchive(page, { route: "Уфа → Мелеуз" }); }
    else { await AF.driverArchive({ route: "Уфа → Мелеуз" }); await P.passengerHistory(page, { route: "Уфа → Мелеуз" }); }
  });
  const rideAfter = q1("SELECT seats_left, status FROM ride WHERE id = ?", ride.id);
  const others = q("SELECT COUNT(*) n FROM booking WHERE ride_id = ?", ride.id)[0].n;
  sc.check(others === 1, "no extra bookings on the ride", { bookings: others });
  sc.facts.ride_after = rideAfter;
}, async () => { if (pwa) await pwa.ctx.close(); });
