// node tools/rideshare_e2e/s04.mjs A|B <unique-tag>
// Real response loss AFTER the API commits, manual retry, network cut and process death.
import { Scenario, runScenario } from "./lib/scenario.mjs";
import { A, AF, ACC, q, q1, sleep, pwaClient, androidAs, userIdOf, lastRideOf, standHealthy, ufaClock, wireFrom, wireSince, fault } from "./lib/stand.mjs";
import * as P from "./lib/pwa-flows.mjs";
import { until } from "./lib/request-flows.mjs";
const pair = process.argv[2], tag = process.argv[3];
if (!["A", "B"].includes(pair) || !tag) throw new Error("Expected A|B and a unique run tag");
const sc = new Scenario(`W1-E2E-04-${pair}-${tag}`, { requirement: "W1-E2E-04", pair: pair === "A" ? "android-passenger+pwa-driver" : "android-driver+pwa-passenger" });
const route = { from: "Уфа", to: "Мелеуз", stops: ["Стерлитамак", "Салават"], pickup: "Стерлитамак", dropoff: "Салават" };
const routeText = `${route.from} → ${route.to}`;
const price = Number(process.env.RSACC_PRICE || (pair === "A" ? 541 : 542));
const driver = pair === "A" ? ACC.pwaDriver : ACC.andDriver;
const passenger = pair === "A" ? ACC.andPassenger : ACC.pwaPassenger;
let pwa, proxyOwned = false;
await runScenario(sc, async () => {
  await standHealthy(sc); proxyOwned = true;
  await fault({ mode: "up" }); await fault({ mode: "clear" });
  pwa = await pwaClient(pair === "A" ? driver : passenger); sc.pages = [pwa.page];
  await androidAs(pair === "A" ? passenger : driver);
  const departure = ufaClock(20), pid = userIdOf(passenger);
  const ridesBefore = q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", userIdOf(driver)).n;
  await sc.step("publish through the real driver client", async () => {
    if (pair === "A") await P.publishRide(pwa.page, { ...route, departAt: departure.date, seats: 3, price });
    else await AF.publishRide({ ...route, ...departure, seats: 3, price });
  });
  const ride = lastRideOf(driver); sc.facts.ride_id = ride.id;
  sc.check(q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", userIdOf(driver)).n === ridesBefore + 1, "one actual new ride");
  const stored = () => q("SELECT * FROM booking WHERE ride_id=? AND passenger_id=? ORDER BY id", ride.id, pid);
  await sc.step("select both stops through the passenger client", async () => {
    if (pair === "A") {
      await AF.searchAndOpen({ from: route.pickup, to: route.dropoff, cardText: `${price} ₽` });
      await AF.tapOptionAfter("Где садишься?", [route.pickup]); await AF.tapOptionAfter("Где выходишь?", [route.dropoff]);
    } else {
      await P.searchRide(pwa.page, { from: route.pickup, to: route.dropoff, routeText, cardText: `${price} ₽` });
      await pwa.page.locator(`input[name^=pickup-stop][value="${route.pickup}"]`).check();
      await pwa.page.locator(`input[name^=dropoff-stop][value="${route.dropoff}"]`).check();
    }
  });
  const from = await wireFrom();
  await fault({ mode: "drop", method: "POST", path: "^/bookings$", count: 1, tag: `W1-04-${pair}-lost-answer` });
  await sc.step("server commits booking but proxy drops the response", async () => {
    if (pair === "A") await A.tapText("Забронировать место");
    else await P.click(pwa.page, P.btn(pwa.page, /^Забронировать/), "book once before response loss");
    await until(() => wireSince(from), (events) => events.some((e) => e.method === "POST" && e.path === "/bookings" && e.outcome === "response_dropped_after_server" && e.status === 200), "real server response dropped");
    sc.check(stored().length === 1 && stored()[0].status === "pending", "server committed one pending booking despite missing response");
    if (pair === "A") {
      // On Android the failure is a Toast; the stable contract is the still-open form,
      // its explicit retry action and the retained stops, with no false success screen.
      const retry = await A.waitFor("Забронировать место", { timeout: 20000 });
      sc.check(retry.enabled !== "false", "Android returns an explicit enabled retry after response loss");
      const texts = (await AF.screenTexts(4)).join("\n");
      sc.check(texts.includes(route.pickup) && texts.includes(route.dropoff), "Android preserves both choices before manual retry");
      await A.swipeDown(0.5); await A.swipeDown(0.5); A.screencap("lost-response-before-manual-retry");
    } else {
      await pwa.page.locator(".auth__error").filter({ visible: true }).first().waitFor({ timeout: 25000 });
      sc.check(await pwa.page.locator(`input[name^=pickup-stop][value="${route.pickup}"]`).isChecked() && await pwa.page.locator(`input[name^=dropoff-stop][value="${route.dropoff}"]`).isChecked(), "PWA preserves both choices with visible network error");
      await P.shot(pwa.page, "lost-response-before-manual-retry.png");
    }
    await sleep(1500);
    const posts = (await wireSince(from)).filter((e) => e.method === "POST" && e.path === "/bookings");
    sc.check(posts.length === 1, "no automatic replay before the person chooses retry", { observed_posts: posts.length });
  });
  await sc.step("person retries once and reaches the same booking", async () => {
    if (pair === "A") { await A.scrollTap("Забронировать место"); await A.waitFor("Отменить бронь", { timeout: 25000 }); }
    else { await P.click(pwa.page, P.btn(pwa.page, /^Забронировать/), "explicit manual retry"); await pwa.page.waitForURL(/\/(booking|trip)\/\d+$/, { timeout: 25000 }); }
    const rows = stored();
    sc.check(rows.length === 1 && rows[0].pickup_stop === route.pickup && rows[0].dropoff_stop === route.dropoff, "manual retry retains exactly one booking and both stops");
    sc.check(q1("SELECT seats_left FROM ride WHERE id=?", ride.id).seats_left === 2, "manual retry occupies exactly one seat");
    const posts = (await wireSince(from)).filter((e) => e.method === "POST" && e.path === "/bookings");
    sc.check(posts.length === 2 && posts[1].status === 200, "wire log shows the original request and one successful explicit retry");
  });
  const booking = stored()[0]; sc.facts.booking_id = booking.id;
  await sc.step("driver confirms and both clients open the active trip", async () => {
    if (pair === "A") {
      await P.driverDecide(pwa.page, { passenger: passenger.firstName });
      await AF.openMyBooking({ route: routeText, status: "Подтверждена", cardText: `${price} ₽` });
      await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName });
    } else {
      await AF.driverDecide({ passenger: passenger.firstName, action: "Подтвердить" });
      await P.openMyTrip(pwa.page, { route: routeText, cardText: `${price} ₽` });
      await AF.driverCardAction({ passenger: passenger.firstName, action: /^Открыть поездку$/ });
    }
  });
  await sc.step("driver marks actual boarding", async () => {
    if (pair === "A") await P.driverBoard(pwa.page, { passenger: passenger.firstName }); else await AF.driverBoard({ passenger: passenger.firstName });
    await until(() => stored()[0], (r) => r.status === "onboard", "boarded stage saved");
    // Open both active screens while online so each client has its own real saved passport.
    if (pair === "A") { await AF.openMyBooking({ route: routeText, status: "В пути", cardText: `${price} ₽` }); await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName }); }
    else { await P.openMyTrip(pwa.page, { route: routeText, cardText: `${price} ₽` }); await AF.driverCardAction({ passenger: passenger.firstName, action: /^Открыть поездку$/ }); }
    await sleep(2500);
  });
  await sc.step("real network loss, Android process death and PWA reload", async () => {
    await fault({ mode: "down", tag: `W1-04-${pair}-network-off` });
    const beforePid = A.pid(); A.forceStop(); A.launch(); await sleep(3000);
    sc.check(beforePid && A.pid() && A.pid() !== beforePid, "Android process genuinely died and restarted");
    await pwa.page.reload({ waitUntil: "domcontentloaded" }); await sleep(2500);
    if (pair === "A") await AF.openMyBooking({ route: routeText, cardText: `${price} ₽` });
    else {
      // The driver can reach the locally saved active trip from the restored cabin.
      await AF.driverCardAction({ passenger: passenger.firstName, action: /^Открыть поездку$/ });
    }
    await A.waitFor(/Офлайн|Нет сети/, { timeout: 30000 });
    await pwa.page.getByText(/Нет сети|Офлайн|сохранённ/).first().waitFor({ timeout: 30000 });
    const androidText = (await AF.screenTexts(5)).join("\n"), pwaText = await pwa.page.locator("body").innerText();
    sc.check(androidText.includes(route.pickup) && androidText.includes(route.dropoff), "Android saved passport keeps both stops after offline process death");
    sc.check(pwaText.includes(route.pickup) && pwaText.includes(route.dropoff), "PWA saved passport keeps both stops after offline reload");
    sc.check(/В пути|Пассажир в машине|Едем/.test(androidText) && /В пути|Пассажир в машине|Едем|Ты в машине/.test(pwaText), "both clients preserve the actual onboard stage offline");
    A.screencap("offline-after-process-death"); await P.shot(pwa.page, "offline-after-reload.png");
  });
  await sc.step("restore network and finish without duplicate or stage rollback", async () => {
    await fault({ mode: "up" }); await sleep(3000); await pwa.page.reload();
    if (pair === "A") { await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName }); await P.tripButton(pwa.page, "Завершить поездку"); }
    else await AF.driverComplete({ passenger: passenger.firstName });
    await until(() => stored()[0], (r) => r.status === "done", "done after recovery");
    const rows = stored();
    sc.check(rows.length === 1 && rows[0].id === booking.id && rows[0].pickup_stop === route.pickup && rows[0].dropoff_stop === route.dropoff, "same single completed booking and same stops after full recovery");
    sc.check(q1("SELECT COUNT(*) n FROM payment WHERE booking_id=?", booking.id).n === 0, "network recovery creates no duplicate payment");
    sc.check(q1("SELECT COUNT(*) n FROM ride WHERE driver_id=?", userIdOf(driver)).n === ridesBefore + 1, "network recovery creates no extra ride");
  });
}, async () => {
  if (proxyOwned) { await fault({ mode: "up" }); await fault({ mode: "clear" }); }
  if (pwa) await pwa.ctx.close();
});
