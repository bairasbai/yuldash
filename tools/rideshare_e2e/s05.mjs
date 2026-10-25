// W1-E2E-05: contacts, foreign access, session change, public link/revocation/completion.
// node tools/rideshare_e2e/s05.mjs A|B [runTag]
// RS_E2E05_EXPIRED_FIXTURE: root-owned synthetic historical fixture in the SAME temporary DB.
// Its creation is fixture setup, NEVER a claimed user/UI action. No clocks or rows are rewritten here.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { Scenario, runScenario } from "./lib/scenario.mjs";
import { A, AF, ACC, q1, sleep, pwaClient, androidAs, userIdOf, lastRideOf, standHealthy, ufaClock, wireFrom, wireSince } from "./lib/stand.mjs";
import { API, DB_PATH } from "./lib/common.mjs";
import { openProfile, pwaLogin } from "./lib/pwa.mjs";
import * as P from "./lib/pwa-flows.mjs";

const pair = process.argv[2] || "A";
if (!["A", "B"].includes(pair)) throw new Error("Expected pair A or B");
const tag = process.argv[3] || new Date().toISOString().replace(/[^0-9]/g, "");
const sc = new Scenario(`W1-E2E-05-${pair}-${tag}`, { requirement: "W1-E2E-05", pair: pair === "A" ? "android-passenger+pwa-driver" : "android-driver+pwa-passenger" });
A.setSerial(process.env.ANDROID_SERIAL || "emulator-5560");
const driver = pair === "A" ? ACC.pwaDriver : ACC.andDriver;
const passenger = pair === "A" ? ACC.andPassenger : ACC.pwaPassenger;
const nativeActor = pair === "A" ? passenger : driver;
const webActor = pair === "A" ? driver : passenger;
const foreignNative = pair === "A" ? ACC.andDriver : ACC.andPassenger;
const route = { from: "Уфа", to: "Мелеуз", stops: ["Стерлитамак", "Салават"], pickup: "Стерлитамак", dropoff: "Салават", title: "Уфа → Мелеуз" };
const price = (pair === "A" ? 701 : 702) + Number(process.env.PRICE_OFFSET || 0);
const contactName = `Близкий-${pair}-${tag}`;
let pwa, stranger, family, ride, booking;
const fresh = () => q1("SELECT * FROM booking WHERE id = ?", booking.id);
const utc = s => Date.parse(/[zZ]$|[+-]\d\d:\d\d$/.test(String(s)) ? String(s) : String(s) + "Z");
async function until(predicate, what, timeout = 20000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { if (await predicate()) return; await sleep(350); }
  sc.check(false, what);
}
async function androidTrip(id, actor) {
  // A hostile/stale booking link enters the real MainActivity navigation boundary.
  // This changes navigation only; it cannot create/confirm/edit a booking.
  A.adb(["shell", "am", "start", "-W", "-n", `${A.PKG}/.MainActivity`, "--es", "type", "booking", "--es", "id", String(id), "--es", "recipient_user_id", String(userIdOf(actor)), "--es", "yuldash_navigation_delivery_id", `${tag}-${Date.now()}`]);
  await sleep(1800);
}
async function passengerTrip() {
  if (pair === "A") await AF.openMyBooking({ route: route.title, cardText: `${price} ₽` });
  else await P.openMyTrip(pwa.page, { route: route.title, cardText: `${price} ₽` });
}
async function webTripWithObservedDetails(page, id) {
  let detail = null;
  const watch = async response => {
    if (!new RegExp(`/bookings/${id}/details(?:\\?|$)`).test(response.url()) || response.status() !== 200) return;
    try { detail = await response.json(); } catch {}
  };
  page.on("response", watch);
  try {
    await page.goto(P.PWA + `/trip/${id}`, { waitUntil: "domcontentloaded" });
    await until(() => detail != null, "real PWA trip load obtained its own details response");
    return detail;
  } finally { page.off("response", watch); }
}
function noSecretsInText(text, source) {
  const sensitive = [driver.phone, passenger.phone, String(booking.boarding_code || "")].filter(Boolean);
  const digits = String(text).replace(/[^0-9]/g, "");
  sc.check(!sensitive.some(v => digits.includes(v.replace(/[^0-9]/g, ""))), source + ": other participants' phone and boarding code are absent");
}
async function openActiveFamilyLink(link, shot) {
  const stateUrl = link.replace(/\/+$/, "") + "/state.json";
  const observed = family.page.waitForResponse(r => r.url() === stateUrl && r.status() === 200, { timeout: 20000 });
  await family.page.goto(link, { waitUntil: "domcontentloaded" });
  const response = await observed;
  const state = await response.json();
  sc.check(!response.request().headers().authorization, "independent public browser loaded the exact capability state without account authorization");
  sc.check(state.kind === "ride" && state.status === "wait" && state.passenger_first_name === passenger.firstName
    && state.from?.text === route.from && state.to?.text === route.to && state.car === null
    && typeof state.phase_text?.ru === "string" && state.phase_text.ru.length > 0
    && typeof state.phase_text?.ba === "string" && state.phase_text.ba.length > 0,
  "public state positively identifies this passenger, route and actual pre-departure phase without a live car");
  await family.page.locator("#title").filter({ hasText: `${passenger.firstName} · ${state.phase_text.ru}` }).waitFor();
  await family.page.locator("#route").filter({ hasText: route.title }).waitFor();
  sc.check(await family.page.locator("#title-ba").innerText() === state.phase_text.ba
    && !(await family.page.locator("#err").isVisible()) && !(await family.page.locator("#done").isVisible()),
  "public page actually rendered both phase labels and its route, not a loading or stale fallback");
  noSecretsInText(await family.page.locator("body").innerText(), "active public family page");
  await P.shot(family.page, shot);
}
async function addContact() {
  if (pair === "A") {
    await AF.goHome(); await AF.nav("Профиль"); await A.scrollTap("Доверенные контакты");
    await A.scrollTap("Добавить контакт");
    await A.typeInto("Имя", contactName); A.hideKeyboard();
    await A.typeInto("Телефон", "+79990301999"); A.hideKeyboard();
    await A.tapText(/^Добавить$/); await A.waitFor(contactName);
  } else {
    await pwa.page.goto(P.PWA + "/trusted", { waitUntil: "domcontentloaded" });
    await pwa.page.getByLabel("Имя", { exact: true }).fill(contactName);
    await pwa.page.getByLabel("Телефон", { exact: true }).fill("+79990301999");
    await P.click(pwa.page, P.btn(pwa.page, /^Добавить$/), "add synthetic trusted contact through UI");
    await pwa.page.getByText(contactName, { exact: true }).waitFor();
  }
  sc.check(q1("SELECT COUNT(*) n FROM trustedcontact WHERE user_id = ? AND name = ?", userIdOf(passenger), contactName).n === 1, "UI created exactly one own synthetic trusted contact");
}
async function share() {
  await passengerTrip();
  let link;
  if (pair === "A") {
    await A.scrollTap("Поделиться поездкой с близким");
    await A.waitFor("Кому отправить поездку"); await A.tapText(contactName);
    const node = await A.waitFor(/^https?:\/\/[^\s]+\/t\/[A-Za-z0-9_-]+$/);
    link = node.text;
    await A.tapText(/^Готово$/);
  } else {
    await P.click(pwa.page, P.btn(pwa.page, /^Поделиться поездкой с близким$/), "share trip with synthetic trusted contact");
    await P.click(pwa.page, pwa.page.getByRole("button").filter({ hasText: contactName }), "select synthetic trusted contact");
    link = await pwa.page.locator(".share-link__url").innerText();
  }
  const path = new URL(link).pathname;
  sc.check(/^\/t\/[A-Za-z0-9_-]{16,}$/.test(path), "UI revealed a capability link for the selected trip");
  // Product's public host is replaced with the explicit loopback stand, never production.
  return API + path;
}
async function revoke() {
  if (pair === "A") {
    await passengerTrip(); await A.scrollTap("Поделиться поездкой с близким");
    await A.waitFor(/^Отозвать$/); await A.tapText(/^Отозвать$/);
    await A.waitFor("Кому отправить поездку"); A.back();
  } else {
    await P.click(pwa.page, pwa.page.getByRole("button", { name: "Отозвать доступ", exact: true }), "revoke public trip link");
    await pwa.page.getByText("Ссылка активна", { exact: true }).waitFor({ state: "hidden" });
  }
}
async function pwaLogoutAndLogin(acc) {
  await pwa.page.goto(P.PWA + "/settings", { waitUntil: "domcontentloaded" });
  await pwa.page.getByText("Выйти из аккаунта", { exact: true }).click();
  await pwa.page.getByRole("button", { name: "Выйти", exact: true }).click();
  await pwa.page.waitForURL(/login|onboarding|map/);
  sc.check(await pwa.page.locator('a[href^="tel:"]').count() === 0, "logout removed old contact actions");
  await pwaLogin(pwa.page, acc);
}

