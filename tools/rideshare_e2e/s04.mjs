// node tools/rideshare_e2e/s04.mjs A|B <unique-tag>
// Real response loss AFTER the API commits, manual retry, network cut and process death.
import { Scenario, runScenario } from "./lib/scenario.mjs";
import { A, AF, ACC, q, q1, sleep, pwaClient, androidAs, userIdOf, lastRideOf, standHealthy, ufaClock, wireFrom, wireSince, fault } from "./lib/stand.mjs";
import * as P from "./lib/pwa-flows.mjs";
import { until } from "./lib/request-flows.mjs";
import { assertSavedOwnTripReady } from "./lib/offline-ready.mjs";
const pair = process.argv[2], tag = process.argv[3];
if (!["A", "B"].includes(pair) || !tag) throw new Error("Expected A|B and a unique run tag");
const sc = new Scenario(`W1-E2E-04-${pair}-${tag}`, { requirement: "W1-E2E-04", pair: pair === "A" ? "android-passenger+pwa-driver" : "android-driver+pwa-passenger" });
const route = { from: "Уфа", to: "Мелеуз", stops: ["Стерлитамак", "Салават"], pickup: "Стерлитамак", dropoff: "Салават" };
const routeText = `${route.from} → ${route.to}`;
const price = Number(process.env.RSACC_PRICE || (pair === "A" ? 541 : 542));
const driver = pair === "A" ? ACC.pwaDriver : ACC.andDriver;
const passenger = pair === "A" ? ACC.andPassenger : ACC.pwaPassenger;
let pwa, proxyOwned = false;
async function warmOwnNativeTrip(booking, suffix) {
  const actor = pair === "A" ? passenger : driver, cursor = await wireFrom();
  // A fresh process prevents an earlier in-memory details card from satisfying this readiness UI.
  A.forceStop(); A.launch();
  A.adb(["shell", "am", "start", "-W", "-n", `${A.PKG}/.MainActivity`, "--es", "type", "booking", "--es", "id", String(booking.id),
    "--es", "recipient_user_id", String(userIdOf(actor)), "--es", "yuldash_navigation_delivery_id", `warm-${pair}-${tag}-${suffix}-${Date.now()}`]);
  if (pair === "A") {
    await A.waitFor("Детали поездки", { timeout: 30000 });
    await A.scrollTap(/^Открыть поездку$/, { maxBottom: 0.96 });
  }
  await A.waitFor("Моя поездка", { timeout: 30000 });
  await A.waitFor(/В пути|Пассажир в машине|Едем/, { timeout: 30000 });
  // This exact segment is rendered only from returned full details, after the snapshot write attempt.
  const segment = `Участок: ${route.pickup} → ${route.dropoff}`;
  await A.scrollTo(segment, { maxBottom: 0.96, max: 12 });
  await until(() => wireSince(cursor), events => events.some(e => e.client === "android" && e.method === "GET" && e.path === `/bookings/${booking.id}/details` && e.status === 200), "native online own full details before API loss", 30000);
  return { booking_id: booking.id, actor_id: userIdOf(actor), status: 200, client: "android", typed_ui_segment: segment };
}
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
    if (pair === "A") { await A.scrollTap("Забронировать место", { maxBottom: 0.96 }); await A.waitFor("Отменить бронь", { timeout: 25000 }); }
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
    sc.facts.android_warm_details_before_probe = await warmOwnNativeTrip(booking, "before-readiness");
    sc.facts.android_durable_readiness = await assertSavedOwnTripReady(sc, {
      bookingId: booking.id, actor: pair === "A" ? passenger : driver, role: pair === "A" ? "passenger" : "driver", hasStops: true,
    }, "before-api-cut");
    sc.facts.android_warm_details = await warmOwnNativeTrip(booking, "after-readiness");
    sc.check(stored()[0].status === "onboard", "same exact booking remains onboard after the Android full-details warm read");
  });
  await sc.step("real network loss, Android process death and PWA reload", async () => {
    await fault({ mode: "down", tag: `W1-04-${pair}-network-off` });
    await pwa.ctx.setOffline(true);
    const beforePid = A.pid(); A.forceStop(); A.launch(); await sleep(3000);
    sc.check(beforePid && A.pid() && A.pid() !== beforePid, "Android process genuinely died and restarted");
    await pwa.page.reload({ waitUntil: "domcontentloaded" }); await sleep(2500);
    // Network-backed lists are not an offline cache. Use the existing protected
    // booking navigation boundary; the product must independently validate its
    // own saved owner/session/booking before displaying a read-only snapshot.
    const nativeActor = pair === "A" ? passenger : driver;
    A.adb(["shell", "am", "start", "-W", "-n", `${A.PKG}/.MainActivity`, "--es", "type", "booking", "--es", "id", String(booking.id),
      "--es", "recipient_user_id", String(userIdOf(nativeActor)), "--es", "yuldash_navigation_delivery_id", `offline-${pair}-${tag}-${Date.now()}`]);
    await A.waitFor("Последние сохранённые данные", { timeout: 120000 });
    await pwa.page.getByText(/Нет сети|Офлайн|сохранённ/).first().waitFor({ timeout: 30000 });
    const nativeLabels = new Set();
    for (let page = 0; page < 5; page++) {
      for (const node of A.dump()) for (const value of [node.text, node["content-desc"]]) if (value) nativeLabels.add(value);
      if (page < 4) await A.swipeUp(0.45);
    }
    const androidText = [...nativeLabels].join("\n"), pwaText = await pwa.page.locator("body").innerText();
    sc.check(androidText.includes(route.pickup) && androidText.includes(route.dropoff), "Android saved passport keeps both stops after offline process death");
    sc.check(pwaText.includes(route.pickup) && pwaText.includes(route.dropoff), "PWA saved passport keeps both stops after offline reload");
    sc.check(androidText.includes("Последнее состояние: Пассажир сел") && androidText.includes(pair === "A" ? "Ты пассажир" : "Ты водитель"), "Android displays its exact saved onboard stage and own role offline");
    sc.check([...nativeLabels].some(label => /^Сохранено:\s*\S/.test(label)), "Android shows the saved-information timestamp");
    sc.check(androidText.includes("Показываем сохранённую поездку. Состояние могло измениться."), "Android explicitly labels the snapshot as last known information");
    const originalCode = String(booking.boarding_code ?? "").trim();
    sc.check(![...nativeLabels].some(label => [driver.phone, passenger.phone].some(phone => label.replace(/\D/g, "").includes(phone.replace(/\D/g, "")))
      || (originalCode.length > 0 && label.includes(originalCode))), "Android read-only snapshot exposes no phone or old boarding code");
    sc.check(![...nativeLabels].some(label => /^(Позвонить|Написать|Сел|Пассажир сел|Я выехал|Подъезжаю|Завершить|Завершить поездку|Отменить бронь|Отменить поездку|Я ещё не сел|Я в машине|Поделиться поездкой с близким|Исправить посадку(?: · .*)?)$/.test(label)), "Android saved mode offers no live booking or contact actions throughout the scanned content");
    sc.facts.android_offline_entry = "supported owner-bound booking link; explicit read-only last-known snapshot, not offline trip lists or automatic resume";
    sc.facts.android_queue_scope = "The actual pre-cut instrumentation proved the old queue readable and empty; legacy OutboxAutoFlush remains unchanged. TCP cuts happen before HTTP parsing, so the wire log alone cannot prove zero offline HTTP attempts.";
    const savedStage = await pwa.page.evaluate(({id, owner}) => {
      const pass = JSON.parse(localStorage.getItem(`yuldash.tripPass.${id}`) || "null");
      const prefix = pass ? `yuldash.tripPass.${id}.onboard.${pass.sessionGeneration}.` : "missing:";
      return { owner_matches: pass?.ownerId === owner, booking_matches: pass?.bookingId === id,
        onboard_marker: Object.keys(localStorage).some(k => k.startsWith(prefix) && !!localStorage.getItem(k)),
        obsolete_boarding_value_removed: pass?.boardingCode === "" };
    }, { id: booking.id, owner: userIdOf(pair === "A" ? driver : passenger) });
    sc.check(Object.values(savedStage).every(Boolean), "PWA real saved state retains its owner and onboard protection after offline reload", savedStage);
    sc.check(stored()[0].status === "onboard", "server still has the same onboard booking during the network cut");
    sc.check(await pwa.page.getByText(String(booking.boarding_code), { exact: true }).count() === 0, "offline onboard pass does not show the obsolete boarding code");
    sc.check(await pwa.page.getByRole("button", { name: /^(Завершить поездку|Забронировать место|Принять)$/ }).count() === 0, "saved fallback offers no live trip actions");
    sc.facts.pwa_offline_stage = "stored onboard protection verified read-only; fallback deliberately shows no live-stage badge";
    A.screencap("offline-after-process-death"); await P.shot(pwa.page, "offline-after-reload.png");
  });
  await sc.step("restore network and finish without duplicate or stage rollback", async () => {
    await fault({ mode: "up" }); await pwa.ctx.setOffline(false); await sleep(3000); await pwa.page.reload();
    const nativeRecovery = await wireFrom();
    await A.scrollTap(/^Повторить$/, { maxBottom: 0.96 });
    await A.waitFor("Последние сохранённые данные", { gone: true, timeout: 30000 });
    if (pair === "A") {
      await A.waitFor("Детали поездки", { timeout: 30000 });
      await A.scrollTap(/^Открыть поездку$/, { maxBottom: 0.96 });
    }
    await A.waitFor("Моя поездка", { timeout: 30000 });
    await A.waitFor(/В пути|Пассажир в машине|Едем/, { timeout: 30000 });
    const recovered = await wireSince(nativeRecovery);
    sc.check(recovered.some(e => e.client === "android" && e.method === "GET" && e.path === `/bookings/${booking.id}/role` && e.status === 200), "Android explicitly retried and obtained a fresh own trip state before live navigation");
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
  if (pwa) await pwa.ctx.setOffline(false);
  if (pwa) await pwa.ctx.close();
});