await runScenario(sc, async () => {
  await standHealthy(sc); await androidAs(nativeActor);
  pwa = await pwaClient(webActor); sc.pages = [pwa.page];
  sc.facts.requirement_acceptance = "not_accepted_pending_independent_review";
  sc.facts.coverage = ["pending contacts", "confirmed contacts", "foreign booking and private stops", "account switch", "public family link", "revocation", "completion", "expired driver contact window"];
  sc.facts.authentication = "real login UI with explicitly synthetic local Telegram-bot stand; real Telegram provider remains external";
  const depart = ufaClock(20);
  await sc.step("publish and book own two-stop trip through real UI", async () => {
    const before = q1("SELECT COUNT(*) n FROM ride WHERE driver_id = ?", userIdOf(driver)).n;
    if (pair === "A") await P.publishRide(pwa.page, { from: route.from, to: route.to, stops: route.stops, departAt: depart.date, seats: 2, price });
    else await AF.publishRide({ from: route.from, to: route.to, stops: route.stops, hh: depart.hh, mm: depart.mm, day: depart.day, seats: 2, price });
    ride = lastRideOf(driver);
    sc.check(ride?.price === price && q1("SELECT COUNT(*) n FROM ride WHERE driver_id = ?", userIdOf(driver)).n === before + 1, "exactly one own ride published");
    if (pair === "A") { await AF.searchAndOpen({ from: route.pickup, to: route.dropoff, cardText: `${price} ₽` }); await AF.chooseStopsAndBook(route); }
    else { await P.searchRide(pwa.page, { from: route.pickup, to: route.dropoff, routeText: route.title, cardText: `${price} ₽` }); await P.bookRide(pwa.page, route); }
    booking = q1("SELECT * FROM booking WHERE ride_id = ? AND passenger_id = ? ORDER BY id DESC LIMIT 1", ride.id, userIdOf(passenger));
    sc.check(booking?.status === "pending", "own booking is pending");
    sc.facts.created = { ride_id: ride.id, booking_id: booking.id, setup_method: "publication and booking through real Android/PWA UI" };
  });
  await sc.step("contacts closed before confirmation in both real clients", async () => {
    if (pair === "A") {
      await P.openDriverCabinet(pwa.page);
      const card = pwa.page.locator(".act-card, article, li").filter({ hasText: passenger.firstName }).filter({ has: P.btn(pwa.page, /^Подтвердить$/) }).last();
      await card.waitFor();
      sc.check(await card.locator('a[href^="tel:"]').count() === 0, "PWA driver's actual pending card has no passenger phone action");
    } else { await AF.openDriverCabinet(); await A.scrollTo(passenger.firstName); sc.check(!A.find(A.dump(), /^Позвонить$/), "Android driver's visible pending card has no passenger call action"); }
    await passengerTrip();
    if (pair === "A") { const text = (await AF.screenTexts(3)).join("\n"); noSecretsInText(text, "Android pending passenger"); sc.check(!A.find(A.dump(), /^Позвонить$/), "Android pending passenger has no call action"); }
    else { const details = await webTripWithObservedDetails(pwa.page, booking.id); sc.check(details.contact_unlocked === false && !details.driver_phone, "PWA's own pending details response contains no driver phone"); }
  });
  await sc.step("driver confirms and both participants can contact each other", async () => {
    if (pair === "A") await P.driverDecide(pwa.page, { passenger: passenger.firstName, action: "Подтвердить" });
    else await AF.driverDecide({ passenger: passenger.firstName, action: "Подтвердить" });
    await until(() => fresh().status === "confirmed", "driver confirmation saved");
    await passengerTrip();
    if (pair === "A") { await A.scrollTo(/^Позвонить$/); await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName }); }
    else { await pwa.page.locator('a[href^="tel:"]').first().waitFor(); await AF.driverCardAction({ passenger: passenger.firstName, action: /^Открыть поездку$/ }); await A.scrollTo(/^Позвонить$/); }
    sc.check(pair === "A" ? await pwa.page.locator('a[href^="tel:"]').count() > 0 : true, "confirmed PWA participant has contact action");
  });
  await sc.step("foreign account cannot open booking or its private stops", async () => {
    stranger = await pwaClient(ACC.pwaStranger); sc.pages.push(stranger.page);
    await stranger.page.goto(P.PWA + `/trip/${booking.id}`, { waitUntil: "domcontentloaded" });
    await stranger.page.getByText(/Не получилось|Не удалось|нет доступа|не найдена|недоступна|Не твоя|Бронь не найдена|Поездка не найдена/i).first().waitFor();
    noSecretsInText(await stranger.page.locator("body").innerText(), "PWA foreign booking");
    sc.check(await stranger.page.locator('a[href^="tel:"]').count() === 0, "foreign PWA has no phone actions");
    sc.check(!(await stranger.page.locator("body").innerText()).includes(`${route.pickup} → ${route.dropoff}`), "foreign PWA does not disclose booked private segment");
    await P.shot(stranger.page, "foreign-booking-denied.png");
  });
  await sc.step("switch account in both clients; old trip stays protected", async () => {
    await pwaLogoutAndLogin(ACC.pwaStranger);
    await pwa.page.goto(P.PWA + `/trip/${booking.id}`, { waitUntil: "domcontentloaded" });
    await pwa.page.getByText(/Не получилось|Не удалось|нет доступа|не найдена|недоступна|Не твоя|Бронь не найдена|Поездка не найдена/i).first().waitFor();
    noSecretsInText(await pwa.page.locator("body").innerText(), "PWA after account switch");
    sc.check(await pwa.page.locator('a[href^="tel:"]').count() === 0, "PWA switched account has no previous phone actions");
    await pwaLogoutAndLogin(webActor);
    await androidAs(foreignNative); await androidTrip(booking.id, foreignNative);
    await A.waitFor(/Не получилось|Не удалось|нет доступа|не найдена|недоступна|Не твоя|Бронь не найдена|Поездка не найдена/i);
    noSecretsInText(A.dump().map(n => n.text || "").join("\n"), "Android after account switch");
    sc.check(!A.find(A.dump(), /^Позвонить$/), "Android switched account has no previous phone action");
    A.screencap("foreign-android-after-switch"); await androidAs(nativeActor);
  });
  await sc.step("add close contact and open public link in independent browser profile", async () => {
    await addContact();
    family = await openProfile(`family-s05-${pair}-${tag}`, { fresh: true }); sc.pages.push(family.page);
    const firstLink = await share();
    await openActiveFamilyLink(firstLink, "family-active-before-revoke.png");
    await revoke(); const revoked = await family.page.reload({ waitUntil: "domcontentloaded" });
    sc.check(revoked?.status() === 404 && /Ссылка не найдена/.test(await family.page.locator("body").innerText()), "reopened revoked public link returns the actual 404 denial");
    sc.check(await family.page.locator('a[href^="tel:"]').count() === 0, "revoked public page has no contact actions");
    const nextLink = await share(); sc.check(nextLink !== firstLink, "sharing after revocation creates a different capability");
    await openActiveFamilyLink(nextLink, "family-active-new-capability.png");
  });
  await sc.step("finish actual trip through driver UI; family sees final state without map", async () => {
    if (pair === "A") { await P.driverBoard(pwa.page, { passenger: passenger.firstName }); await P.driverOpenTrip(pwa.page, { passenger: passenger.firstName }); await P.tripButton(pwa.page, "Завершить поездку"); }
    else { await AF.driverBoard({ passenger: passenger.firstName }); await AF.driverComplete({ passenger: passenger.firstName }); }
    await until(() => fresh().status === "done", "real driver UI completed own booking");
    await family.page.locator("#done").waitFor({ state: "visible", timeout: 20000 });
    sc.check(/Поездка завершена/.test(await family.page.locator("#done-title").innerText()), "family page describes actual completion");
    sc.check(await family.page.locator("#map").isVisible() === false, "completed family page hides map");
    noSecretsInText(await family.page.locator("body").innerText(), "completed family page");
    await P.shot(family.page, "family-completed-no-map.png");
  });
  await sc.step("historical synthetic fixture: expired contact window is enforced in both real clients", async () => {
    const fixturePath = process.env.RS_E2E05_EXPIRED_FIXTURE;
    sc.check(!!fixturePath, "RS_E2E05_EXPIRED_FIXTURE is required; missing 48h fixture must not become PASS");
    const manifest = JSON.parse(readFileSync(fixturePath, "utf8"));
    const f = manifest.pairs?.[pair];
    sc.check(manifest.setup_method === "synthetic-historical-fixture-not-user-ui" && resolve(manifest.stand_db) === resolve(DB_PATH) && f, "expired fixture is explicitly synthetic setup in this temporary stand");
    const b = q1("SELECT * FROM booking WHERE id = ?", f.booking_id);
    const r = b && q1("SELECT * FROM ride WHERE id = ?", b.ride_id);
    sc.check(b && r && b.status === "done" && b.passenger_id === userIdOf(passenger) && r.driver_id === userIdOf(driver) && r.id === f.ride_id, "fixture belongs to this synthetic driver/passenger pair");
    sc.check(utc(r.depart_at) + 48 * 3600000 < Date.now() && (!b.lost_item_until || utc(b.lost_item_until) < Date.now()), "fixture is genuinely past default 48h window without active lost-item extension");
    sc.facts.fixture_setup = { method: manifest.setup_method, manifest: resolve(fixturePath), booking_id: b.id, ride_id: r.id, created_by_user_ui: false, actual_clock: true, window_hours: 48 };
    const details = await webTripWithObservedDetails(pwa.page, b.id);
    sc.check(details.contact_unlocked === false && !details.driver_phone && !details.passenger_phone, "PWA's own expired-trip details response contains no participant phones");
    sc.check(await pwa.page.locator('a[href^="tel:"]').count() === 0, "PWA expired trip has no call action");
    const wireStart = await wireFrom();
    await androidTrip(b.id, nativeActor); await sleep(1800);
    const texts = (await AF.screenTexts(4)).join("\n");
    noSecretsInText(texts, "Android expired trip");
    sc.check(!texts.split("\n").some(t => t === "Позвонить"), "Android expired trip has no call action throughout scrolled content");
    const wire = await wireSince(wireStart);
    sc.check(wire.some(event => String(event.path || event.url || "").includes(`/bookings/${b.id}/`) && Number(event.status || event.response_status) === 200), "Android navigation really loaded this expired booking through its API boundary");
    A.screencap("expired-contact-window"); await P.shot(pwa.page, "expired-contact-window.png");
  });
  sc.facts.local_execution = "passed";
}, async () => {
  const failures = [];
  for (const client of [family, stranger, pwa]) if (client) {
    try { await client.ctx.close(); } catch (error) { failures.push(error.message); }
  }
  if (failures.length) throw new Error("Browser cleanup failed: " + failures.join("; "));
});
